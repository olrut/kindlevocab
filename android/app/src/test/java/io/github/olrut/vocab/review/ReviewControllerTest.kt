package io.github.olrut.vocab.review

import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A store in memory. */
private class FakeStore : WordStore {
    val words = (1L..3L).associateWith { word(it) }.toMutableMap()
    val changes = mutableListOf<Pair<Long, String>>()
    var reads = 0
    var failRead: Exception? = null
    var failWrite: Exception? = null
    /** When set, the next read waits for it, so a test can hold one in flight. */
    var readGate: CompletableDeferred<Unit>? = null

    override suspend fun learning(): List<Word> {
        reads++
        failRead?.let { throw it }
        val snapshot = words.values.filter { it.status == Word.LEARNING }
        readGate?.let { readGate = null; it.await() }
        return snapshot
    }

    override suspend fun setStatus(id: Long, status: String) {
        failWrite?.let { throw it }
        changes += id to status
        words[id] = words.getValue(id).copy(status = status)
    }
}

class ReviewControllerTest {
    private fun TestScope.setup(store: FakeStore = FakeStore()): Pair<ReviewController, FakeStore> {
        val c = ReviewController(store, backgroundScope, Random(7))
        c.load()
        runCurrent()
        return c to store
    }

    @Test
    fun `loads the words into a shuffled deck`() = runTest {
        val (c, store) = setup()
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(setOf(1L, 2L, 3L), s.deck.queue.map { it.id }.toSet())
        assertFalse(s.revealed)
        assertEquals(1, store.reads)
    }

    @Test
    fun `is loading until the first read`() = runTest {
        val c = ReviewController(FakeStore(), backgroundScope, Random(7))
        assertTrue(c.state.value.loading)
    }

    @Test
    fun `words added meanwhile join the deck on the next load, the current word stays`() = runTest {
        val (c, store) = setup()
        val current = c.state.value.deck.current!!
        store.words[9L] = word(9)
        c.load()
        runCurrent()
        assertEquals(current.id, c.state.value.deck.current!!.id)
        assertEquals(setOf(1L, 2L, 3L, 9L), c.state.value.deck.queue.map { it.id }.toSet())
    }

    @Test
    fun `a failed read is reported and ends loading`() = runTest {
        val store = FakeStore()
        store.failRead = IllegalStateException("disk full")
        val (c, _) = setup(store)
        assertFalse(c.state.value.loading)
        assertEquals("Could not read your words: disk full", c.state.value.error)
    }

    @Test
    fun `tap reveals, next hides again and moves on`() = runTest {
        val (c, _) = setup()
        val first = c.state.value.deck.current
        c.reveal()
        assertTrue(c.state.value.revealed)
        c.next()
        assertFalse(c.state.value.revealed)
        assertEquals(c.state.value.deck.queue[1], c.state.value.deck.current)
        assertTrue(first != c.state.value.deck.current)
    }

    @Test
    fun `mark learned advances at once, stores the change, reads again and offers undo`() = runTest {
        val (c, store) = setup()
        val w = c.state.value.deck.current!!
        c.reveal()
        c.markLearned()
        assertFalse(w in c.state.value.deck.queue)
        assertFalse(c.state.value.revealed)
        assertEquals(w, c.state.value.undo)
        runCurrent()
        assertEquals(listOf(w.id to Word.LEARNED), store.changes)
        assertEquals(2, store.reads)
        assertFalse(c.state.value.deck.queue.any { it.id == w.id })
        assertNull(c.state.value.error)
    }

    @Test
    fun `a failed save is reported`() = runTest {
        val (c, store) = setup()
        store.failWrite = IllegalStateException("disk full")
        val w = c.state.value.deck.current!!
        c.markLearned()
        runCurrent()
        assertEquals("Could not save “${w.word}”: disk full", c.state.value.error)
        c.clearError()
        assertNull(c.state.value.error)
    }

    @Test
    fun `undo brings the word back as the current one and stores learning after learned`() = runTest {
        val (c, store) = setup()
        val w = c.state.value.deck.current!!
        c.markLearned()
        c.undo()
        assertEquals(w, c.state.value.deck.current)
        assertNull(c.state.value.undo)
        runCurrent()
        assertEquals(listOf(w.id to Word.LEARNED, w.id to Word.LEARNING), store.changes)
        assertEquals(w.id, c.state.value.deck.current!!.id)
    }

    @Test
    fun `a read that started before mark learned does not bring the word back`() = runTest {
        val store = FakeStore()
        val c = ReviewController(store, backgroundScope, Random(7))
        c.load()
        runCurrent()
        // A read of the store is in flight when the word is swiped.
        val gate = CompletableDeferred<Unit>()
        store.readGate = gate
        c.load()
        runCurrent()
        val w = c.state.value.deck.current!!
        c.markLearned()
        runCurrent()
        // The old read lands last, after the change was stored and read back.
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(w.id to Word.LEARNED), store.changes)
        assertFalse(c.state.value.deck.queue.any { it.id == w.id })
    }

    @Test
    fun `dismissing the undo offer keeps the word learned`() = runTest {
        val (c, store) = setup()
        c.markLearned()
        c.dismissUndo()
        runCurrent()
        assertNull(c.state.value.undo)
        assertEquals(1, store.changes.size)
    }

    @Test
    fun `marking every word learned empties the deck`() = runTest {
        val (c, _) = setup()
        repeat(3) { c.markLearned() }
        runCurrent()
        assertNull(c.state.value.deck.current)
        assertFalse(c.state.value.loading)
    }
}
