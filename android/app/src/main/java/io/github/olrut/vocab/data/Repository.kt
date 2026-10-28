package io.github.olrut.vocab.data

import androidx.room.withTransaction
import io.github.olrut.vocab.api.StatusPatch
import io.github.olrut.vocab.api.VocabApi
import io.github.olrut.vocab.api.Word
import io.github.olrut.vocab.audio.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the review screen needs from the word store. */
interface WordStore {
    suspend fun learning(): List<Word>

    /** Records a status change locally; [sync] sends it. */
    suspend fun setStatus(id: Long, status: String)

    /** Sends pending changes and fetches the server's. Throws if the server cannot be reached. */
    suspend fun sync()
}

/**
 * The offline store (docs/SPEC.md § 6): Room holds the words and the changes not yet sent.
 * One lock orders local changes and sync, so a change is never half sent or lost between a
 * push and the delete of its pending row.
 */
class Repository(
    private val db: VocabDb,
    private val api: VocabApi,
    private val audio: AudioCache,
    private val background: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) : WordStore {
    private val dao = db.dao()
    private val lock = Mutex()
    private var audioJob: Job? = null

    override suspend fun learning(): List<Word> = lock.withLock { dao.learning().map { it.toWord() } }

    override suspend fun setStatus(id: Long, status: String) = lock.withLock {
        db.withTransaction {
            val ts = ISO.format(now())
            val pending = dao.pendingFor(id)
            when {
                // The server has not seen the change this one reverses (undo before the push).
                pending != null && pending.status != status -> dao.deletePending(pending.id)
                pending == null -> dao.insertPending(PendingChange(wordId = id, status = status, changedAt = ts))
            }
            dao.setStatus(id, status, if (status == "learned") ts else null)
        }
    }

    override suspend fun sync() {
        lock.withLock {
            val changes = api.changes(dao.lastSync() ?: EPOCH)
            val changed = changes.words.associateBy { it.id }
            val pushed = mutableListOf<Word>()
            for (p in dao.pending()) {
                val server = changed[p.wordId]
                // Last updatedAt wins: the word was changed elsewhere after this change was made.
                if (server != null && Instant.parse(server.updatedAt) > Instant.parse(p.changedAt)) {
                    dao.deletePending(p.id)
                    continue
                }
                try {
                    pushed += api.setStatus(p.wordId, StatusPatch(p.status))
                } catch (e: HttpException) {
                    if (e.code() != 404) throw e
                    dao.deleteWord(p.wordId)
                }
                dao.deletePending(p.id)
            }
            db.withTransaction {
                // The PATCH replies are newer than the pull, so they go last.
                for (w in changes.words + pushed) {
                    if (w.deletedAt == null) {
                        dao.upsert(w.toRow())
                    } else {
                        dao.deleteWord(w.id)
                        dao.deletePendingFor(w.id)
                        audio.delete(w.id)
                    }
                }
                dao.setSyncState(SyncState(lastSync = changes.serverTime))
            }
        }
        fetchAudio()
    }

    /** Best effort, in the background: recordings the server has and the phone does not. */
    private fun fetchAudio() {
        if (audioJob?.isActive == true) return
        audioJob = background.launch {
            for (w in dao.withAudio()) if (!audio.has(w.id)) audio.get(w.id)
        }
    }

    /** For tests: waits for the recordings being fetched. */
    internal suspend fun awaitAudio() {
        audioJob?.join()
    }

    private companion object {
        const val EPOCH = "1970-01-01T00:00:00.000Z"

        /** The server's timestamp format: UTC, milliseconds. */
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
