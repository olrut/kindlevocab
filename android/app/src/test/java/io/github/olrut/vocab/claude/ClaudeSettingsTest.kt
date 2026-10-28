package io.github.olrut.vocab.claude

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With plain preferences: Robolectric has no Android Keystore. The app passes encrypted ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClaudeSettingsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("claude-test", Context.MODE_PRIVATE)

    @Test
    fun `no key at first, then saved, kept, and removed`() {
        prefs.edit().clear().commit()
        val s = ClaudeSettings(prefs)
        assertNull(s.config.value)
        s.save("  sk-ant-api03-abcd1234 ", ClaudeModel.Haiku)
        assertEquals(ClaudeConfig("sk-ant-api03-abcd1234", ClaudeModel.Haiku), s.config.value)
        assertEquals(ClaudeConfig("sk-ant-api03-abcd1234", ClaudeModel.Haiku), ClaudeSettings(prefs).config.value)
        s.setModel(ClaudeModel.Opus)
        assertEquals(ClaudeModel.Opus, ClaudeSettings(prefs).config.value!!.model)
        s.remove()
        assertNull(s.config.value)
        assertNull(ClaudeSettings(prefs).config.value)
    }

    @Test
    fun `the model defaults to Opus and changing it needs a key`() {
        prefs.edit().clear().putString("apiKey", "sk-ant-x").commit()
        val s = ClaudeSettings(prefs)
        assertEquals(ClaudeModel.Opus, s.config.value!!.model)
        prefs.edit().clear().commit()
        val empty = ClaudeSettings(prefs)
        empty.setModel(ClaudeModel.Haiku)
        assertNull(empty.config.value)
    }

    @Test
    fun `what cannot be a key is refused with a reason`() {
        prefs.edit().clear().commit()
        val s = ClaudeSettings(prefs)
        for ((key, message) in listOf(" " to "Paste the API key", "sk-ant- abc" to "An API key has no spaces", "abc123" to "Anthropic API keys start with sk-ant-")) {
            try {
                s.save(key, ClaudeModel.Opus)
                fail("expected $key to be refused")
            } catch (e: IllegalArgumentException) {
                assertEquals(message, e.message)
            }
        }
        assertNull(s.config.value)
    }

    @Test
    fun `the key is shown masked`() = assertEquals("sk-ant-…wxyz", ClaudeSettings.masked("sk-ant-api03-secretwxyz"))
}
