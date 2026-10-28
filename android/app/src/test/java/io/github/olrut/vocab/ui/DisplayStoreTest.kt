package io.github.olrut.vocab.ui

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplayStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("display-test", Context.MODE_PRIVATE)

    @Test
    fun `defaults to the system theme and normal size`() {
        assertEquals(Display(ThemeMode.System, TextSize.Normal), DisplayStore(prefs).display.value)
    }

    @Test
    fun `changes show at once and are kept for the next start`() {
        val store = DisplayStore(prefs)
        store.setTheme(ThemeMode.Dark)
        store.setSize(TextSize.Large)
        assertEquals(Display(ThemeMode.Dark, TextSize.Large), store.display.value)
        assertEquals(Display(ThemeMode.Dark, TextSize.Large), DisplayStore(prefs).display.value)
    }

    @Test
    fun `unknown stored values fall back to the defaults`() {
        prefs.edit {
            putString("theme", "Sepia")
            putString("size", "Gigantic")
        }
        assertEquals(Display(), DisplayStore(prefs).display.value)
    }

    @Test
    fun `dark follows the system unless chosen`() {
        assertTrue(Display(ThemeMode.System).dark(systemDark = true))
        assertFalse(Display(ThemeMode.System).dark(systemDark = false))
        assertTrue(Display(ThemeMode.Dark).dark(systemDark = false))
        assertFalse(Display(ThemeMode.Light).dark(systemDark = true))
    }

    @Test
    fun `sizes grow from small to extra large around normal`() {
        val scales = TextSize.entries.map { it.scale }
        assertEquals(scales.sorted(), scales)
        assertEquals(1f, TextSize.Normal.scale)
    }
}
