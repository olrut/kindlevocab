package io.github.olrut.vocab

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.olrut.vocab.api.LoginRequest
import io.github.olrut.vocab.api.User
import io.github.olrut.vocab.api.VocabApi
import io.github.olrut.vocab.api.apiBaseUrl
import io.github.olrut.vocab.api.createApi
import io.github.olrut.vocab.api.errorMessage
import io.github.olrut.vocab.audio.AudioCache
import io.github.olrut.vocab.audio.Speaker
import io.github.olrut.vocab.data.Repository
import io.github.olrut.vocab.data.VocabDb
import io.github.olrut.vocab.review.ReviewController
import io.github.olrut.vocab.ui.DisplayStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest

sealed interface Screen {
    data class Server(val input: String, val checking: Boolean = false, val error: String? = null) : Screen
    data class Users(val users: List<User>? = null, val error: String? = null, val busy: Boolean = false) : Screen
    data class Review(val userName: String, val controller: ReviewController, val audio: AudioCache) : Screen
}

/** Which screen is showing, and the session behind it. Survives rotation. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val session = Session(app)
    val display = DisplayStore(app.getSharedPreferences("display", Context.MODE_PRIVATE))
    val speaker = Speaker(app)
    private var api: VocabApi? = null

    /** The logged-in user's local store, open while the review screen is. */
    private var db: VocabDb? = null
    private var repository: Repository? = null
    private var accountScope: CoroutineScope? = null

    private val _screen = MutableStateFlow<Screen>(Screen.Server(session.server ?: "https://"))
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        val server = session.server
        val base = server?.let(::apiBaseUrl)
        if (base != null) {
            api = buildApi(base)
            val token = session.token
            val id = session.userId
            val name = session.userName
            if (token != null && id != null && name != null) startReview(id, name) else showUsers()
        }
    }

    private fun buildApi(base: String) = createApi(base, token = { session.token }, onUnauthorized = {
        // OkHttp thread: the session ended on the server (logged out elsewhere, or a new database).
        session.logOut()
        viewModelScope.launch { showUsers(error = "Signed out by the server. Pick your name again.") }
    })

    fun connect(input: String) {
        val base = apiBaseUrl(input)
        if (base == null) {
            _screen.value = Screen.Server(input, error = "Enter the server address, e.g. https://vocab.tail1234.ts.net")
            return
        }
        _screen.value = Screen.Server(input, checking = true)
        val candidate = createApi(base, token = { null }, onUnauthorized = {})
        viewModelScope.launch {
            try {
                val users = candidate.users()
                session.server = input.trim()
                session.logOut()
                api = buildApi(base)
                _screen.value = Screen.Users(users)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _screen.value = Screen.Server(input, error = errorMessage(e))
            }
        }
    }

    fun changeServer() {
        closeAccount()
        session.logOut()
        _screen.value = Screen.Server(session.server ?: "https://")
    }

    fun showUsers(error: String? = null) {
        closeAccount()
        val api = api ?: return changeServer()
        _screen.value = Screen.Users(error = error)
        viewModelScope.launch {
            try {
                val users = api.users()
                _screen.update { if (it is Screen.Users) it.copy(users = users) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _screen.update { if (it is Screen.Users) it.copy(error = errorMessage(e)) else it }
            }
        }
    }

    fun login(user: User, pin: String?) {
        val api = api ?: return
        _screen.update { if (it is Screen.Users) it.copy(busy = true, error = null) else it }
        viewModelScope.launch {
            try {
                val res = api.login(LoginRequest(userId = user.id, pin = pin?.takeIf { user.hasPin }, device = "android:${Build.MODEL}"))
                session.logIn(res.token, res.user.id, res.user.name)
                startReview(res.user.id, res.user.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _screen.update { if (it is Screen.Users) it.copy(busy = false, error = errorMessage(e)) else it }
            }
        }
    }

    /** The review screen syncs itself whenever it comes to the front. */
    private fun startReview(userId: Long, name: String) {
        val app = getApplication<Application>()
        val api = api!!
        // Words, pending changes and recordings are kept per server and user.
        val account = MessageDigest.getInstance("SHA-256")
            .digest("${apiBaseUrl(session.server!!)}|$userId".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val audio = AudioCache(File(app.cacheDir, "audio/$account"), api)
        val db = VocabDb.open(app, account)
        // The store's work stops before its database closes (switching users).
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val repository = Repository(db, api, audio, scope)
        this.db = db
        this.repository = repository
        this.accountScope = scope
        _screen.value = Screen.Review(name, ReviewController(repository, scope), audio)
    }

    private fun closeAccount() {
        accountScope?.cancel()
        db?.close()
        accountScope = null
        db = null
        repository = null
    }

    fun switchUser() {
        val api = api
        val repository = repository
        viewModelScope.launch {
            // Best effort: send what is pending while the token still works. Anything left stays
            // in this user's database for their next login. The token is forgotten either way.
            runCatching { withTimeout(5_000) { repository?.sync() } }
            runCatching { api?.logout() }
            session.logOut()
            showUsers()
        }
    }

    override fun onCleared() {
        closeAccount()
        speaker.release()
    }
}
