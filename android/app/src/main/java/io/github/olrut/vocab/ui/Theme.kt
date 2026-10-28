package io.github.olrut.vocab.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Before Android 12 (no wallpaper colours): schemes around the launcher icon's blue.
private val Light = lightColorScheme(primary = Color(0xFF2E5E8C), onPrimary = Color.White, primaryContainer = Color(0xFFD2E4FF), onPrimaryContainer = Color(0xFF001D36))
private val Dark = darkColorScheme(primary = Color(0xFF9ECAFF), onPrimary = Color(0xFF003258), primaryContainer = Color(0xFF0F4A75), onPrimaryContainer = Color(0xFFD2E4FF))

/** The app's theme: light or dark per [Display], wallpaper colours on Android 12+. */
@Composable
fun VocabTheme(display: Display, content: @Composable () -> Unit) {
    val dark = display.dark(isSystemInDarkTheme())
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
