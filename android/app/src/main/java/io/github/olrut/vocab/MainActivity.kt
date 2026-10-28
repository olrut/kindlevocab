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
import androidx.activity.compose.BackHandler
import io.github.olrut.vocab.ui.EditScreen
import io.github.olrut.vocab.ui.ImportScreen
import io.github.olrut.vocab.ui.ReviewScreen
import io.github.olrut.vocab.ui.VocabTheme
import io.github.olrut.vocab.ui.WordsScreen
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
                    val stack by vm.stack.collectAsStateWithLifecycle()
                    val scope = rememberCoroutineScope()
                    BackHandler(enabled = stack.size > 1) { vm.back() }
                    when (val s = stack.last()) {
                        Screen.Review -> ReviewScreen(
                            vm.review,
                            onPlay = { word -> scope.launch { vm.speaker.say(word, vm.audio) } },
                            onAdd = vm::addWord,
                            onWords = vm::showWords,
                            onImport = vm::showImport,
                            display = display,
                            onTheme = vm.display::setTheme,
                            onSize = vm.display::setSize,
                        )
                        is Screen.Words -> WordsScreen(
                            vm.words,
                            s.status,
                            onFilter = vm::filterWords,
                            onOpen = vm::editWord,
                            onAdd = vm::addWord,
                            onBack = { vm.back() },
                        )
                        Screen.Import -> ImportScreen(
                            vm.imports,
                            onKindle = vm::openKindle,
                            onFile = vm::importFile,
                            onExport = vm::export,
                            onBack = { vm.back() },
                        )
                        is Screen.Editor -> EditScreen(
                            s.editor,
                            onPlay = { word -> scope.launch { vm.speaker.say(word, vm.audio) } },
                            onClose = { vm.back() },
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
