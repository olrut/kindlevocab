package io.github.olrut.vocab

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Server address and login, kept in EncryptedSharedPreferences (docs/SPEC.md § 6). */
class Session(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** What the user typed, e.g. "vocab.tail1234.ts.net". */
    var server: String?
        get() = prefs.getString("server", null)
        set(value) = prefs.edit { putString("server", value) }

    val token: String? get() = prefs.getString("token", null)
    val userName: String? get() = prefs.getString("userName", null)

    fun logIn(token: String, userName: String) = prefs.edit {
        putString("token", token)
        putString("userName", userName)
    }

    fun logOut() = prefs.edit {
        remove("token")
        remove("userName")
    }
}
