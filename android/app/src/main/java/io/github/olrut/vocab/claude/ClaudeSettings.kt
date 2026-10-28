package io.github.olrut.vocab.claude

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own Claude API key and model (docs/SPEC.md § 6). In the app the preferences are
 * encrypted with a key in the Android Keystore, and the file is left out of backups (it could not
 * be decrypted on another phone anyway). No key: lookups use the dictionary alone.
 */
class ClaudeSettings(private val prefs: SharedPreferences) {
    private val _config = MutableStateFlow(read())
    val config: StateFlow<ClaudeConfig?> = _config.asStateFlow()

    private fun read(): ClaudeConfig? {
        val key = prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() } ?: return null
        val model = ClaudeModel.entries.firstOrNull { it.name == prefs.getString(MODEL, null) } ?: ClaudeModel.Opus
        return ClaudeConfig(key, model)
    }

    /** Throws [IllegalArgumentException] with a message for the user if [apiKey] cannot be a key. */
    fun save(apiKey: String, model: ClaudeModel) {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "Paste the API key" }
        require(key.none { it.isWhitespace() }) { "An API key has no spaces" }
        require(key.startsWith("sk-ant-")) { "Anthropic API keys start with sk-ant-" }
        prefs.edit { putString(KEY, key).putString(MODEL, model.name) }
        _config.value = ClaudeConfig(key, model)
    }

    /** Changes the model of the saved key. */
    fun setModel(model: ClaudeModel) {
        val current = _config.value ?: return
        prefs.edit { putString(MODEL, model.name) }
        _config.value = current.copy(model = model)
    }

    fun remove() {
        prefs.edit { clear() }
        _config.value = null
    }

    companion object {
        const val FILE = "claude"
        private const val KEY = "apiKey"
        private const val MODEL = "model"

        fun encrypted(context: Context): ClaudeSettings {
            val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                context, FILE, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return ClaudeSettings(prefs)
        }

        /** "sk-ant-…wxyz": enough to recognise the key, never the whole of it. */
        fun masked(key: String) = "sk-ant-…" + key.takeLast(4)
    }
}
