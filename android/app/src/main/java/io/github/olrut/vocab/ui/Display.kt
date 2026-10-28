package io.github.olrut.vocab.ui

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode(val label: String) { System("Follow the system"), Light("Light"), Dark("Dark") }

/** How large the review card's text is, relative to the system font size. */
enum class TextSize(val scale: Float, val label: String) {
    Small(0.85f, "Small"),
    Normal(1f, "Normal"),
    Large(1.2f, "Large"),
    ExtraLarge(1.45f, "Extra large"),
}

data class Display(val theme: ThemeMode = ThemeMode.System, val size: TextSize = TextSize.Normal) {
    fun dark(systemDark: Boolean) = when (theme) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** Display choices, per device (plain SharedPreferences: nothing secret). */
class DisplayStore(private val prefs: SharedPreferences) {
    private val _display = MutableStateFlow(
        Display(
            theme = parse(prefs.getString("theme", null), ThemeMode.System),
            size = parse(prefs.getString("size", null), TextSize.Normal),
        ),
    )
    val display: StateFlow<Display> = _display.asStateFlow()

    fun setTheme(theme: ThemeMode) {
        prefs.edit { putString("theme", theme.name) }
        _display.update { it.copy(theme = theme) }
    }

    fun setSize(size: TextSize) {
        prefs.edit { putString("size", size.name) }
        _display.update { it.copy(size = size) }
    }

    private inline fun <reified E : Enum<E>> parse(value: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: default
}
