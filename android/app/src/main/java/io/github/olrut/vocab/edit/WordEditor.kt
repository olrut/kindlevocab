package io.github.olrut.vocab.edit

import io.github.olrut.vocab.data.DuplicateWordException
import io.github.olrut.vocab.data.InvalidWordException
import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordDraft
import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.Sense
import io.github.olrut.vocab.dictionary.WordLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The word form; [id] null while adding a new word. */
data class WordForm(
    val id: Long? = null,
    val word: String = "",
    val definition: String? = null,
    val example: String? = null,
    val finnish: String? = null,
    val notes: String? = null,
    val ipa: String? = null,
    val audioUrl: String? = null,
    val source: String = Word.SOURCE_MANUAL,
    val status: String = Word.LEARNING,
    val bookTitle: String? = null,
) {
    fun draft() = WordDraft(word, definition, example, finnish, notes, ipa, audioUrl, source, status)
}

sealed interface LookupState {
    data object Idle : LookupState
    data object Busy : LookupState
    data class Senses(val senses: List<Sense>) : LookupState
    data object NotFound : LookupState
    data class Failed(val message: String) : LookupState
}

data class EditorState(
    val form: WordForm = WordForm(),
    val lookup: LookupState = LookupState.Idle,
    /** While an existing word is being read in. */
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** Saved or deleted: the screen closes. */
    val done: Boolean = false,
)

/**
 * Add and edit (docs/SPEC.md § 6): type a word, look it up in the dictionary, pick a sense (or
 * write your own), adjust, save. Runs on the main thread; the actions that do work return its job.
 */
class WordEditor(private val repo: WordRepository, private val dictionary: WordLookup, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** Bumped when the word text changes, so a lookup for the old text is dropped. */
    private var lookupSeq = 0

    fun edit(id: Long): Job {
        _state.value = EditorState(loading = true)
        return scope.launch {
            val w = repo.get(id)
            _state.value = if (w == null) {
                EditorState(error = "The word was deleted", done = true)
            } else {
                EditorState(
                    WordForm(w.id, w.word, w.definition, w.example, w.finnish, w.notes, w.ipa, w.audioUrl, w.source, w.status, w.bookTitle),
                )
            }
        }
    }

    fun setWord(text: String) {
        val changed = WordRepository.normalizeWord(text) != WordRepository.normalizeWord(_state.value.form.word)
        if (changed) lookupSeq++
        _state.update {
            val form = it.form.copy(word = text)
            if (changed) it.copy(form = form.copy(ipa = null, audioUrl = null), lookup = LookupState.Idle) else it.copy(form = form)
        }
    }

    /** Any field other than the word itself (that goes through [setWord]). */
    fun update(change: (WordForm) -> WordForm) = _state.update { it.copy(form = change(it.form).copy(word = it.form.word), error = null) }

    fun lookUp(): Job? {
        val text = _state.value.form.word.trim()
        if (text.isEmpty()) return null
        val seq = ++lookupSeq
        _state.update { it.copy(lookup = LookupState.Busy, error = null) }
        return scope.launch {
            val result = dictionary.lookup(text)
            if (seq != lookupSeq) return@launch
            _state.update {
                when (result) {
                    is Lookup.Found -> it.copy(form = it.form.copy(ipa = result.ipa, audioUrl = result.audioUrl), lookup = LookupState.Senses(result.senses))
                    Lookup.NotFound -> it.copy(lookup = LookupState.NotFound)
                    is Lookup.Failed -> it.copy(lookup = LookupState.Failed(result.message))
                }
            }
        }
    }

    fun pick(sense: Sense) = _state.update {
        it.copy(
            form = it.form.copy(definition = sense.definition, example = sense.example ?: it.form.example, source = if (it.form.id == null) Word.SOURCE_DICTIONARY else it.form.source),
            lookup = LookupState.Idle,
        )
    }

    fun dismissLookup() = _state.update { it.copy(lookup = LookupState.Idle) }

    fun save(): Job {
        val form = _state.value.form
        _state.update { it.copy(saving = true, error = null) }
        return scope.launch {
            try {
                if (form.id == null) repo.add(form.draft()) else repo.update(form.id, form.draft())
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = message(e)) }
            }
        }
    }

    fun setStatus(status: String): Job? {
        val id = _state.value.form.id
        if (id == null) {
            _state.update { it.copy(form = it.form.copy(status = status)) }
            return null
        }
        return scope.launch {
            repo.setStatus(id, status)
            _state.update { it.copy(form = it.form.copy(status = status)) }
        }
    }

    fun delete(): Job? {
        val id = _state.value.form.id ?: return null
        return scope.launch {
            repo.delete(id)
            _state.update { it.copy(done = true) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun message(e: Exception) = when (e) {
        is DuplicateWordException, is InvalidWordException -> e.message!!
        else -> "Could not save: ${e.message ?: e}"
    }
}
