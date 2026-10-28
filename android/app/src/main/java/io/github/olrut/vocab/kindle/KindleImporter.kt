package io.github.olrut.vocab.kindle

import io.github.olrut.vocab.data.DuplicateWordException
import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordDraft
import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Lookup
import io.github.olrut.vocab.dictionary.WordLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

data class KindleCandidate(val word: KindleWord, val exists: Boolean)

data class ImportError(val word: String, val error: String)

data class ImportProgress(
    val done: Int = 0,
    val total: Int = 0,
    val imported: Int = 0,
    val skipped: Int = 0,
    val errors: List<ImportError> = emptyList(),
    val finished: Boolean = false,
)

/**
 * Kindle import (docs/SPEC.md § 3, Kindle import): preview the words in a vocab.db, then import
 * the chosen ones, each with a lookup given the sentence from the book, which is the example.
 */
class KindleImporter(
    private val repo: WordRepository,
    private val dictionary: WordLookup,
    private val concurrency: Int = MAX_CONCURRENT_LOOKUPS,
) {
    /** The words to offer; [onlyNew] leaves out those an earlier import already saw. */
    suspend fun candidates(vocab: KindleVocab, onlyNew: Boolean): List<KindleCandidate> {
        val since = if (onlyNew) repo.lastKindleTimestamp() else null
        val existing = repo.texts()
        return vocab.words.filter { since == null || it.timestamp > since }.map { KindleCandidate(it, it.word in existing) }
    }

    suspend fun import(
        vocab: KindleVocab,
        selected: Collection<String>,
        markMastered: Boolean,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportProgress {
        val byWord = vocab.words.associateBy { it.word }
        val unique = selected.map(WordRepository::normalizeWord).distinct()
        val lock = Mutex()
        var progress = ImportProgress(total = unique.size)
        // Kindle timestamps of words that failed for a reason that may pass (no network): they must
        // stay "new" for the next import, so the cutoff cannot move past them.
        val failedAt = mutableListOf<Long>()
        suspend fun report(change: (ImportProgress) -> ImportProgress) = lock.withLock {
            progress = change(progress)
            onProgress(progress)
        }
        onProgress(progress)

        suspend fun importOne(text: String) {
            val kw = byWord[text] ?: return report { it.copy(errors = it.errors + ImportError(text, "Not in the file")) }
            val existing = repo.find(text)
            if (existing != null) {
                if (markMastered && kw.mastered && existing.status == Word.LEARNING) repo.setStatus(existing.id, Word.LEARNED)
                return report { it.copy(skipped = it.skipped + 1) }
            }
            val found = when (val r = dictionary.lookup(text, kw.usage)) {
                is Lookup.Found -> r
                Lookup.NotFound -> null
                is Lookup.Failed -> {
                    lock.withLock { failedAt += kw.timestamp }
                    return report { it.copy(errors = it.errors + ImportError(text, r.message)) }
                }
            }
            val sense = found?.senses?.firstOrNull()
            try {
                repo.add(
                    WordDraft(
                        word = text,
                        definition = sense?.definition,
                        example = kw.usage ?: sense?.example,
                        finnish = sense?.finnish,
                        ipa = found?.ipa,
                        audioUrl = found?.audioUrl,
                        source = Word.SOURCE_KINDLE,
                        status = if (markMastered && kw.mastered) Word.LEARNED else Word.LEARNING,
                        bookTitle = kw.bookTitle,
                        bookAuthor = kw.bookAuthor,
                        kindleLookupAt = kw.lookupAt?.let { WordRepository.ISO.format(Instant.ofEpochMilli(it)) },
                    ),
                )
                report { it.copy(imported = it.imported + 1) }
            } catch (e: DuplicateWordException) {
                // Added by hand while the import ran: that one wins, like any existing word.
                report { it.copy(skipped = it.skipped + 1) }
            }
        }

        val queue = Channel<String>(Channel.UNLIMITED)
        unique.forEach { queue.trySend(it) }
        queue.close()
        coroutineScope {
            repeat(concurrency) {
                launch {
                    for (text in queue) {
                        try {
                            importOne(text)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            byWord[text]?.let { kw -> lock.withLock { failedAt += kw.timestamp } }
                            report { it.copy(errors = it.errors + ImportError(text, e.message ?: e.toString())) }
                        }
                        report { it.copy(done = it.done + 1) }
                    }
                }
            }
        }

        // Everything in this file was seen in the preview, selected or not, except words that
        // failed. The cutoff is the max over all imports, so an older file cannot move it back.
        val cutoff = vocab.maxTimestamp?.let { max -> failedAt.minOrNull()?.let { minOf(max, it - 1) } ?: max }
        if (cutoff != null) repo.recordKindleImport(cutoff)
        report { it.copy(finished = true) }
        return progress
    }

    companion object {
        // The free dictionary answers 429 when asked too often.
        const val MAX_CONCURRENT_LOOKUPS = 2
    }
}
