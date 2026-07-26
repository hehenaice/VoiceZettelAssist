package com.voicezettel.assist

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

/**
 * Accessibility Service that listens for selected volume-key combinations and
 * launches the floating [VoiceRecordOverlay].
 *
 * Why AccessibilityService and not a normal broadcast / foreground service?
 *   Only an AccessibilityService configured with
 *   `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents=true` can
 *   intercept hardware volume keys before the system applies the volume change.
 *   We return `true` from [onKeyEvent] only when we actually consume the combo,
 *   so the device volume still works normally otherwise.
 */
class VoiceAccessibilityService : AccessibilityService() {

    private val prefs: SecurePrefs by lazy { SecurePrefs.get(this) }

    // Tracking state for combo detection — reset whenever the inter-press gap
    // exceeds the combo's tolerance window.
    private var lastVolDownTime: Long = 0L
    private var lastVolUpTime: Long = 0L
    private var volDownPressCount: Int = 0
    private var volUpPressCount: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // We don't consume any accessibility events — the service only exists
        // to receive key events via onKeyEvent().
    }

    override fun onInterrupt() {
        // No-op — no accessibility features to interrupt.
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val ev = event ?: return false
        if (ev.action != KeyEvent.ACTION_UP) return false

        val now = SystemClock.uptimeMillis()
        val combo = prefs.triggerCombo

        return when (ev.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeDown(now, combo)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeUp(now, combo)
            }
            else -> false
        }
    }

    private fun handleVolumeDown(now: Long, combo: SecurePrefs.TriggerCombo): Boolean {
        // Reset Up-side state whenever a Down press arrives (so two-sided combos
        // stay well-formed).
        volUpPressCount = 0
        lastVolUpTime = 0L

        val gap = now - lastVolDownTime
        if (gap > COMBO_RESET_MS) {
            // Start a fresh combo attempt.
            volDownPressCount = 1
        } else {
            volDownPressCount++
        }
        lastVolDownTime = now

        return when (combo) {
            SecurePrefs.TriggerCombo.DOUBLE_PRESS_VOL_DOWN -> {
                if (volDownPressCount >= 2 && gap <= 400L) {
                    resetState()
                    launchOverlay()
                    true // consume both volume-down presses that formed the combo
                } else false
            }

            SecurePrefs.TriggerCombo.TRIPLE_PRESS_VOL_DOWN -> {
                if (volDownPressCount >= 3 && gap <= 800L) {
                    resetState()
                    launchOverlay()
                    true
                } else false
            }

            SecurePrefs.TriggerCombo.VOL_DOWN_THEN_VOL_UP -> {
                // Just record this volume-down press; the trigger fires when the
                // matching volume-up press arrives within the window.
                false
            }

            SecurePrefs.TriggerCombo.VOL_UP_THEN_VOL_DOWN -> {
                // Trigger: an Up press happened recently, now a Down press arrives.
                val upGap = now - lastVolUpTime
                if (lastVolUpTime > 0 && upGap in 1..600L) {
                    resetState()
                    launchOverlay()
                    true
                } else false
            }
        }
    }

    private fun handleVolumeUp(now: Long, combo: SecurePrefs.TriggerCombo): Boolean {
        // Mirror of handleVolumeDown.
        volDownPressCount = 0
        lastVolDownTime = 0L

        val gap = now - lastVolUpTime
        if (gap > COMBO_RESET_MS) {
            volUpPressCount = 1
        } else {
            volUpPressCount++
        }
        lastVolUpTime = now

        return when (combo) {
            SecurePrefs.TriggerCombo.VOL_DOWN_THEN_VOL_UP -> {
                val downGap = now - lastVolDownTime
                if (lastVolDownTime > 0 && downGap in 1..600L) {
                    resetState()
                    launchOverlay()
                    true
                } else false
            }

            SecurePrefs.TriggerCombo.VOL_UP_THEN_VOL_DOWN,
            SecurePrefs.TriggerCombo.DOUBLE_PRESS_VOL_DOWN,
            SecurePrefs.TriggerCombo.TRIPLE_PRESS_VOL_DOWN -> {
                // Volume Up isn't part of these combos — let it through.
                false
            }
        }
    }

    private fun resetState() {
        lastVolDownTime = 0L
        lastVolUpTime = 0L
        volDownPressCount = 0
        volUpPressCount = 0
    }

    /** Launches the overlay activity that records + transcribes the voice note. */
    private fun launchOverlay() {
        // Pre-flight validation — show a toast instead of opening the overlay if
        // the user hasn't finished setting up the app.
        if (prefs.geminiApiKey.isBlank()) {
            Toast.makeText(this, R.string.toast_no_api_key, Toast.LENGTH_LONG).show()
            return
        }
        if (!prefs.hasVault()) {
            Toast.makeText(this, R.string.toast_no_vault, Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, VoiceRecordOverlay::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch overlay", e)
            Toast.makeText(this, "Could not launch voice overlay", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "VoiceA11yService"
        private const val COMBO_RESET_MS = 1200L
    }
}
