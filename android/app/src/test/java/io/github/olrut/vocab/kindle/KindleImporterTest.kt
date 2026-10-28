package io.github.olrut.vocab.kindle

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.olrut.vocab.audio.AudioCache
import io.github.olrut.vocab.data.VocabDb
import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordDraft
import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.Sense
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KindleImporterTest {
    private lateinit var db: VocabDb
    private lateinit var dir: File
    private lateinit var repo: WordRepository
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val answers = mutableMapOf<String, Lookup>()

    private val dictionary = { w: String -> asked += w; answers[w] ?: Lookup.NotFound }
    private fun importer() = KindleImporter(repo, { dictionary(it) })

    private fun kw(word: String, ts: Long, usage: String? = null, mastered: Boolean = false, lookupAt: Long? = null) =
        KindleWord(word, usage, "Book", "Author", mastered, ts, lookupAt)

    private val vocab = KindleVocab(
        listOf(
            kw("ephemeral", 3000, usage = "An ephemeral joy.", lookupAt = 1_700_000_000_000),
            kw("sanguine", 2000, mastered = true),
            kw("qwzx", 1000),
        ),
        3000,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VocabDb::class.java).allowMainThreadQueries().build()
        dir = Files.createTempDirectory("audio").toFile()
        repo = WordRepository(db, AudioCache(dir, OkHttpClient()))
        answers["ephemeral"] = Lookup.Found("/ɪˈfɛm(ə)ɹəl/", "https://x/eph.mp3", listOf(Sense("adjective", "Lasting a short time.", "Ephemeral fame.")))
        answers["sanguine"] = Lookup.Found(null, null, listOf(Sense("adjective", "Hopeful.", "She is sanguine.")))
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `candidates mark words already on the list`() = runTest {
        repo.add(WordDraft("sanguine", "x"))
        val c = importer().candidates(vocab, onlyNew = false)
        assertEquals(listOf("ephemeral" to false, "sanguine" to true, "qwzx" to false), c.map { it.word.word to it.exists })
    }

    @Test
    fun `imports with the book sentence as the example and the dictionary's definition`() = runTest {
        val p = importer().import(vocab, listOf("ephemeral", "sanguine", "qwzx"), markMastered = true)
        assertEquals(ImportProgress(done = 3, total = 3, imported = 3, skipped = 0, errors = emptyList(), finished = true), p)
        val e = repo.find("ephemeral")!!
        assertEquals("Lasting a short time.", e.definition)
        assertEquals("An ephemeral joy.", e.example)
        assertEquals("/ɪˈfɛm(ə)ɹəl/", e.ipa)
        assertEquals("https://x/eph.mp3", e.audioUrl)
        assertEquals(Word.SOURCE_KINDLE, e.source)
        assertEquals("Book", e.bookTitle)
        assertEquals("Author", e.bookAuthor)
        assertEquals("2023-11-14T22:13:20.000Z", e.kindleLookupAt)
        assertEquals(Word.LEARNING, e.status)
        // No sentence from the book: the dictionary's example.
        assertEquals("She is sanguine.", repo.find("sanguine")!!.example)
        assertEquals(Word.LEARNED, repo.find("sanguine")!!.status)
        // Not in the dictionary: imported anyway, to be filled in.
        val q = repo.find("qwzx")!!
        assertNull(q.definition)
        assertEquals(3000L, repo.lastKindleTimestamp())
    }

    @Test
    fun `mastered words stay learning when asked`() = runTest {
        importer().import(vocab, listOf("sanguine"), markMastered = false)
        assertEquals(Word.LEARNING, repo.find("sanguine")!!.status)
    }

    @Test
    fun `an existing word is skipped, not looked up, and marked learned if mastered`() = runTest {
        repo.add(WordDraft("sanguine", "mine"))
        val p = importer().import(vocab, listOf("sanguine"), markMastered = true)
        assertEquals(1, p.skipped)
        assertEquals(0, p.imported)
        assertTrue(asked.isEmpty())
        val s = repo.find("sanguine")!!
        assertEquals("mine", s.definition)
        assertEquals(Word.LEARNED, s.status)
    }

    @Test
    fun `a failed lookup is reported and holds the cutoff back for the next import`() = runTest {
        answers["sanguine"] = Lookup.Failed("No connection to the dictionary")
        val p = importer().import(vocab, listOf("ephemeral", "sanguine", "qwzx"), markMastered = true)
        assertEquals(listOf(ImportError("sanguine", "No connection to the dictionary")), p.errors)
        assertEquals(2, p.imported)
        assertNull(repo.find("sanguine"))
        assertEquals(1999L, repo.lastKindleTimestamp())
        // "Only new" now offers the failed word again, but not the older ones.
        assertEquals(listOf("ephemeral", "sanguine"), importer().candidates(vocab, onlyNew = true).map { it.word.word })
    }

    @Test
    fun `only new skips what an earlier import already saw, selected or not`() = runTest {
        importer().import(KindleVocab(vocab.words.drop(1), 2000), listOf("qwzx"), markMastered = true)
        assertEquals(listOf("ephemeral"), importer().candidates(vocab, onlyNew = true).map { it.word.word })
        assertEquals(3, importer().candidates(vocab, onlyNew = false).size)
    }

    @Test
    fun `an older file never moves the cutoff back`() = runTest {
        importer().import(vocab, emptyList(), markMastered = true)
        importer().import(KindleVocab(vocab.words.drop(2), 1000), emptyList(), markMastered = true)
        assertEquals(3000L, repo.lastKindleTimestamp())
    }

    @Test
    fun `words not in the file are errors, repeats count once, progress is reported`() = runTest {
        val seen = mutableListOf<Int>()
        val p = importer().import(vocab, listOf("ephemeral", "EPHEMERAL", "nope"), markMastered = true) { seen += it.done }
        assertEquals(2, p.total)
        assertEquals(listOf(ImportError("nope", "Not in the file")), p.errors)
        assertEquals(1, p.imported)
        assertEquals(listOf(0, 1, 2), seen.distinct().sorted())
    }
}
