package io.github.olrut.vocab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.olrut.vocab.ui.ReviewScreen
import io.github.olrut.vocab.ui.ServerScreen
import io.github.olrut.vocab.ui.UserPickerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    val screen by vm.screen.collectAsStateWithLifecycle()
                    val scope = rememberCoroutineScope()
                    when (val s = screen) {
                        is Screen.Server -> ServerScreen(s, onConnect = vm::connect)
                        is Screen.Users -> UserPickerScreen(
                            s,
                            onLogin = vm::login,
                            onRetry = { vm.showUsers() },
                            onChangeServer = vm::changeServer,
                        )
                        is Screen.Review -> ReviewScreen(
                            s.controller,
                            s.userName,
                            onPlay = { word -> vm.api?.let { api -> scope.launch { vm.speaker.say(word, api) } } },
                            onSwitchUser = vm::switchUser,
                        )
                    }
                }
            }
        }
    }
}
