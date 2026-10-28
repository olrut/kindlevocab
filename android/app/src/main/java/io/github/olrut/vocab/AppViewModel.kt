package io.github.olrut.vocab

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.olrut.vocab.audio.AudioCache
import io.github.olrut.vocab.audio.Speaker
import io.github.olrut.vocab.data.VocabDb
import io.github.olrut.vocab.data.WordRepository
import io.github.olrut.vocab.dictionary.Dictionary
import io.github.olrut.vocab.edit.WordEditor
import io.github.olrut.vocab.kindle.KindleReader
import io.github.olrut.vocab.transfer.FileFormat
import io.github.olrut.vocab.transfer.ImportController
import io.github.olrut.vocab.review.ReviewController
import io.github.olrut.vocab.ui.DisplayStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

sealed interface Screen {
    data object Review : Screen
    /** [status] null: every word. */
    data class Words(val status: String? = null) : Screen
    data class Editor(val editor: WordEditor) : Screen
    data object Import : Screen
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
    val imports = ImportController(words, dictionary, viewModelScope)

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

    fun showImport() = push(Screen.Import)

    /** A Kindle vocab.db the user picked: copied into the cache (SQLite needs a file), then read. */
    fun openKindle(uri: Uri) = withFile("Could not read the file") {
        val file = File(getApplication<Application>().cacheDir, "kindle/vocab.db")
        withContext(Dispatchers.IO) {
            file.parentFile!!.mkdirs()
            read(uri, MAX_KINDLE_BYTES) { input -> file.outputStream().use { input.copyTo(it) } }
        }
        imports.showKindle { withContext(Dispatchers.IO) { KindleReader.read(file) } }
    }

    fun importFile(uri: Uri) = withFile("Could not read the file") {
        val format = FileFormat.of(displayName(uri) ?: "")
            ?: return@withFile imports.fail("Pick a .csv or .json file (an export of this app)")
        val text = withContext(Dispatchers.IO) { read(uri, MAX_TRANSFER_BYTES) { it.readBytes().toString(Charsets.UTF_8) } }
        imports.importFile(text, format)
    }

    fun export(uri: Uri, format: FileFormat) = withFile("Could not write the file") {
        val text = imports.export(format)
        withContext(Dispatchers.IO) {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output stream")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        imports.exported(words.all().size)
    }

    private fun withFile(failure: String, work: suspend () -> Unit) {
        imports.busy()
        viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                imports.fail("$failure: ${e.message ?: e}")
            }
        }
    }

    /** Reads [uri], refusing more than [max] bytes. */
    private fun <T> read(uri: Uri, max: Long, use: (InputStream) -> T): T {
        val resolver = getApplication<Application>().contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        if (size != null && size > max) throw IOException("the file is larger than ${max / 1_000_000} MB")
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open it")
        return input.use(use)
    }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** False when there is nothing to go back to (the system then closes the app). */
    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        return true
    }

    private fun push(screen: Screen) = _stack.update { it + screen }

    private companion object {
        const val MAX_KINDLE_BYTES = 200_000_000L
        const val MAX_TRANSFER_BYTES = 10_000_000L
    }

    override fun onCleared() {
        speaker.release()
        db.close()
    }
}
