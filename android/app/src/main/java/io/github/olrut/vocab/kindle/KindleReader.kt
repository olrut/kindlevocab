package io.github.olrut.vocab.kindle

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.olrut.vocab.data.WordRepository
import java.io.File

data class KindleWord(
    /** Normalised stem (fallback: the looked-up form). */
    val word: String,
    /** Sentence from the book, from the newest lookup. */
    val usage: String?,
    val bookTitle: String?,
    val bookAuthor: String?,
    /** Marked "mastered" on the Kindle (category 1). */
    val mastered: Boolean,
    /** WORDS.timestamp (ms since the epoch) of the newest row for this word; drives "only new". */
    val timestamp: Long,
    /** LOOKUPS.timestamp of the newest lookup, if any. */
    val lookupAt: Long?,
)

data class KindleVocab(
    /** One entry per distinct word, newest first. */
    val words: List<KindleWord>,
    /** Max WORDS.timestamp over the English words, null if there are none. */
    val maxTimestamp: Long?,
)

/** The file is not a usable Kindle vocab.db. The message is shown to the user. */
class KindleFileException(message: String) : Exception(message)

/**
 * Reads a Kindle vocab.db (docs/SPEC.md § 3, Kindle import). The file is untrusted: it is opened
 * read-only, column sets are detected per table (they vary between firmware versions), and every
 * value is type-checked.
 */
object KindleReader {
    private const val MAX_USAGE = 2000
    private const val MAX_BOOK_FIELD = 500

    fun read(file: File): KindleVocab {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (e: SQLiteException) {
            throw KindleFileException("This is not a Kindle vocab.db file")
        }
        try {
            // Don't let views or triggers in a crafted file call SQL functions with side effects
            // (ignored by SQLite versions before 3.31, which have no such setting).
            runCatching { db.rawQuery("PRAGMA trusted_schema = OFF", null).use { it.moveToFirst() } }
            return read(db)
        } catch (e: KindleFileException) {
            throw e
        } catch (e: SQLiteException) {
            // "file is not a database" and friends surface on the first query, not on open.
            throw KindleFileException("This is not a Kindle vocab.db file")
        } finally {
            db.close()
        }
    }

    private class Lookup(val usage: String?, val bookKey: String?, val timestamp: Long)

    private class Book(val title: String?, val author: String?)

    private fun read(db: SQLiteDatabase): KindleVocab {
        val wordsCols = columnsOf(db, "WORDS") ?: throw KindleFileException("No WORDS table: this is not a Kindle vocab.db")
        if ("id" !in wordsCols || ("word" !in wordsCols && "stem" !in wordsCols)) throw KindleFileException("The WORDS table has no id/word columns")

        val books = HashMap<String, Book>()
        val bookCols = columnsOf(db, "BOOK_INFO")
        if (bookCols != null && "id" in bookCols) {
            selectAll(db, "BOOK_INFO", bookCols, listOf("id", "title", "authors")) { row ->
                val id = row.key("id") ?: return@selectAll
                books[id] = Book(row.text("title", MAX_BOOK_FIELD), row.text("authors", MAX_BOOK_FIELD))
            }
        }

        val newestLookup = HashMap<String, Lookup>()
        val lookupCols = columnsOf(db, "LOOKUPS")
        if (lookupCols != null && "word_key" in lookupCols) {
            selectAll(db, "LOOKUPS", lookupCols, listOf("word_key", "book_key", "usage", "timestamp")) { row ->
                val key = row.key("word_key") ?: return@selectAll
                val lookup = Lookup(row.text("usage", MAX_USAGE), row.key("book_key"), row.millis("timestamp"))
                val prev = newestLookup[key]
                if (prev == null || lookup.timestamp > prev.timestamp) newestLookup[key] = lookup
            }
        }

        val byWord = LinkedHashMap<String, KindleWord>()
        var maxTimestamp: Long? = null
        selectAll(db, "WORDS", wordsCols, listOf("id", "word", "stem", "lang", "category", "timestamp")) { row ->
            if ("lang" in wordsCols && !isEnglish(row.string("lang"))) return@selectAll
            val word = wordOf(row) ?: return@selectAll
            val timestamp = row.millis("timestamp")
            maxTimestamp = maxOf(maxTimestamp ?: timestamp, timestamp)

            val lookup = row.key("id")?.let { newestLookup[it] }
            val book = lookup?.bookKey?.let { books[it] }
            val prev = byWord[word]
            // Several WORDS rows can share a stem ("ran", "running"): the newest row sets the
            // category, and the newest lookup across all of them gives the sentence.
            val rowIsNewer = prev == null || timestamp > prev.timestamp
            val lookupIsNewer = lookup != null && (prev?.lookupAt == null || lookup.timestamp > prev.lookupAt)
            byWord[word] = KindleWord(
                word = word,
                usage = if (lookupIsNewer) lookup!!.usage else prev?.usage,
                bookTitle = if (lookupIsNewer) book?.title else prev?.bookTitle,
                bookAuthor = if (lookupIsNewer) book?.author else prev?.bookAuthor,
                lookupAt = if (lookupIsNewer) lookup!!.timestamp else prev?.lookupAt,
                mastered = if (rowIsNewer) row.long("category") == 1L else prev!!.mastered,
                timestamp = if (rowIsNewer) timestamp else prev!!.timestamp,
            )
        }

        val words = byWord.values.sortedWith(compareByDescending<KindleWord> { it.timestamp }.thenBy { it.word })
        return KindleVocab(words, maxTimestamp)
    }

