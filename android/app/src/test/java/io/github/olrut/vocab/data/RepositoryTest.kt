package io.github.olrut.vocab.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.olrut.vocab.api.Changes
import io.github.olrut.vocab.api.LoginRequest
import io.github.olrut.vocab.api.LoginResponse
import io.github.olrut.vocab.api.StatusPatch
import io.github.olrut.vocab.api.User
import io.github.olrut.vocab.api.VocabApi
import io.github.olrut.vocab.api.Word
import io.github.olrut.vocab.audio.AudioCache
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

private fun w(id: Long, updatedAt: String, status: String = "learning", hasAudio: Boolean = false, deletedAt: String? = null) =
    Word(id = id, word = "w$id", definition = "def $id", hasAudio = hasAudio, status = status, updatedAt = updatedAt, deletedAt = deletedAt)

/** The server, in memory: words by id, with `updated_at` from [clock]. */
private class FakeServer : VocabApi {
    var clock = "2026-10-01T10:00:00.000Z"
    val words = mutableMapOf<Long, Word>()
    val sinces = mutableListOf<String>()
    val patches = mutableListOf<Pair<Long, String>>()
    var offline = false
    var failPatch: Int? = null
    val audioRequests = mutableListOf<Long>()

    fun put(word: Word) {
        words[word.id] = word
    }

    override suspend fun changes(since: String): Changes {
        if (offline) throw IOException("offline")
        sinces += since
        return Changes(serverTime = clock, words = words.values.filter { Instant.parse(it.updatedAt) > Instant.parse(since) }.sortedBy { it.id })
    }

    override suspend fun setStatus(id: Long, body: StatusPatch): Word {
        if (offline) throw IOException("offline")
        failPatch?.let { throw HttpException(Response.error<Word>(it, "{}".toResponseBody())) }
        val current = words[id]?.takeIf { it.deletedAt == null }
            ?: throw HttpException(Response.error<Word>(404, """{"error":"not found"}""".toResponseBody()))
        patches += id to body.status
        val updated = current.copy(status = body.status, updatedAt = clock, learnedAt = if (body.status == "learned") clock else null)
        words[id] = updated
        return updated
    }

    override suspend fun audio(id: Long): ResponseBody {
        audioRequests += id
        return "mp3 $id".toResponseBody()
    }

    override suspend fun users(): List<User> = error("unused")
    override suspend fun login(body: LoginRequest): LoginResponse = error("unused")
    override suspend fun logout() = error("unused")
    override suspend fun words(status: String): List<Word> = error("unused")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VocabDb::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()
    private val server = FakeServer()
    private val audioDir: File = Files.createTempDirectory("audio").toFile()
    private val audio = AudioCache(audioDir, server)
    private var phoneClock = "2026-10-01T12:00:00.000Z"

    @After
    fun tearDown() {
        db.close()
        audioDir.deleteRecursively()
    }

    private fun TestScope.repo() = Repository(db, server, audio, backgroundScope, now = { Instant.parse(phoneClock) })

