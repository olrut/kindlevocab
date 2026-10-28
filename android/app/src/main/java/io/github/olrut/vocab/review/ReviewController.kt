package io.github.olrut.vocab.review

import io.github.olrut.vocab.api.Word
import io.github.olrut.vocab.api.errorMessage
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
    /** Until the local words have been read once. */
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val revealed: Boolean = false,
    /** The word just marked learned, while its undo is offered. */
    val undo: Word? = null,
    /** Why the last sync failed; null once one succeeds. */
    val notSynced: String? = null,
    val error: String? = null,
)

/**
 * The review screen's logic, offline first (docs/SPEC.md § 6): the deck comes from the local
 * store, gestures change only the store, and a sync after each change (and on entry, on
 * return to the front, and on request) sends them and fetches the server's.
 *
 * Runs on one thread (the main one): [issued] and [applied] count status changes, so a read
 * that started before a change was stored never puts that word back into the deck.
 */
class ReviewController(private val store: WordStore, private val scope: CoroutineScope, private val random: Random = Random.Default) {
    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var issued = 0
    private var applied = 0
    private var running = 0

    /** On entry and whenever the app comes back to the front. A failure only shows as "not synced". */
    fun load() = sync(manual = false)

    /** "Sync now": a failure is also reported. */
    fun syncNow() = sync(manual = true)

    private fun sync(manual: Boolean) {
        running++
        _state.update { it.copy(syncing = true) }
        scope.launch {
            reload()
            try {
                store.sync()
                _state.update { it.copy(notSynced = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = errorMessage(e)
                _state.update { it.copy(notSynced = message, error = if (manual) "Sync failed: $message" else it.error) }
            }
            reload()
            running--
            _state.update { it.copy(syncing = running > 0) }
        }
    }

    /** Reads the store into the deck, unless a change was made that the read may not include. */
    private suspend fun reload() {
        val before = applied
        if (before != issued) return
        val words = store.learning()
        if (issued == before) _state.update { it.copy(deck = it.deck.reload(words, random), loading = false) }
    }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun next() = _state.update { it.copy(deck = it.deck.next(random), revealed = false) }

    fun markLearned() {
        val word = _state.value.deck.current ?: return
        _state.update { it.copy(deck = it.deck.removeCurrent(random), revealed = false, undo = word) }
        change(word, "learned")
    }

    fun undo() {
        val word = _state.value.undo ?: return
        _state.update { it.copy(deck = it.deck.restore(word), revealed = false, undo = null) }
        change(word, "learning")
    }

    private fun change(word: Word, status: String) {
        issued++
        scope.launch {
            try {
                store.setStatus(word.id, status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not save “${word.word}”: ${errorMessage(e)}") }
            } finally {
                applied++
            }
            // The deck is read again at the end of this sync, now that the change is stored.
            sync(manual = false)
        }
    }

    fun dismissUndo() = _state.update { it.copy(undo = null) }

    fun clearError() = _state.update { it.copy(error = null) }
}
