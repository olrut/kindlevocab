package io.github.olrut.vocab.review

import io.github.olrut.vocab.api.StatusPatch
import io.github.olrut.vocab.api.VocabApi
import io.github.olrut.vocab.api.Word
import io.github.olrut.vocab.api.errorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class ReviewState(
    val deck: Deck = Deck(emptyList(), 0),
    val loading: Boolean = true,
    val revealed: Boolean = false,
    /** The word just marked learned, while its undo is offered. */
    val undo: Word? = null,
    val error: String? = null,
)

/**
 * The review screen's logic, online only (phase 8): reads `GET /words`, and marking learned or
 * undoing patches the server straight away. Phase 9 replaces the patches with the offline queue.
 */
class ReviewController(private val api: VocabApi, private val scope: CoroutineScope, private val random: Random = Random.Default) {
    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    // Status patches go out in order, so an undo can never overtake its "learned".
    private val patches = Mutex()

    fun load() {
        _state.update { it.copy(loading = true) }
        scope.launch {
            try {
                val words = api.words("learning")
                _state.update { it.copy(deck = it.deck.reload(words, random), loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = errorMessage(e)) }
            }
        }
    }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun next() = _state.update { it.copy(deck = it.deck.next(random), revealed = false) }

    fun markLearned() {
        val word = _state.value.deck.current ?: return
        _state.update { it.copy(deck = it.deck.removeCurrent(random), revealed = false, undo = word) }
        scope.launch {
            try {
                patches.withLock { api.setStatus(word.id, StatusPatch("learned")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        deck = it.deck.restore(word),
                        undo = if (it.undo?.id == word.id) null else it.undo,
                        error = "Could not mark “${word.word}” as learned: ${errorMessage(e)}",
                    )
                }
            }
        }
    }

    fun undo() {
        val word = _state.value.undo ?: return
        _state.update { it.copy(deck = it.deck.restore(word), revealed = false, undo = null) }
        scope.launch {
            try {
                patches.withLock { api.setStatus(word.id, StatusPatch("learning")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not undo “${word.word}”: ${errorMessage(e)}") }
            }
        }
    }

    fun dismissUndo() = _state.update { it.copy(undo = null) }

    fun clearError() = _state.update { it.copy(error = null) }
}
