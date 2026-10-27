package io.github.olrut.vocab.audio

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import io.github.olrut.vocab.api.VocabApi
import io.github.olrut.vocab.api.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Says a word: the server's recording when it has one (downloaded once into the cache dir),
 * otherwise, or if that fails, Android TextToSpeech in en-US.
 */
class Speaker(context: Context) {
    private val audioDir = File(context.cacheDir, "audio")
    private var ttsReady = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }
    private var player: MediaPlayer? = null

    suspend fun say(word: Word, api: VocabApi) {
        val file = if (word.hasAudio) recording(word, api) else null
        if (file == null || !play(file)) speak(word.word)
    }

    private suspend fun recording(word: Word, api: VocabApi): File? = withContext(Dispatchers.IO) {
        val file = File(audioDir, "${word.id}.mp3")
        if (file.exists()) return@withContext file
        try {
            val bytes = api.audio(word.id).bytes()
            audioDir.mkdirs()
            val tmp = File(audioDir, "${word.id}.mp3.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            file
        } catch (e: Exception) {
            Log.w(TAG, "audio for ${word.id} failed: $e")
            null
        }
    }

    private fun play(file: File): Boolean = try {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "playing ${file.name} failed: $e")
        file.delete()
        false
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts.language = Locale.US
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    fun release() {
        player?.release()
        player = null
        tts.shutdown()
    }

    private companion object {
        const val TAG = "Speaker"
    }
}
