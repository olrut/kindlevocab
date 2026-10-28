package io.github.olrut.vocab.review

import io.github.olrut.vocab.api.Word
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

/** A store in memory: [words] is the local table, [server] what a sync brings in. */
private class FakeStore : WordStore {
    val words = (1L..3L).associateWith { word(it) }.toMutableMap()
    var server: Map<Long, Word>? = null
    var failSync: Exception? = null
    val changes = mutableListOf<Pair<Long, String>>()
    var syncs = 0
    /** When set, the next read waits for it, so a test can hold one in flight. */
    var readGate: CompletableDeferred<Unit>? = null
    /** When set, sync waits for it. */
    var syncGate: CompletableDeferred<Unit>? = null

    override suspend fun learning(): List<Word> {
        val snapshot = words.values.filter { it.status == "learning" }
        readGate?.let { readGate = null; it.await() }
        return snapshot
    }

    override suspend fun setStatus(id: Long, status: String) {
        changes += id to status
        words[id] = words.getValue(id).copy(status = status)
    }

    override suspend fun sync() {
        syncGate?.await()
        syncs++
        failSync?.let { throw it }
        server?.let { words.clear(); words.putAll(it) }
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
    fun `loads the local words into a shuffled deck and syncs`() = runTest {
        val (c, store) = setup()
        val s = c.state.value
        assertFalse(s.loading)
        assertFalse(s.syncing)
        assertEquals(setOf(1L, 2L, 3L), s.deck.queue.map { it.id }.toSet())
        assertFalse(s.revealed)
        assertEquals(1, store.syncs)
    }

    @Test
    fun `shows the local words before the sync finishes`() = runTest {
        val store = FakeStore()
        store.syncGate = CompletableDeferred()
        val (c, _) = setup(store)
        assertEquals(3, c.state.value.deck.queue.size)
        assertTrue(c.state.value.syncing)
        store.syncGate!!.complete(Unit)
        runCurrent()
        assertFalse(c.state.value.syncing)
    }

    @Test
    fun `words from the sync join the deck`() = runTest {
        val store = FakeStore()
        store.syncGate = CompletableDeferred()
        val (c, _) = setup(store)
        store.server = store.words + (9L to word(9))
        store.syncGate!!.complete(Unit)
        runCurrent()
        assertEquals(setOf(1L, 2L, 3L, 9L), c.state.value.deck.queue.map { it.id }.toSet())
    }

    @Test
    fun `an automatic sync that fails says so quietly and keeps the words`() = runTest {
        val store = FakeStore()
        store.failSync = java.net.ConnectException("refused")
        val (c, _) = setup(store)
        assertEquals(3, c.state.value.deck.queue.size)
        assertEquals("Cannot reach the server", c.state.value.notSynced)
        assertNull(c.state.value.error)
    }

    @Test
    fun `sync now reports a failure, and a later success clears the note`() = runTest {
        val (c, store) = setup()
        store.failSync = java.net.ConnectException("refused")
        c.syncNow()
        runCurrent()
        assertEquals("Sync failed: Cannot reach the server", c.state.value.error)
        assertEquals("Cannot reach the server", c.state.value.notSynced)
        c.clearError()
        store.failSync = null
        c.syncNow()
        runCurrent()
        assertNull(c.state.value.error)
        assertNull(c.state.value.notSynced)
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
    fun `mark learned advances at once, stores the change, syncs and offers undo`() = runTest {
        val (c, store) = setup()
        val w = c.state.value.deck.current!!
        c.reveal()
        c.markLearned()
        assertFalse(w in c.state.value.deck.queue)
        assertFalse(c.state.value.revealed)
        assertEquals(w, c.state.value.undo)
        runCurrent()
        assertEquals(listOf(w.id to "learned"), store.changes)
        assertEquals(2, store.syncs)
        assertFalse(c.state.value.deck.queue.any { it.id == w.id })
    }

    @Test
    fun `mark learned works the same offline`() = runTest {
        val store = FakeStore()
        store.failSync = java.net.ConnectException("refused")
        val (c, _) = setup(store)
        val w = c.state.value.deck.current!!
        c.markLearned()
        runCurrent()
        assertEquals(listOf(w.id to "learned"), store.changes)
        assertFalse(c.state.value.deck.queue.any { it.id == w.id })
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
        assertEquals(listOf(w.id to "learned", w.id to "learning"), store.changes)
        assertEquals(w.id, c.state.value.deck.current!!.id)
    }

    @Test
    fun `a read that started before mark learned does not bring the word back`() = runTest {
        val store = FakeStore()
        val c = ReviewController(store, backgroundScope, Random(7))
        c.load()
        runCurrent()
        // A sync is under way and its read of the store is in flight when the word is swiped.
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
        assertEquals(listOf(w.id to "learned"), store.changes)
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
    fun `sync keeps the current word in front`() = runTest {
        val (c, store) = setup()
        val current = c.state.value.deck.current!!
        store.server = store.words + (9L to word(9))
        c.load()
        runCurrent()
        assertEquals(current.id, c.state.value.deck.current!!.id)
        assertEquals(4, c.state.value.deck.queue.size)
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
