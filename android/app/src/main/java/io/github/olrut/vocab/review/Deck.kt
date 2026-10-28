package io.github.olrut.vocab.review

import io.github.olrut.vocab.data.Word
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

    /**
     * New contents after a sync, without starting the pass over: words keep their place (with
     * their new content), gone ones drop out, new ones go somewhere in the part not yet shown.
     * The current word stays current if it is still there.
     */
    fun reload(words: List<Word>, random: Random): Deck {
        if (queue.isEmpty()) return of(words, random)
        val byId = words.associateBy { it.id }
        val known = queue.mapTo(HashSet()) { it.id }
        val shown = queue.take(pos).mapNotNull { byId[it.id] }
        val ahead = queue.drop(pos).mapNotNull { byId[it.id] }.toMutableList()
        val first = if (ahead.firstOrNull()?.id == current?.id) 1 else 0
        for (w in words.filter { it.id !in known }.shuffled(random)) {
            ahead.add(first + random.nextInt(ahead.size - first + 1), w)
        }
        return if (ahead.isEmpty()) reshuffle(shown, current, random) else Deck(shown + ahead, shown.size)
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
