package io.github.olrut.vocab

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.olrut.vocab.ui.ReviewScreen
import io.github.olrut.vocab.ui.ServerScreen
import io.github.olrut.vocab.ui.UserPickerScreen
import io.github.olrut.vocab.ui.VocabTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val display by vm.display.display.collectAsStateWithLifecycle()
            val dark = display.dark(isSystemInDarkTheme())
            // System bar icons follow the app's theme, which may differ from the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            VocabTheme(display) {
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
                            onPlay = { word -> scope.launch { vm.speaker.say(word, s.audio) } },
                            onSwitchUser = vm::switchUser,
                            display = display,
                            onTheme = vm.display::setTheme,
                            onSize = vm.display::setSize,
                        )
                    }
                }
            }
        }
    }
}

// The scrims enableEdgeToEdge() uses by default for three-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
