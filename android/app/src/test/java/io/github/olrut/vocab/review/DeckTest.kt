package io.github.olrut.vocab.review

import io.github.olrut.vocab.api.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

fun word(id: Long, text: String = "w$id") = Word(id = id, word = text, definition = "def of $text")

class DeckTest {
    private val words = (1L..5L).map { word(it) }

    /** The ids of one full pass, starting at the current word. */
    private fun Deck.pass(): List<Long> {
        val seen = mutableListOf<Long>()
        var d = this
        repeat(queue.size) {
            seen += d.current!!.id
            d = d.next(Random(0))
        }
        return seen
    }

    @Test
    fun `a new deck is a shuffle of every word`() {
        val pass = Deck.of(words, Random(1)).pass()
        assertEquals(5, pass.size)
        assertEquals(words.map { it.id }.toSet(), pass.toSet())
    }

    @Test
    fun `the shuffle depends on the random source`() {
        val orders = (1..5).map { Deck.of(words, Random(it)).queue.map(Word::id) }.toSet()
        assertTrue("every seed gave the same order", orders.size > 1)
    }

    @Test
    fun `an empty deck has no current word and stays empty`() {
        val deck = Deck.of(emptyList(), Random(0))
        assertNull(deck.current)
        assertNull(deck.next(Random(0)).current)
    }

    @Test
    fun `after the last word it reshuffles and starts over, not repeating the word just shown`() {
        var deck = Deck.of(words, Random(3))
        repeat(4) { deck = deck.next(Random(0)) }
        val last = deck.current!!.id
        for (seed in 0..20) {
            val again = deck.next(Random(seed))
            assertEquals(0, again.pos)
            assertEquals(5, again.queue.size)
            assertNotEquals(last, again.current!!.id)
        }
    }

    @Test
    fun `a single word repeats`() {
        val deck = Deck.of(listOf(word(1)), Random(0))
        assertEquals(1L, deck.next(Random(0)).current!!.id)
    }

    @Test
    fun `removing the current word shows the next one`() {
        val deck = Deck.of(words, Random(2))
        val after = deck.removeCurrent(Random(0))
        assertEquals(deck.queue[1], after.current)
        assertEquals(4, after.queue.size)
        assertFalse(deck.current in after.queue)
    }

    @Test
    fun `removing the last word of a pass reshuffles the rest`() {
        var deck = Deck.of(words, Random(2))
        repeat(4) { deck = deck.next(Random(0)) }
        val removed = deck.current!!
        val after = deck.removeCurrent(Random(0))
        assertEquals(0, after.pos)
        assertEquals(words.toSet() - removed, after.queue.toSet())
    }

    @Test
    fun `removing the only word empties the deck`() {
        assertNull(Deck.of(listOf(word(1)), Random(0)).removeCurrent(Random(0)).current)
    }

    @Test
    fun `restore puts the word back as the current one`() {
        val deck = Deck.of(words, Random(2))
        val removed = deck.current!!
        val restored = deck.removeCurrent(Random(0)).restore(removed)
        assertEquals(removed, restored.current)
        assertEquals(words.toSet(), restored.queue.toSet())
    }

    @Test
    fun `restore into an empty deck`() {
        val empty = Deck.of(listOf(word(1)), Random(0)).removeCurrent(Random(0))
        assertEquals(1L, empty.restore(word(1)).current!!.id)
    }

    @Test
    fun `restore of a word already in the deck does not duplicate it`() {
        val deck = Deck.of(words, Random(2))
        val restored = deck.restore(deck.queue[3])
        assertEquals(deck.queue[3], restored.current)
        assertEquals(5, restored.queue.size)
    }

    @Test
    fun `reload keeps the current word in front, with its new content, and drops words that are gone`() {
        val deck = Deck.of(words, Random(4))
        val current = deck.current!!
        val gone = deck.queue[1].id
        val fresh = words.filter { it.id != gone }.map { if (it.id == current.id) it.copy(definition = "edited") else it }
        val reloaded = deck.reload(fresh, Random(0))
        assertEquals(current.id, reloaded.current!!.id)
        assertEquals("edited", reloaded.current!!.definition)
        assertEquals(fresh.map { it.id }.toSet(), reloaded.queue.map { it.id }.toSet())
    }

    @Test
    fun `reload without the current word starts a new shuffle`() {
        val deck = Deck.of(words, Random(4))
        val reloaded = deck.reload(words.filter { it.id != deck.current!!.id }, Random(0))
        assertEquals(0, reloaded.pos)
        assertEquals(4, reloaded.queue.size)
    }
}
