package io.github.olrut.vocab.transfer

import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.WordLookup
import io.github.olrut.vocab.kindle.ImportProgress
import io.github.olrut.vocab.kindle.KindleCandidate
import io.github.olrut.vocab.kindle.KindleFileException
import io.github.olrut.vocab.kindle.KindleImporter
import io.github.olrut.vocab.kindle.KindleVocab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportState {
    /** [message]: the outcome of the last action, if any. */
    data class Idle(val message: String? = null, val error: Boolean = false) : ImportState
    data object Busy : ImportState
    data class Kindle(
        val vocab: KindleVocab,
        val onlyNew: Boolean,
        val candidates: List<KindleCandidate>,
        val selected: Set<String>,
        val markMastered: Boolean = true,
    ) : ImportState
    data class Running(val progress: ImportProgress) : ImportState
    data class Finished(val progress: ImportProgress) : ImportState
}

/**
 * The Import / export screen's logic (docs/SPEC.md § 6): a Kindle vocab.db goes preview → pick →
 * import; a CSV/JSON file is checked whole, then imported. Runs on the main thread; getting the
 * files' contents is the caller's (AppViewModel).
 */
class ImportController(
    private val repo: WordRepository,
    private val dictionary: WordLookup,
    private val scope: CoroutineScope,
    private val kindle: KindleImporter = KindleImporter(repo, dictionary),
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun busy() {
        _state.value = ImportState.Busy
    }

    fun fail(message: String) {
        _state.value = ImportState.Idle(message, error = true)
    }

    /** A read Kindle file, or the reason it could not be read. */
    suspend fun showKindle(read: suspend () -> KindleVocab) {
        val vocab = try {
            read()
        } catch (e: KindleFileException) {
            return fail(e.message!!)
        }
        showCandidates(vocab, onlyNew = repo.lastKindleTimestamp() != null)
    }

    private suspend fun showCandidates(vocab: KindleVocab, onlyNew: Boolean, markMastered: Boolean = true) {
        val candidates = kindle.candidates(vocab, onlyNew)
        // New words are picked by default; those already on the list are shown, unpicked.
        _state.value = ImportState.Kindle(vocab, onlyNew, candidates, candidates.filter { !it.exists }.mapTo(HashSet()) { it.word.word }, markMastered)
    }

    fun setOnlyNew(onlyNew: Boolean): Job? {
        val s = _state.value as? ImportState.Kindle ?: return null
        return scope.launch { showCandidates(s.vocab, onlyNew, s.markMastered) }
    }

    fun toggle(word: String) = _state.update { s ->
        if (s !is ImportState.Kindle) s else s.copy(selected = if (word in s.selected) s.selected - word else s.selected + word)
    }

    fun selectAll(all: Boolean) = _state.update { s ->
        if (s !is ImportState.Kindle) s else s.copy(selected = if (all) s.candidates.mapTo(HashSet()) { it.word.word } else emptySet())
    }

    fun setMarkMastered(mark: Boolean) = _state.update { s -> if (s is ImportState.Kindle) s.copy(markMastered = mark) else s }

    fun startKindle(): Job? {
        val s = _state.value as? ImportState.Kindle ?: return null
        // In list order, so the newest words come in first.
        val words = s.candidates.map { it.word.word }.filter { it in s.selected }
        _state.value = ImportState.Running(ImportProgress(total = words.size))
        return scope.launch {
            val done = try {
                kindle.import(s.vocab, words, s.markMastered) { p -> _state.value = ImportState.Running(p) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch fail("The import stopped: ${e.message ?: e}")
            }
            _state.value = ImportState.Finished(done)
        }
    }

    /** A CSV/JSON file's text: all of it is imported, or nothing. Then fills in missing pronunciations. */
    suspend fun importFile(content: String, format: FileFormat) {
        val result = try {
            repo.importAll(Transfer.parse(content, format))
        } catch (e: ImportFileException) {
            return fail(e.message!!)
        }
        val skipped = if (result.skipped > 0) ", skipped ${result.skipped} already on your list" else ""
        _state.value = ImportState.Idle("Imported ${words(result.added.size)}$skipped.")
        val missing = result.added.filter { it.ipa == null || it.audioUrl == null }
        if (missing.isNotEmpty()) scope.launch { fillPronunciations(missing.map { it.id to it.word }) }
    }

    /** Best effort, in the background: an imported word gets the dictionary's IPA and recording. */
    private suspend fun fillPronunciations(words: List<Pair<Long, String>>) {
        val queue = Channel<Pair<Long, String>>(Channel.UNLIMITED)
        words.forEach { queue.trySend(it) }
        queue.close()
        coroutineScope {
            repeat(2) {
                launch {
                    for ((id, word) in queue) {
                        val r = dictionary.lookup(word)
                        if (r is Lookup.Found) repo.fillPronunciation(id, r.ipa, r.audioUrl)
                    }
                }
            }
        }
    }

    suspend fun export(format: FileFormat): String = Transfer.export(repo.all(), format)

    fun exported(count: Int) {
        _state.value = ImportState.Idle("Exported ${words(count)}.")
    }

    private fun words(n: Int) = if (n == 1) "1 word" else "$n words"

    fun close() {
        _state.value = ImportState.Idle()
    }
}
