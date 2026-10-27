package io.github.olrut.vocab.review

import io.github.olrut.vocab.api.Word
import kotlin.random.Random

/**
 * Review order (docs/SPEC.md § 6): one shuffled pass over the learning list, then a new shuffle.
 * Immutable; every operation returns a new deck. `queue` holds every word exactly once.
 */
data class Deck(val queue: List<Word>, val pos: Int) {
    val current: Word? get() = queue.getOrNull(pos)

    fun next(random: Random): Deck = if (pos + 1 < queue.size) copy(pos = pos + 1) else reshuffle(queue, current, random)

    /** Drops the current word (marked learned); the following one becomes current. */
    fun removeCurrent(random: Random): Deck {
        val rest = queue.filterIndexed { i, _ -> i != pos }
        return if (pos < rest.size) Deck(rest, pos) else reshuffle(rest, current, random)
    }

    /** Puts a word back as the current one (undo). */
    fun restore(word: Word): Deck {
        val rest = queue.filter { it.id != word.id }
        val at = pos.coerceAtMost(rest.size)
        return Deck(rest.take(at) + word + rest.drop(at), at)
    }

    /** New contents from the server. The current word stays in front if it is still there. */
    fun reload(words: List<Word>, random: Random): Deck {
        val keep = current?.let { c -> words.find { it.id == c.id } } ?: return of(words, random)
        return Deck(listOf(keep) + words.filter { it.id != keep.id }.shuffled(random), 0)
    }

    companion object {
        fun of(words: List<Word>, random: Random) = Deck(words.shuffled(random), 0)

        /** A new pass that does not open with the word just shown, when there is a choice. */
        private fun reshuffle(words: List<Word>, last: Word?, random: Random): Deck {
            val shuffled = words.shuffled(random).toMutableList()
            if (shuffled.size > 1 && shuffled[0].id == last?.id) shuffled.add(shuffled.removeAt(0))
            return Deck(shuffled, 0)
        }
    }
}
