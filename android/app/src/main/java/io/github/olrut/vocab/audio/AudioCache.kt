package io.github.olrut.vocab.audio

import android.util.Log
import io.github.olrut.vocab.api.VocabApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The server's recordings, `GET /audio/:id`, kept as `<dir>/<id>.mp3`. */
class AudioCache(private val dir: File, private val api: VocabApi) {
    fun file(id: Long) = File(dir, "$id.mp3")

    fun has(id: Long) = file(id).exists()

    /** The cached file, downloading it first if needed; null if that fails. */
    suspend fun get(id: Long): File? = withContext(Dispatchers.IO) {
        val file = file(id)
        if (file.exists()) return@withContext file
        try {
            val bytes = api.audio(id).bytes()
            dir.mkdirs()
            // Written aside and renamed, so a half-written file is never taken for a recording.
            val tmp = File(dir, "$id.mp3.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
            file.takeIf { it.exists() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "audio for $id failed: $e")
            null
        }
    }

    fun delete(id: Long) {
        file(id).delete()
    }

    private companion object {
        const val TAG = "AudioCache"
    }
}
