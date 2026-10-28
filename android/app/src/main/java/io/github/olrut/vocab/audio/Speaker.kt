package io.github.olrut.vocab.audio

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import io.github.olrut.vocab.api.Word
import java.io.File
import java.util.Locale

/**
 * Says a word: the server's recording when it has one (from [AudioCache]),
 * otherwise, or if that fails, Android TextToSpeech in en-US.
 */
class Speaker(context: Context) {
    private var ttsReady = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }
    private var player: MediaPlayer? = null

    suspend fun say(word: Word, cache: AudioCache) {
        val file = if (word.hasAudio) cache.get(word.id) else null
        if (file == null || !play(file)) speak(word.word)
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
