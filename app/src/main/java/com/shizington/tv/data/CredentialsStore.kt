package com.shizington.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.shizington.tv.data.model.Credentials

/** Credentials live in EncryptedSharedPreferences, with a plain fallback for broken keystores. */
class CredentialsStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "secure_credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w("CredentialsStore", "Encrypted prefs unavailable, falling back", e)
        context.getSharedPreferences("credentials_fallback", Context.MODE_PRIVATE)
    }

    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Credentials? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        val backups = prefs.getString(KEY_BACKUPS, null).orEmpty().split('\n').filter { it.isNotBlank() }
        return Credentials(server, user, pass, backups)
    }

    fun save(c: Credentials) {
        prefs.edit().putString(KEY_SERVER, c.server).putString(KEY_USER, c.username)
            .putString(KEY_PASS, c.password).putString(KEY_BACKUPS, c.backupServers.joinToString("\n")).apply()
        settings.edit().putString(KEY_LAST_ACCOUNT, c.accountKey).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    val lastAccountKey: String? get() = settings.getString(KEY_LAST_ACCOUNT, null)

    /** Live stream container: "ts" (MPEG-TS) or "m3u8" (HLS). */
    var liveFormat: String
        get() = settings.getString(KEY_LIVE_FORMAT, "ts") ?: "ts"
        set(value) = settings.edit().putString(KEY_LIVE_FORMAT, value).apply()

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_BACKUPS = "backup_servers"
        const val KEY_LAST_ACCOUNT = "last_account"
        const val KEY_LIVE_FORMAT = "live_format"
    }
}
