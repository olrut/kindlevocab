package io.github.olrut.vocab.audio

import android.util.Log
import io.github.olrut.vocab.data.Word
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** The dictionary's recordings, downloaded from [Word.audioUrl] on first use and kept as `<dir>/<id>.mp3`. */
class AudioCache(private val dir: File, private val client: OkHttpClient) {
    fun file(id: Long) = File(dir, "$id.mp3")

    fun has(id: Long) = file(id).exists()

    /**
     * The cached file, downloading it first if needed; null if there is none or that fails.
     * A word not saved yet (id 0) is downloaded afresh each time and not kept.
     */
    suspend fun get(word: Word): File? = withContext(Dispatchers.IO) {
        val preview = word.id <= 0
        val file = if (preview) File(dir, "preview.mp3") else file(word.id)
        if (preview) file.delete() else if (file.exists()) return@withContext file
        val url = word.audioUrl ?: return@withContext null
        try {
            val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val body = res.body!!
                if (body.contentLength() > MAX_BYTES) throw IOException("too large")
                body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("too large")
                    }
                    out.toByteArray()
                }
            }
            dir.mkdirs()
            // Written aside and renamed, so a half-written file is never taken for a recording.
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
            file.takeIf { it.exists() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "audio for ${word.word} failed: $e")
            null
        }
    }

    fun delete(id: Long) {
        file(id).delete()
    }

    private companion object {
        const val TAG = "AudioCache"
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}
