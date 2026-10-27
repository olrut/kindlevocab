package io.github.olrut.vocab.review

import io.github.olrut.vocab.api.LoginRequest
import io.github.olrut.vocab.api.LoginResponse
import io.github.olrut.vocab.api.StatusPatch
import io.github.olrut.vocab.api.User
import io.github.olrut.vocab.api.VocabApi
import io.github.olrut.vocab.api.Word
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private class FakeApi : VocabApi {
    var learning: List<Word> = (1L..3L).map { word(it) }
    var failWords: Exception? = null
    val patches = mutableListOf<Pair<Long, String>>()
    var failPatch: Exception? = null
    /** When set, a PATCH to "learned" waits for it, so a test can hold one in flight. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun users(): List<User> = emptyList()
    override suspend fun login(body: LoginRequest): LoginResponse = error("unused")
    override suspend fun logout() = Unit
    override suspend fun words(status: String): List<Word> {
        failWords?.let { throw it }
        return learning
    }
    override suspend fun setStatus(id: Long, body: StatusPatch): Word {
        if (body.status == "learned") gate?.await()
        failPatch?.let { throw it }
        patches += id to body.status
        return learning.first { it.id == id }.copy(status = body.status)
    }
    override suspend fun audio(id: Long): ResponseBody = error("unused")
}

class ReviewControllerTest {
    private fun TestScope.setup(api: FakeApi = FakeApi()): Pair<ReviewController, FakeApi> {
        val c = ReviewController(api, backgroundScope, Random(7))
        c.load()
        runCurrent()
        return c to api
    }

    @Test
    fun `loads the learning list into a shuffled deck`() = runTest {
        val (c, _) = setup()
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(setOf(1L, 2L, 3L), s.deck.queue.map { it.id }.toSet())
        assertFalse(s.revealed)
    }

    @Test
    fun `shows a load error and keeps what it had`() = runTest {
        val (c, api) = setup()
        val before = c.state.value.deck
        api.failWords = java.net.ConnectException("refused")
        c.load()
        runCurrent()
        assertEquals("Cannot reach the server", c.state.value.error)
        assertEquals(before, c.state.value.deck)
        c.clearError()
        assertNull(c.state.value.error)
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
    fun `mark learned advances at once, patches the server and offers undo`() = runTest {
        val (c, api) = setup()
        val w = c.state.value.deck.current!!
        c.reveal()
        c.markLearned()
        assertFalse(w in c.state.value.deck.queue)
        assertFalse(c.state.value.revealed)
        assertEquals(w, c.state.value.undo)
        runCurrent()
        assertEquals(listOf(w.id to "learned"), api.patches)
    }

    @Test
    fun `undo brings the word back and patches it to learning, after the learned patch`() = runTest {
        val (c, api) = setup()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val w = c.state.value.deck.current!!
        c.markLearned()
        runCurrent()
        c.undo()
        assertEquals(w, c.state.value.deck.current)
        assertNull(c.state.value.undo)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(w.id to "learned", w.id to "learning"), api.patches)
    }

    @Test
    fun `a failed learned patch puts the word back and says so`() = runTest {
        val (c, api) = setup()
        api.failPatch = java.net.ConnectException("refused")
        val w = c.state.value.deck.current!!
        c.markLearned()
        runCurrent()
        assertEquals(w, c.state.value.deck.current)
        assertNull(c.state.value.undo)
        assertEquals("Could not mark “${w.word}” as learned: Cannot reach the server", c.state.value.error)
    }

    @Test
    fun `dismissing the undo offer keeps the word learned`() = runTest {
        val (c, api) = setup()
        c.markLearned()
        c.dismissUndo()
        runCurrent()
        assertNull(c.state.value.undo)
        assertEquals(1, api.patches.size)
    }

    @Test
    fun `sync reloads and keeps the current word in front`() = runTest {
        val (c, api) = setup()
        val current = c.state.value.deck.current!!
        api.learning = api.learning + word(9)
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
    }
}
