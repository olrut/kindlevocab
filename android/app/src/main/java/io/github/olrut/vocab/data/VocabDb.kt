package io.github.olrut.vocab.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A word on the user's list (docs/SPEC.md § 2). The phone holds the only copy. */
@Entity(tableName = "words", indices = [Index("word", unique = true), Index("status")])
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Lowercased, trimmed, unique. */
    val word: String,
    /** Null for a Kindle word the dictionary did not know, until the user writes one. */
    val definition: String? = null,
    val example: String? = null,
    val finnish: String? = null,
    val ipa: String? = null,
    /** The dictionary's recording; downloaded into the audio cache when first played. */
    val audioUrl: String? = null,
    val source: String = SOURCE_MANUAL,
    val status: String = LEARNING,
    val learnedAt: String? = null,
    val bookTitle: String? = null,
    val bookAuthor: String? = null,
    val kindleLookupAt: String? = null,
    val notes: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    companion object {
        const val LEARNING = "learning"
        const val LEARNED = "learned"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DICTIONARY = "dictionary"
        const val SOURCE_CLAUDE = "claude"
        const val SOURCE_IMPORT = "import"
        const val SOURCE_KINDLE = "kindle"
    }
}

/** One finished Kindle import; the newest lookup time in it is where "only new" starts next time. */
@Entity(tableName = "kindle_imports")
data class KindleImport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val importedAt: String,
    /** Kindle's timestamp (ms since the epoch) of the newest lookup taken in. */
    val lastKindleTimestamp: Long,
)

@Dao
interface VocabDao {
    @Query("SELECT * FROM words WHERE status = 'learning'")
    suspend fun learning(): List<Word>

    /** [status] null: every word. Newest first. */
    @Query("SELECT * FROM words WHERE (:status IS NULL OR status = :status) ORDER BY createdAt DESC, id DESC")
    fun observe(status: String?): Flow<List<Word>>

    @Query("SELECT * FROM words ORDER BY createdAt, id")
    suspend fun all(): List<Word>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun get(id: Long): Word?

    @Query("SELECT * FROM words WHERE word = :word")
    suspend fun find(word: String): Word?

    @Query("SELECT word FROM words")
    suspend fun texts(): List<String>

    @Insert
    suspend fun insert(word: Word): Long

    @Update
    suspend fun update(word: Word)

    @Query("UPDATE words SET status = :status, learnedAt = :learnedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, learnedAt: String?, updatedAt: String)

    /** Fills a missing pronunciation; what the word already has stays. */
    @Query("UPDATE words SET ipa = COALESCE(ipa, :ipa), audioUrl = COALESCE(audioUrl, :audioUrl) WHERE id = :id")
    suspend fun fillPronunciation(id: Long, ipa: String?, audioUrl: String?)

    @Query("DELETE FROM words WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertKindleImport(row: KindleImport)

    @Query("SELECT MAX(lastKindleTimestamp) FROM kindle_imports")
    suspend fun lastKindleTimestamp(): Long?
}

@Database(entities = [Word::class, KindleImport::class], version = 1, exportSchema = false)
abstract class VocabDb : RoomDatabase() {
    abstract fun dao(): VocabDao

    companion object {
        fun open(context: Context): VocabDb {
            dropServerCopies(context)
            return Room.databaseBuilder(context.applicationContext, VocabDb::class.java, "vocab.db").build()
        }

        /** The server version's per-account copies (words-<account>.db); the server is gone. */
        private fun dropServerCopies(context: Context) {
            context.databaseList().filter { it.startsWith("words-") && it.endsWith(".db") }.forEach(context::deleteDatabase)
        }
    }
}
