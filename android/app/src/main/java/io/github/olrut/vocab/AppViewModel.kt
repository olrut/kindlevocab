package io.github.olrut.vocab

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.olrut.vocab.api.LoginRequest
import io.github.olrut.vocab.api.User
import io.github.olrut.vocab.api.VocabApi
import io.github.olrut.vocab.api.apiBaseUrl
import io.github.olrut.vocab.api.createApi
import io.github.olrut.vocab.api.errorMessage
import io.github.olrut.vocab.audio.Speaker
import io.github.olrut.vocab.review.ReviewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data class Server(val input: String, val checking: Boolean = false, val error: String? = null) : Screen
    data class Users(val users: List<User>? = null, val error: String? = null, val busy: Boolean = false) : Screen
    data class Review(val userName: String, val controller: ReviewController) : Screen
}

/** Which screen is showing, and the session behind it. Survives rotation. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val session = Session(app)
    val speaker = Speaker(app)
    var api: VocabApi? = null
        private set

    private val _screen = MutableStateFlow<Screen>(Screen.Server(session.server ?: "https://"))
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        val server = session.server
        val base = server?.let(::apiBaseUrl)
        if (base != null) {
            api = buildApi(base)
            val token = session.token
            val name = session.userName
            if (token != null && name != null) startReview(name) else showUsers()
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
        session.logOut()
        _screen.value = Screen.Server(session.server ?: "https://")
    }

    fun showUsers(error: String? = null) {
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
                session.logIn(res.token, res.user.name)
                startReview(res.user.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _screen.update { if (it is Screen.Users) it.copy(busy = false, error = errorMessage(e)) else it }
            }
        }
    }

    /** The review screen loads the list itself whenever it comes to the front. */
    private fun startReview(name: String) {
        _screen.value = Screen.Review(name, ReviewController(api!!, viewModelScope))
    }

    fun switchUser() {
        val api = api
        viewModelScope.launch {
            // Best effort: the token is forgotten here either way.
            runCatching { api?.logout() }
            session.logOut()
            showUsers()
        }
    }

    override fun onCleared() {
        speaker.release()
    }
}
