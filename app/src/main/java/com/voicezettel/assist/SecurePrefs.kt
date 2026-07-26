package com.voicezettel.assist

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted preference store.
 *
 * Holds:
 *  - Gemini API key (secret)
 *  - Zettel vault tree URI (string form)
 *  - Selected trigger combination (enum name)
 *
 * Uses AES256-GCM master key with AES256-SIV key wrapping to avoid IV reuse issues.
 */
class SecurePrefs private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()
        }

    /** Persisted tree URI (string form) granted via `takePersistableUriPermission`. */
    var vaultUriString: String
        get() = prefs.getString(KEY_VAULT_URI, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_VAULT_URI, value).apply()
        }

    /** True if the user has granted persistable SAF access to the vault tree. */
    fun hasVault(): Boolean = vaultUriString.isNotEmpty()

    /** Convenience accessor returning the parsed tree [Uri] or null. */
    fun vaultUri(): Uri? = vaultUriString.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    /** Currently selected trigger combo as enum. */
    var triggerCombo: TriggerCombo
        get() = TriggerCombo.fromName(prefs.getString(KEY_TRIGGER_COMBO, null))
        set(value) {
            prefs.edit().putString(KEY_TRIGGER_COMBO, value.name).apply()
        }

    /** All trigger combos available in the UI dropdown. */
    enum class TriggerCombo(val displayName: String) {
        DOUBLE_PRESS_VOL_DOWN("Double-Press Volume Down (within 400ms)"),
        VOL_DOWN_THEN_VOL_UP("Volume Down → Volume Up (within 600ms)"),
        VOL_UP_THEN_VOL_DOWN("Volume Up → Volume Down (within 600ms)"),
        TRIPLE_PRESS_VOL_DOWN("Triple-Press Volume Down (within 800ms)");

        companion object {
            fun fromName(name: String?): TriggerCombo =
                name?.let { runCatching { valueOf(it) }.getOrNull() } ?: DOUBLE_PRESS_VOL_DOWN
        }
    }

    companion object {
        private const val FILE_NAME = "voice_zettel_secure_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_TRIGGER_COMBO = "trigger_combo"

        @Volatile private var INSTANCE: SecurePrefs? = null

        fun get(context: Context): SecurePrefs =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurePrefs(context).also { INSTANCE = it }
            }
    }
}