    /** Lower-cased column names of [table], or null if the table does not exist. Table names are from a fixed list. */
    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String>? =
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            // A table that does not exist gives no rows (and, on some versions, no columns either).
            val name = c.getColumnIndex("name")
            buildSet { while (name >= 0 && c.moveToNext()) add(c.getString(name).lowercase()) }
        }.ifEmpty { null }

    /** SELECT of the wanted columns that exist; missing ones read as null. Names are from a fixed list. */
    private fun selectAll(db: SQLiteDatabase, table: String, have: Set<String>, want: List<String>, each: (Row) -> Unit) {
        val cols = want.filter { it in have }
        db.rawQuery("SELECT ${cols.joinToString(", ") { "\"$it\"" }} FROM \"$table\"", null).use { c ->
            val row = Row(c, cols)
            while (c.moveToNext()) each(row)
        }
    }

    /** The current row of [c]; every getter checks the stored type. */
    private class Row(private val c: Cursor, cols: List<String>) {
        private val index = cols.withIndex().associate { (i, name) -> name to i }

        private fun type(name: String): Int = index[name]?.let { c.getType(it) } ?: Cursor.FIELD_TYPE_NULL

        fun string(name: String): String? = if (type(name) == Cursor.FIELD_TYPE_STRING) c.getString(index.getValue(name)) else null

        fun long(name: String): Long? = if (type(name) == Cursor.FIELD_TYPE_INTEGER) c.getLong(index.getValue(name)) else null

        /** A key column: text or a number, as text. */
        fun key(name: String): String? = when (type(name)) {
            Cursor.FIELD_TYPE_STRING -> c.getString(index.getValue(name))
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(index.getValue(name)).toString()
            else -> null
        }

        fun text(name: String, max: Int): String? = string(name)?.trim()?.ifEmpty { null }?.take(max)

        fun millis(name: String): Long = when (type(name)) {
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(index.getValue(name))
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index.getValue(name)).takeIf { it.isFinite() }?.toLong() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }

    private fun isEnglish(lang: String?): Boolean {
        val l = lang?.trim()?.lowercase() ?: return false
        return l == "en" || l.startsWith("en-") || l.startsWith("en_")
    }

    private fun wordOf(row: Row): String? {
        for (candidate in listOf(row.string("stem"), row.string("word"))) {
            val w = WordRepository.normalizeWord(candidate ?: continue)
            if (w.isNotEmpty()) return w.takeIf { it.length <= WordRepository.MAX_WORD }
        }
        return null
    }
}
