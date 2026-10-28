package io.github.olrut.vocab.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.olrut.vocab.audio.AudioCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordRepositoryTest {
    private lateinit var db: VocabDb
    private lateinit var dir: File
    private lateinit var audio: AudioCache
    private var clock = Instant.parse("2026-03-01T10:00:00Z")
    private lateinit var repo: WordRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VocabDb::class.java)
            .allowMainThreadQueries()
            .build()
        dir = Files.createTempDirectory("audio").toFile()
        audio = AudioCache(dir, OkHttpClient())
        repo = WordRepository(db, audio) { clock }
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun draft(word: String = "Serendipity ", definition: String? = "a happy accident") =
        WordDraft(word = word, definition = definition, example = " ", finnish = "onnekas sattuma", notes = "")

    @Test
    fun `add normalises the word, blanks become null, timestamps are set`() = runTest {
        val w = repo.add(draft())
        assertEquals("serendipity", w.word)
        assertNull(w.example)
        assertNull(w.notes)
        assertEquals("onnekas sattuma", w.finnish)
        assertEquals(Word.LEARNING, w.status)
        assertEquals("2026-03-01T10:00:00.000Z", w.createdAt)
        assertEquals(w.createdAt, w.updatedAt)
        assertEquals(w, db.dao().get(w.id))
    }

    @Test
    fun `adding a word already on the list fails, whatever its case`() = runTest {
        repo.add(draft())
        try {
            repo.add(draft(word = "SERENDIPITY"))
            fail("expected a duplicate")
        } catch (e: DuplicateWordException) {
            assertEquals("serendipity", e.word)
        }
    }

    @Test
    fun `an empty or too long word is rejected`() = runTest {
        for (bad in listOf("  ", "x".repeat(101))) {
            try {
                repo.add(draft(word = bad))
                fail("expected $bad to be rejected")
            } catch (e: InvalidWordException) {
                // expected
            }
        }
        assertTrue(db.dao().all().isEmpty())
    }

    @Test
    fun `too long notes are rejected`() = runTest {
        try {
            repo.add(draft().copy(notes = "n".repeat(10_001)))
            fail("expected the notes to be rejected")
        } catch (e: InvalidWordException) {
            assertEquals("Notes can be at most 10000 characters", e.message)
        }
    }

    @Test
    fun `learned sets learnedAt, learning clears it`() = runTest {
        val w = repo.add(draft())
        clock = clock.plusSeconds(60)
        repo.setStatus(w.id, Word.LEARNED)
        val learned = db.dao().get(w.id)!!
        assertEquals(Word.LEARNED, learned.status)
        assertEquals("2026-03-01T10:01:00.000Z", learned.learnedAt)
        assertEquals("2026-03-01T10:01:00.000Z", learned.updatedAt)
        assertTrue(repo.learning().isEmpty())

        repo.setStatus(w.id, Word.LEARNING)
        assertNull(db.dao().get(w.id)!!.learnedAt)
        assertEquals(listOf(w.id), repo.learning().map { it.id })
    }

    @Test
    fun `update keeps the id and creation time and bumps updatedAt`() = runTest {
        val w = repo.add(draft())
        clock = clock.plusSeconds(5)
        val updated = repo.update(w.id, draft(definition = "luck").copy(example = "By serendipity, we met."))
        assertEquals(w.id, updated.id)
        assertEquals(w.createdAt, updated.createdAt)
        assertEquals("2026-03-01T10:00:05.000Z", updated.updatedAt)
        assertEquals("luck", updated.definition)
        assertEquals("By serendipity, we met.", db.dao().get(w.id)!!.example)
    }

    @Test
    fun `update to another word's text fails, to its own text in other case works`() = runTest {
        val a = repo.add(draft(word = "alpha"))
        repo.add(draft(word = "beta"))
        try {
            repo.update(a.id, draft(word = "Beta"))
            fail("expected a duplicate")
        } catch (e: DuplicateWordException) {
            assertEquals("beta", e.word)
        }
        assertEquals("alpha", repo.update(a.id, draft(word = "ALPHA")).word)
    }

    @Test
    fun `changing the word text drops the old recording and pronunciation`() = runTest {
        val a = repo.add(draft(word = "alpha").copy(ipa = "/a/", audioUrl = "https://x/a.mp3"))
        audio.file(a.id).writeText("mp3")
        val same = repo.update(a.id, draft(word = "alpha").copy(ipa = "/a/", audioUrl = "https://x/a.mp3"))
        assertEquals("https://x/a.mp3", same.audioUrl)
        assertTrue(audio.has(a.id))
        val renamed = repo.update(a.id, draft(word = "alps").copy(ipa = "/a/", audioUrl = "https://x/a.mp3"))
        assertNull(renamed.ipa)
        assertNull(renamed.audioUrl)
        assertFalse(audio.has(a.id))
    }

    @Test
    fun `delete removes the word and its recording`() = runTest {
        val w = repo.add(draft())
        audio.file(w.id).writeText("mp3")
        repo.delete(w.id)
        assertNull(db.dao().get(w.id))
        assertFalse(audio.has(w.id))
    }

    @Test
    fun `observe filters by status, newest first`() = runTest {
        val a = repo.add(draft(word = "a"))
        clock = clock.plusSeconds(1)
        val b = repo.add(draft(word = "b"))
        clock = clock.plusSeconds(1)
        val c = repo.add(draft(word = "c"))
        repo.setStatus(b.id, Word.LEARNED)
        assertEquals(listOf(c.id, b.id, a.id), repo.observe(null).first().map { it.id })
        assertEquals(listOf(c.id, a.id), repo.observe(Word.LEARNING).first().map { it.id })
        assertEquals(listOf(b.id), repo.observe(Word.LEARNED).first().map { it.id })
    }

    @Test
    fun `contains matches the normalised word`() = runTest {
        repo.add(draft())
        assertTrue(repo.contains(" SerenDipity"))
        assertFalse(repo.contains("luck"))
    }
}
