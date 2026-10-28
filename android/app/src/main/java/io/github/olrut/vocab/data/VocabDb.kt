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
import androidx.room.Upsert
import io.github.olrut.vocab.api.Word

/** The local copy of the user's live words (learning and learned). Deleted words are removed. */
@Entity(tableName = "words", indices = [Index("status")])
data class WordRow(
    @PrimaryKey val id: Long,
    val word: String,
    val definition: String,
    val example: String?,
    val finnish: String?,
    val ipa: String?,
    val hasAudio: Boolean,
    val status: String,
    val learnedAt: String?,
    val updatedAt: String,
)

/** A status change the server has not seen yet. At most one per word. */
@Entity(tableName = "pending_changes", indices = [Index("wordId")])
data class PendingChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val status: String,
    val changedAt: String,
)

@Entity(tableName = "sync_state")
data class SyncState(@PrimaryKey val id: Int = 0, val lastSync: String)

@Dao
interface VocabDao {
    @Query("SELECT * FROM words WHERE status = 'learning'")
    suspend fun learning(): List<WordRow>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun word(id: Long): WordRow?

    @Query("SELECT * FROM words WHERE hasAudio")
    suspend fun withAudio(): List<WordRow>

    @Upsert
    suspend fun upsert(row: WordRow)

    @Query("DELETE FROM words WHERE id = :id")
    suspend fun deleteWord(id: Long)

    @Query("UPDATE words SET status = :status, learnedAt = :learnedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, learnedAt: String?)

    @Query("SELECT * FROM pending_changes ORDER BY id")
    suspend fun pending(): List<PendingChange>

    @Query("SELECT * FROM pending_changes WHERE wordId = :wordId")
    suspend fun pendingFor(wordId: Long): PendingChange?

    @Insert
    suspend fun insertPending(change: PendingChange)

    @Query("DELETE FROM pending_changes WHERE id = :id")
    suspend fun deletePending(id: Long)

    @Query("DELETE FROM pending_changes WHERE wordId = :wordId")
    suspend fun deletePendingFor(wordId: Long)

    @Query("SELECT lastSync FROM sync_state WHERE id = 0")
    suspend fun lastSync(): String?

    @Upsert
    suspend fun setSyncState(state: SyncState)
}

@Database(entities = [WordRow::class, PendingChange::class, SyncState::class], version = 1, exportSchema = false)
abstract class VocabDb : RoomDatabase() {
    abstract fun dao(): VocabDao

    companion object {
        /** One database per server and user, so switching users never mixes their words. */
        fun open(context: Context, account: String): VocabDb =
            Room.databaseBuilder(context.applicationContext, VocabDb::class.java, "words-$account.db").build()
    }
}

fun WordRow.toWord() = Word(id, word, definition, example, finnish, ipa, hasAudio, status, learnedAt, updatedAt)

fun Word.toRow() = WordRow(id, word, definition, example, finnish, ipa, hasAudio, status, learnedAt, updatedAt)
