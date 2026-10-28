package io.github.olrut.vocab.review

import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ReviewState(
    val deck: Deck = Deck(emptyList(), 0),
    /** Until the words have been read once. */
    val loading: Boolean = true,
    val revealed: Boolean = false,
    /** The word just marked learned, while its undo is offered. */
    val undo: Word? = null,
    val error: String? = null,
)

/**
 * The review screen's logic (docs/SPEC.md § 6): the deck comes from the store, gestures change
 * the store, and the deck is read again on entry, on return to the screen and after each change.
 *
 * Runs on one thread (the main one): [issued] and [applied] count status changes, so a read
 * that started before a change was stored never puts that word back into the deck.
 */
class ReviewController(private val store: WordStore, private val scope: CoroutineScope, private val random: Random = Random.Default) {
    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var issued = 0
    private var applied = 0

    /** On entry and whenever the screen comes back (words may have been added or edited). */
    fun load() {
        scope.launch { reload() }
    }

    /** Reads the store into the deck, unless a change was made that the read may not include. */
    private suspend fun reload() {
        val before = applied
        if (before != issued) return
        val words = try {
            store.learning()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "Could not read your words: ${e.message ?: e}") }
            return
        }
        if (issued == before) _state.update { it.copy(deck = it.deck.reload(words, random), loading = false) }
    }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun next() = _state.update { it.copy(deck = it.deck.next(random), revealed = false) }

    fun markLearned() {
        val word = _state.value.deck.current ?: return
        _state.update { it.copy(deck = it.deck.removeCurrent(random), revealed = false, undo = word) }
        change(word, Word.LEARNED)
    }

    fun undo() {
        val word = _state.value.undo ?: return
        _state.update { it.copy(deck = it.deck.restore(word), revealed = false, undo = null) }
        change(word, Word.LEARNING)
    }

    private fun change(word: Word, status: String) {
        issued++
        scope.launch {
            try {
                store.setStatus(word.id, status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not save “${word.word}”: ${e.message ?: e}") }
            } finally {
                applied++
            }
            reload()
        }
    }

    fun dismissUndo() = _state.update { it.copy(undo = null) }

    fun clearError() = _state.update { it.copy(error = null) }
}
