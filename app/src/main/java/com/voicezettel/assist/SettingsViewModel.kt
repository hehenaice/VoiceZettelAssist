package com.voicezettel.assist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin ViewModel exposing the persisted settings as Compose-observable state.
 *
 * Writes are funneled back through [SecurePrefs] so reads stay in sync.
 *
 * The default `viewModel()` factory in `androidx.lifecycle:lifecycle-viewmodel-compose`
 * automatically provides the `Application` to any `AndroidViewModel` subclass —
 * no custom factory needed.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = SecurePrefs.get(app)

    private val _geminiApiKey = MutableStateFlow(prefs.geminiApiKey)
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _vaultUriString = MutableStateFlow(prefs.vaultUriString)
    val vaultUriString: StateFlow<String> = _vaultUriString.asStateFlow()

    private val _triggerCombo = MutableStateFlow(prefs.triggerCombo)
    val triggerCombo: StateFlow<SecurePrefs.TriggerCombo> = _triggerCombo.asStateFlow()

    fun setGeminiApiKey(value: String) {
        prefs.geminiApiKey = value
        _geminiApiKey.value = prefs.geminiApiKey
    }

    fun setVaultUri(value: String) {
        prefs.vaultUriString = value
        _vaultUriString.value = prefs.vaultUriString
    }

    fun setTriggerCombo(value: SecurePrefs.TriggerCombo) {
        prefs.triggerCombo = value
        _triggerCombo.value = prefs.triggerCombo
    }
}