    @Test
    fun `the first sync pulls everything and remembers the server time`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        server.put(w(2, "2026-10-01T09:00:00.000Z", status = "learned"))
        val repo = repo()
        repo.sync()
        assertEquals(listOf("1970-01-01T00:00:00.000Z"), server.sinces)
        assertEquals(listOf(1L), repo.learning().map { it.id })
        assertEquals("learned", dao.word(2)!!.status)
        assertEquals("2026-10-01T10:00:00.000Z", dao.lastSync())
    }

    @Test
    fun `later syncs ask only for changes since the last one, and upsert them`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        server.clock = "2026-10-01T11:00:00.000Z"
        server.put(w(1, "2026-10-01T10:30:00.000Z").copy(definition = "edited"))
        server.put(w(3, "2026-10-01T10:30:00.000Z"))
        repo.sync()
        assertEquals("2026-10-01T10:00:00.000Z", server.sinces.last())
        assertEquals(setOf(1L, 3L), repo.learning().map { it.id }.toSet())
        assertEquals("edited", dao.word(1)!!.definition)
        assertEquals("2026-10-01T11:00:00.000Z", dao.lastSync())
    }

    @Test
    fun `a word deleted on the server is removed with its pending change and recording`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z", hasAudio = true))
        val repo = repo()
        repo.sync()
        repo.awaitAudio()
        assertTrue(audio.has(1))
        server.offline = true
        repo.setStatus(1, "learned")
        server.offline = false
        server.put(w(1, "2026-10-01T12:30:00.000Z", deletedAt = "2026-10-01T12:30:00.000Z"))
        repo.sync()
        assertNull(dao.word(1))
        assertTrue(dao.pending().isEmpty())
        assertFalse(audio.has(1))
        assertTrue(server.patches.isEmpty())
    }

    @Test
    fun `marking learned changes the local word and queues the change, without calling the server`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        server.offline = true
        repo.setStatus(1, "learned")
        assertTrue(repo.learning().isEmpty())
        assertEquals("learned", dao.word(1)!!.status)
        assertEquals(phoneClock, dao.word(1)!!.learnedAt)
        assertEquals(listOf(PendingChange(1, 1, "learned", phoneClock)), dao.pending())
        assertTrue(server.patches.isEmpty())
    }

    @Test
    fun `sync pushes pending changes, deletes them and keeps the server's reply`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        server.put(w(2, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.setStatus(1, "learned")
        repo.setStatus(2, "learned")
        server.clock = "2026-10-01T12:00:05.000Z"
        repo.sync()
        assertEquals(listOf(1L to "learned", 2L to "learned"), server.patches)
        assertTrue(dao.pending().isEmpty())
        assertEquals("2026-10-01T12:00:05.000Z", dao.word(1)!!.updatedAt)
        assertEquals("2026-10-01T12:00:05.000Z", dao.word(1)!!.learnedAt)
    }

    @Test
    fun `offline, the change waits and goes out on the next sync that reaches the server`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.setStatus(1, "learned")
        server.offline = true
        try {
            repo.sync()
            fail("expected the sync to fail")
        } catch (_: IOException) {
        }
        assertEquals(1, dao.pending().size)
        assertEquals("2026-10-01T10:00:00.000Z", dao.lastSync())
        server.offline = false
        repo.sync()
        assertEquals(listOf(1L to "learned"), server.patches)
        assertTrue(dao.pending().isEmpty())
    }

    @Test
    fun `a server error keeps the change for later`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.setStatus(1, "learned")
        server.failPatch = 500
        try {
            repo.sync()
            fail("expected the sync to fail")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }
        assertEquals(1, dao.pending().size)
        assertEquals("learned", dao.word(1)!!.status)
    }

    @Test
    fun `a 404 drops the change and the word`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.setStatus(1, "learned")
        server.words.remove(1)
        repo.sync()
        assertTrue(dao.pending().isEmpty())
        assertNull(dao.word(1))
    }

    @Test
    fun `undo before the push removes the pending change, so the server hears nothing`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.setStatus(1, "learned")
        repo.setStatus(1, "learning")
        assertTrue(dao.pending().isEmpty())
        assertEquals("learning", dao.word(1)!!.status)
        assertNull(dao.word(1)!!.learnedAt)
        repo.sync()
        assertTrue(server.patches.isEmpty())
        assertEquals(listOf(1L), repo.learning().map { it.id })
    }

    @Test
    fun `undo after the push sends learning`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.setStatus(1, "learned")
        repo.sync()
        repo.setStatus(1, "learning")
        assertEquals(listOf(PendingChange(2, 1, "learning", phoneClock)), dao.pending())
        repo.sync()
        assertEquals(listOf(1L to "learned", 1L to "learning"), server.patches)
        assertEquals("learning", server.words.getValue(1).status)
    }

    @Test
    fun `a change made on the server after the phone's wins, and the phone's is dropped`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.setStatus(1, "learned") // 12:00 on the phone
        server.put(w(1, "2026-10-01T12:30:00.000Z").copy(definition = "edited on the web"))
        server.clock = "2026-10-01T13:00:00.000Z"
        repo.sync()
        assertTrue(server.patches.isEmpty())
        assertTrue(dao.pending().isEmpty())
        assertEquals("learning", dao.word(1)!!.status)
        assertEquals("edited on the web", dao.word(1)!!.definition)
    }

    @Test
    fun `a change made on the phone after the server's wins and is pushed`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        server.put(w(1, "2026-10-01T11:00:00.000Z").copy(definition = "edited on the web"))
        repo.setStatus(1, "learned") // 12:00 on the phone
        server.clock = "2026-10-01T13:00:00.000Z"
        repo.sync()
        assertEquals(listOf(1L to "learned"), server.patches)
        assertEquals("learned", dao.word(1)!!.status)
        assertEquals("edited on the web", dao.word(1)!!.definition)
    }

    @Test
    fun `recordings are fetched once, in the background, for words that have one`() = runTest {
        server.put(w(1, "2026-10-01T09:00:00.000Z", hasAudio = true))
        server.put(w(2, "2026-10-01T09:00:00.000Z"))
        val repo = repo()
        repo.sync()
        repo.awaitAudio()
        assertEquals(listOf(1L), server.audioRequests)
        assertEquals("mp3 1", audio.file(1).readText())
        repo.sync()
        repo.awaitAudio()
        assertEquals(listOf(1L), server.audioRequests)
    }
}
