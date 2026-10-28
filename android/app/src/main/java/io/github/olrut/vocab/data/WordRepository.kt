package io.github.olrut.vocab.data

import androidx.room.withTransaction
import io.github.olrut.vocab.audio.AudioCache
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the review screen needs from the word store. */
interface WordStore {
    suspend fun learning(): List<Word>

    suspend fun setStatus(id: Long, status: String)
}

/** The fields the user (or an import) gives for a word; the rest is the store's. */
data class WordDraft(
    val word: String,
    val definition: String? = null,
    val example: String? = null,
    val finnish: String? = null,
    val notes: String? = null,
    val ipa: String? = null,
    val audioUrl: String? = null,
    val source: String = Word.SOURCE_MANUAL,
    val status: String = Word.LEARNING,
    val bookTitle: String? = null,
    val bookAuthor: String? = null,
    val kindleLookupAt: String? = null,
    /** For imports that carry their own; otherwise now. */
    val createdAt: String? = null,
    val learnedAt: String? = null,
)

class DuplicateWordException(val word: String) : Exception("“$word” is already on your list")

class InvalidWordException(message: String) : Exception(message)

/** The words on this phone (docs/SPEC.md § 2): Room, plus the recordings in [audio]. */
class WordRepository(
    private val db: VocabDb,
    private val audio: AudioCache,
    private val now: () -> Instant = Instant::now,
) : WordStore {
    private val dao = db.dao()

    override suspend fun learning(): List<Word> = dao.learning()

    /** [status] null: every word. Updates whenever the words change. */
    fun observe(status: String?): Flow<List<Word>> = dao.observe(status)

    suspend fun get(id: Long): Word? = dao.get(id)

    suspend fun all(): List<Word> = dao.all()

    suspend fun contains(word: String): Boolean = dao.find(normalizeWord(word)) != null

    /** The normalised texts of every word on the list. */
    suspend fun texts(): Set<String> = dao.texts().toSet()

    /** Throws [DuplicateWordException] if the word is on the list, [InvalidWordException] if a field is not valid. */
    suspend fun add(draft: WordDraft): Word {
        val d = validate(draft)
        val at = timestamp()
        return db.withTransaction {
            if (dao.find(d.word) != null) throw DuplicateWordException(d.word)
            val word = Word(
                word = d.word, definition = d.definition, example = d.example, finnish = d.finnish, ipa = d.ipa,
                audioUrl = d.audioUrl, source = d.source, status = d.status,
                learnedAt = if (d.status == Word.LEARNED) d.learnedAt ?: at else null,
                bookTitle = d.bookTitle, bookAuthor = d.bookAuthor, kindleLookupAt = d.kindleLookupAt, notes = d.notes,
                createdAt = d.createdAt ?: at, updatedAt = at,
            )
            word.copy(id = dao.insert(word))
        }
    }

    /**
     * Replaces the word's text fields with the draft's; status, source and book stay. A new word
     * text drops the old pronunciation, which belonged to the old word.
     */
    suspend fun update(id: Long, draft: WordDraft): Word {
        val d = validate(draft)
        val (updated, renamed) = db.withTransaction {
            val old = dao.get(id) ?: throw InvalidWordException("The word was deleted")
            val other = dao.find(d.word)
            if (other != null && other.id != id) throw DuplicateWordException(d.word)
            val renamed = old.word != d.word
            val updated = old.copy(
                word = d.word, definition = d.definition, example = d.example, finnish = d.finnish, notes = d.notes,
                ipa = if (renamed) null else d.ipa, audioUrl = if (renamed) null else d.audioUrl, updatedAt = timestamp(),
            )
            dao.update(updated)
            updated to renamed
        }
        if (renamed) audio.delete(id)
        return updated
    }

    override suspend fun setStatus(id: Long, status: String) {
        val at = timestamp()
        dao.setStatus(id, status, if (status == Word.LEARNED) at else null, at)
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        audio.delete(id)
    }

    private fun timestamp(): String = ISO.format(now())

    companion object {
        const val MAX_WORD = 100
        const val MAX_TEXT = 2000
        const val MAX_NOTES = 10_000

        /** Like the server's: UTC with milliseconds, e.g. 2026-03-01T10:00:00.000Z. */
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun normalizeWord(word: String) = word.trim().lowercase()

        private fun clean(s: String?) = s?.trim()?.ifEmpty { null }

        fun validate(d: WordDraft): WordDraft {
            val word = normalizeWord(d.word)
            if (word.isEmpty()) throw InvalidWordException("The word is empty")
            if (word.length > MAX_WORD) throw InvalidWordException("The word can be at most $MAX_WORD characters")
            val notes = clean(d.notes)
            if (notes != null && notes.length > MAX_NOTES) throw InvalidWordException("Notes can be at most $MAX_NOTES characters")
            val texts = listOf(d.definition, d.example, d.finnish).map(::clean)
            if (texts.any { it != null && it.length > MAX_TEXT }) throw InvalidWordException("A field can be at most $MAX_TEXT characters")
            if (d.status != Word.LEARNING && d.status != Word.LEARNED) throw InvalidWordException("Unknown status ${d.status}")
            return d.copy(word = word, definition = texts[0], example = texts[1], finnish = texts[2], notes = notes, ipa = clean(d.ipa), audioUrl = clean(d.audioUrl))
        }
    }
}
