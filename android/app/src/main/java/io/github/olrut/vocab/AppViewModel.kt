package io.github.olrut.vocab

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.olrut.vocab.audio.AudioCache
import io.github.olrut.vocab.audio.Speaker
import io.github.olrut.vocab.data.VocabDb
import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Dictionary
import io.github.olrut.vocab.edit.WordEditor
import io.github.olrut.vocab.review.ReviewController
import io.github.olrut.vocab.ui.DisplayStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

sealed interface Screen {
    data object Review : Screen
    /** [status] null: every word. */
    data class Words(val status: String? = null) : Screen
    data class Editor(val editor: WordEditor) : Screen
}

/** The screens, as a back stack, and the app's single word store. Survives rotation. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val display = DisplayStore(app.getSharedPreferences("display", Context.MODE_PRIVATE))
    val speaker = Speaker(app)
    private val dictionary = Dictionary()
    private val db = VocabDb.open(app)
    val audio = AudioCache(File(app.cacheDir, "recordings"), Dictionary.defaultClient())
    val words = WordRepository(db, audio)
    val review = ReviewController(words, viewModelScope)

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Review))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    init {
        // The server version's recordings, kept per account (cacheDir/audio/<account>/).
        File(app.cacheDir, "audio").deleteRecursively()
    }

    fun showWords() = push(Screen.Words())

    fun filterWords(status: String?) = _stack.update { s -> s.map { if (it is Screen.Words) Screen.Words(status) else it } }

    fun addWord() = push(Screen.Editor(WordEditor(words, dictionary, viewModelScope)))

    fun editWord(id: Long) = push(Screen.Editor(WordEditor(words, dictionary, viewModelScope).also { it.edit(id) }))

    /** False when there is nothing to go back to (the system then closes the app). */
    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        return true
    }

    private fun push(screen: Screen) = _stack.update { it + screen }

    override fun onCleared() {
        speaker.release()
        db.close()
    }
}
