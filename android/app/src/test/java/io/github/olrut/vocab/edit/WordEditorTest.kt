package io.github.olrut.vocab.edit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.olrut.vocab.audio.AudioCache
import io.github.olrut.vocab.data.VocabDb
import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordDraft
import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.Sense
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class WordEditorTest {
    private lateinit var db: VocabDb
    private lateinit var dir: File
    private lateinit var repo: WordRepository
    private val asked = mutableListOf<String>()
    private var answer: Lookup = Lookup.NotFound
    private var gate: CompletableDeferred<Unit>? = null

    private val found = Lookup.Found(
        "/rʌn/", "https://x/run.mp3",
        listOf(Sense("verb", "Move fast on foot.", "She ran home."), Sense("noun", "An act of running.", null)),
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

    private fun TestScope.editor() = WordEditor(repo, { w -> asked += w; gate?.await(); answer }, backgroundScope)

    @Test
    fun `a lookup offers the senses and keeps the pronunciation`() = runTest {
        answer = found
        val e = editor()
        e.setWord(" Run")
        e.lookUp()
        assertTrue(e.state.value.lookup is LookupState.Busy)
        runCurrent()
        assertEquals(listOf("Run"), asked)
        assertEquals(found.senses, (e.state.value.lookup as LookupState.Senses).senses)
        assertEquals("/rʌn/", e.state.value.form.ipa)
        assertEquals("https://x/run.mp3", e.state.value.form.audioUrl)
    }

    @Test
    fun `picking a sense fills the definition and example and marks it from the dictionary`() = runTest {
        answer = found
        val e = editor()
        e.setWord("run")
        e.lookUp()
        runCurrent()
        e.pick(found.senses[0])
        val f = e.state.value.form
        assertEquals("Move fast on foot.", f.definition)
        assertEquals("She ran home.", f.example)
        assertEquals(Word.SOURCE_DICTIONARY, f.source)
        assertEquals(LookupState.Idle, e.state.value.lookup)
    }

    @Test
    fun `picking a sense without an example keeps what the user wrote`() = runTest {
        answer = found
        val e = editor()
        e.setWord("run")
        e.update { it.copy(example = "I run daily.") }
        e.lookUp()
        runCurrent()
        e.pick(found.senses[1])
        assertEquals("I run daily.", e.state.value.form.example)
    }

    @Test
    fun `not found and failure are shown, and nothing is filled`() = runTest {
        val e = editor()
        e.setWord("qwzx")
        e.lookUp()
        runCurrent()
        assertEquals(LookupState.NotFound, e.state.value.lookup)
        answer = Lookup.Failed("No connection to the dictionary")
        e.lookUp()
        runCurrent()
        assertEquals(LookupState.Failed("No connection to the dictionary"), e.state.value.lookup)
        assertNull(e.state.value.form.definition)
    }

    @Test
    fun `an empty word is not looked up`() = runTest {
        val e = editor()
        e.lookUp()
        runCurrent()
        assertTrue(asked.isEmpty())
        assertEquals(LookupState.Idle, e.state.value.lookup)
    }

    @Test
    fun `changing the word drops a lookup answer that is late or for the old word`() = runTest {
        answer = found
        gate = CompletableDeferred()
        val e = editor()
        e.setWord("run")
        e.lookUp()
        runCurrent()
        e.setWord("walk")
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(LookupState.Idle, e.state.value.lookup)
        assertNull(e.state.value.form.ipa)
    }

    @Test
    fun `changing the word text after a lookup forgets its pronunciation`() = runTest {
        answer = found
        val e = editor()
        e.setWord("run")
        e.lookUp()
        runCurrent()
        e.setWord("runs")
        assertNull(e.state.value.form.ipa)
        assertNull(e.state.value.form.audioUrl)
    }

    @Test
    fun `save adds a new word and reports done`() = runTest {
        answer = found
        val e = editor()
        e.setWord("Run")
        e.lookUp()
        runCurrent()
        e.pick(found.senses[0])
        e.update { it.copy(finnish = "juosta") }
        e.save().join()
        assertTrue(e.state.value.done)
        val w = repo.all().single()
        assertEquals("run", w.word)
        assertEquals("juosta", w.finnish)
        assertEquals("https://x/run.mp3", w.audioUrl)
        assertEquals(Word.SOURCE_DICTIONARY, w.source)
    }

    @Test
    fun `a word without a definition can still be saved`() = runTest {
        val e = editor()
        e.setWord("qwzx")
        e.save().join()
        assertTrue(e.state.value.done)
        assertNull(repo.all().single().definition)
    }

    @Test
    fun `saving a duplicate shows an error and stays open`() = runTest {
        repo.add(WordDraft("run", "x"))
        val e = editor()
        e.setWord("RUN")
        e.save().join()
        assertFalse(e.state.value.done)
        assertEquals("“run” is already on your list", e.state.value.error)
        assertFalse(e.state.value.saving)
    }

    @Test
    fun `an invalid word shows the reason`() = runTest {
        val e = editor()
        e.save().join()
        assertEquals("The word is empty", e.state.value.error)
    }

    @Test
    fun `editing loads the word and saves changes to it`() = runTest {
        val w = repo.add(WordDraft("run", "old", notes = "mine", audioUrl = "https://x/run.mp3"))
        val e = editor()
        e.edit(w.id).join()
        val f = e.state.value.form
        assertEquals(w.id, f.id)
        assertEquals("run", f.word)
        assertEquals("mine", f.notes)
        e.update { it.copy(definition = "new") }
        e.save().join()
        assertTrue(e.state.value.done)
        val saved = repo.get(w.id)!!
        assertEquals("new", saved.definition)
        assertEquals("https://x/run.mp3", saved.audioUrl)
        assertEquals(1, repo.all().size)
    }

    @Test
    fun `status and delete act on the edited word`() = runTest {
        val w = repo.add(WordDraft("run", "x"))
        val e = editor()
        e.edit(w.id).join()
        e.setStatus(Word.LEARNED)!!.join()
        assertEquals(Word.LEARNED, e.state.value.form.status)
        assertEquals(Word.LEARNED, repo.get(w.id)!!.status)
        e.delete()!!.join()
        assertTrue(e.state.value.done)
        assertNull(repo.get(w.id))
    }
}
