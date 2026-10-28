package io.github.olrut.vocab.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.olrut.vocab.audio.AudioCache
import io.github.olrut.vocab.data.VocabDb
import io.github.olrut.vocab.data.WordDraft
import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.Sense
import io.github.olrut.vocab.kindle.ImportError
import io.github.olrut.vocab.kindle.KindleFileException
import io.github.olrut.vocab.kindle.KindleVocab
import io.github.olrut.vocab.kindle.KindleWord
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportControllerTest {
    private lateinit var db: VocabDb
    private lateinit var dir: File
    private lateinit var repo: WordRepository
    private val lookups = mutableListOf<String>()

    private val vocab = KindleVocab(
        listOf(KindleWord("newer", null, null, null, false, 2000, null), KindleWord("older", null, null, null, false, 1000, null)),
        2000,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VocabDb::class.java).allowMainThreadQueries().build()
        dir = Files.createTempDirectory("audio").toFile()
        repo = WordRepository(db, AudioCache(dir, OkHttpClient()))
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun answer(w: String): Lookup =
        if (w == "missing") Lookup.NotFound else Lookup.Found("/$w/", "https://x/$w.mp3", listOf(Sense(null, "def of $w", null)))

    private fun TestScope.controller() = ImportController(
        repo,
        lookup = { w, _ -> answer(w) },
        dictionary = { w, _ ->
            synchronized(lookups) { lookups += w }
            answer(w)
        },
        scope = backgroundScope,
    )

    @Test
    fun `a Kindle file shows its words with the new ones picked`() = runTest {
        repo.add(WordDraft("older", "mine"))
        val c = controller()
        c.showKindle { vocab }
        val s = c.state.value as ImportState.Kindle
        assertFalse(s.onlyNew)
        assertEquals(listOf("newer", "older"), s.candidates.map { it.word.word })
        assertEquals(setOf("newer"), s.selected)
        c.toggle("older")
        c.toggle("newer")
        assertEquals(setOf("older"), (c.state.value as ImportState.Kindle).selected)
        c.selectAll(true)
        assertEquals(setOf("newer", "older"), (c.state.value as ImportState.Kindle).selected)
        c.selectAll(false)
        assertTrue((c.state.value as ImportState.Kindle).selected.isEmpty())
    }

    @Test
    fun `after a first import only new words are offered, and that can be turned off`() = runTest {
        repo.recordKindleImport(1000)
        val c = controller()
        c.showKindle { vocab }
        val s = c.state.value as ImportState.Kindle
        assertTrue(s.onlyNew)
        assertEquals(listOf("newer"), s.candidates.map { it.word.word })
        c.setOnlyNew(false)!!.join()
        assertEquals(2, (c.state.value as ImportState.Kindle).candidates.size)
    }

    @Test
    fun `an unreadable Kindle file is reported`() = runTest {
        val c = controller()
        c.showKindle { throw KindleFileException("This is not a Kindle vocab.db file") }
        assertEquals(ImportState.Idle("This is not a Kindle vocab.db file", error = true), c.state.value)
    }

    @Test
    fun `the import runs the picked words and finishes with the counts`() = runTest {
        val c = controller()
        c.showKindle { vocab }
        c.toggle("older")
        c.startKindle()!!.join()
        val done = (c.state.value as ImportState.Finished).progress
        // Only the picked word.
        assertEquals(1, done.imported)
        assertEquals(null, repo.find("older"))
        assertEquals(emptyList<ImportError>(), done.errors)
        assertEquals("def of newer", repo.find("newer")!!.definition)
    }

    @Test
    fun `a csv import reports its counts and fills in pronunciations afterwards`() = runTest {
        repo.add(WordDraft("old", "x"))
        val c = controller()
        c.importFile("word,definition,ipa\nnew,d,\nold,d,\nmissing,d,\nhas,d,/h/\n", FileFormat.Csv)
        assertEquals(ImportState.Idle("Imported 3 words, skipped 1 already on your list."), c.state.value)
        advanceUntilIdle()
        // Room answers on its own threads; wait for the background fill to land.
        repeat(50) { if (repo.find("new")!!.audioUrl == null) Thread.sleep(20) }
        assertEquals("/new/", repo.find("new")!!.ipa)
        assertEquals("https://x/new.mp3", repo.find("new")!!.audioUrl)
        assertEquals("/h/", repo.find("has")!!.ipa)
        assertEquals(null, repo.find("missing")!!.ipa)
        assertFalse("old" in lookups)
    }

    @Test
    fun `a bad file imports nothing and says why`() = runTest {
        val c = controller()
        c.importFile("word\nok\n \n", FileFormat.Csv)
        c.importFile("definition\nx\n", FileFormat.Csv)
        assertEquals(ImportState.Idle("Missing column: word", error = true), c.state.value)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `export writes every word`() = runTest {
        repo.add(WordDraft("a", "x"))
        repo.add(WordDraft("b"))
        val c = controller()
        val csv = c.export(FileFormat.Csv)
        assertEquals(3, csv.trimEnd().lines().size)
        c.exported(2)
        assertEquals(ImportState.Idle("Exported 2 words."), c.state.value)
        c.exported(1)
        assertEquals(ImportState.Idle("Exported 1 word."), c.state.value)
    }
}
