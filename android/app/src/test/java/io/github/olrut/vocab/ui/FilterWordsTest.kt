package io.github.olrut.vocab.ui

import io.github.olrut.vocab.data.Word
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterWordsTest {
    private val words = listOf(
        Word(id = 1, word = "serendipity", definition = "A happy accident", finnish = "onnekas sattuma"),
        Word(id = 2, word = "run", definition = null),
        Word(id = 3, word = "cat", definition = "A small pet"),
    )

    @Test
    fun `a blank query shows everything`() = assertEquals(words, filterWords(words, "  "))

    @Test
    fun `matches the word, definition or Finnish, ignoring case`() {
        assertEquals(listOf(1L), filterWords(words, "SEREN").map { it.id })
        assertEquals(listOf(2L), filterWords(words, " RUN ").map { it.id })
        assertEquals(listOf(1L, 3L), filterWords(words, "a ").map { it.id }.filter { it != 2L })
        assertEquals(listOf(3L), filterWords(words, "pet").map { it.id })
        assertEquals(listOf(1L), filterWords(words, "sattuma").map { it.id })
        assertEquals(emptyList<Long>(), filterWords(words, "zzz").map { it.id })
    }
}
