package com.voicezettel.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voicezettel.assist.ui.theme.VoiceZettelTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Floating overlay activity launched by [VoiceAccessibilityService].
 *
 * Flow:
 *   1. On create: request RECORD_AUDIO runtime permission if needed.
 *   2. Once granted: start [AudioRecorder], show pulsing mic + Stop/Cancel buttons.
 *   3. User taps Stop → stop recorder → run [GeminiTranscriber] → write to vault
 *      via [MarkdownNoteWriter] → toast "Saved to Zettel Notes!" → finish().
 *   4. User taps Cancel → discard audio → finish().
 *   5. Auto-stop after MAX_RECORD_MS as a safety net.
 *
 * The window itself is a transparent floating window sized to wrap content,
 * placed near the top-center of the screen.
 *
 * Recording/transcribing state is held in a [mutableStateOf] property on the
 * activity itself so both the lifecycle-scope coroutine (which mutates it from
 * `handleStop`) and the Compose view (which reads it) share one source of truth.
 */
class VoiceRecordOverlay : ComponentActivity() {

    private val prefs by lazy { SecurePrefs.get(this) }
    private val recorder by lazy { AudioRecorder(this) }
    private val noteWriter by lazy { MarkdownNoteWriter(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var autoStopRunnable: Runnable? = null

    // Single source of truth for the UI phase — read by Compose via the
    // `transcribing` parameter we pass into OverlayRoot. Mutated from
    // handleStop() (which runs on the main thread).
    private var transcribing by mutableStateOf(false)

    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else {
            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureOverlayWindow()

        setContent {
            VoiceZettelTheme {
                OverlayRoot(
                    transcribing = transcribing,
                    recorder = recorder,
                    onStop = { handleStop() },
                    onCancel = { handleCancel() }
                )
            }
        }

        if (hasMicPermission()) beginRecording()
        else recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun configureOverlayWindow() {
        window.setDimAmount(0.45f)
        window.attributes = window.attributes.apply {
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.CENTER
            // Width = 88% of screen so the dialog doesn't span the full width on
            // tablets; height wraps content.
            width = (Resources.getSystem().displayMetrics.widthPixels * 0.88).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun beginRecording() {
        try {
            recorder.start()
            autoStopRunnable = Runnable { handleStop() }
            autoStopRunnable?.let { handler.postDelayed(it, MAX_RECORD_MS) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(
                this,
                "Could not start recording: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun handleStop() {
        if (transcribing) return // already stopping — debounce double-taps
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null

        val audioFile = recorder.stop()
        if (audioFile == null || !audioFile.exists() || audioFile.length() < 256L) {
            Toast.makeText(this, "Recording too short — discarded", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Flip UI into the "Transcribing…" phase.
        transcribing = true

        lifecycleScope.launch {
            val transcriber = GeminiTranscriber(prefs.geminiApiKey)
            val result = transcriber.transcribe(audioFile)

            // Clean up the temp file regardless of outcome.
            audioFile.delete()

            when (result) {
                is GeminiTranscriber.Result.Success -> {
                    val vaultUri: Uri = prefs.vaultUri() ?: run {
                        toastFromMainThread(R.string.toast_no_vault)
                        finishFromMainThread()
                        return@launch
                    }
                    when (val writeResult = noteWriter.appendVoiceNote(
                        vaultUri,
                        result.transcript
                    )) {
                        is MarkdownNoteWriter.Result.Success -> {
                            toastFromMainThread(R.string.toast_saved)
                        }
                        is MarkdownNoteWriter.Result.Failure -> {
                            Log.e(TAG, "Save failed: ${writeResult.reason}")
                            toastFromMainThread(R.string.toast_save_failed)
                        }
                    }
                }
                is GeminiTranscriber.Result.Failure -> {
                    Log.w(TAG, "Transcription failed: ${result.reason}")
                    Toast.makeText(
                        this@VoiceRecordOverlay,
                        getString(R.string.overlay_error_generic) +
                            " (${result.reason})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            finishFromMainThread()
        }
    }

    private fun handleCancel() {
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null
        recorder.cancel()
        finish()
    }

    private fun toastFromMainThread(resId: Int) {
        handler.post { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
    }

    private fun finishFromMainThread() {
        handler.post { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null
        if (recorder.isRecording) recorder.cancel()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ───────────────────────── Compose overlay UI ─────────────────────────

    @Composable
    private fun OverlayRoot(
        transcribing: Boolean,
        recorder: AudioRecorder,
        onStop: () -> Unit,
        onCancel: () -> Unit
    ) {
        var elapsedMs by remember { mutableStateOf(0L) }

        // Poll recorder.elapsedMs() every 100ms while actively recording.
        LaunchedEffect(transcribing) {
            while (!transcribing) {
                elapsedMs = recorder.elapsedMs()
                delay(100L)
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1F1F1F),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = if (transcribing)
                        stringResource(R.string.overlay_transcribing)
                    else
                        stringResource(R.string.overlay_listening),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(16.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    if (transcribing) {
                        CircularProgressIndicator(
                            color = Color(0xFFFF5252),
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(96.dp)
                        )
                    } else {
                        PulsingMic()
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = if (transcribing) "" else formatElapsed(elapsedMs),
                    color = Color(0xFFB0B0B0),
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // Cancel
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onCancel,
                            enabled = !transcribing,
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFF333333), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Cancel,
                                contentDescription = stringResource(R.string.overlay_cancel),
                                tint = Color.White
                            )
                        }
                        Text(
                            text = stringResource(R.string.overlay_cancel),
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Stop
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onStop,
                            enabled = !transcribing,
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFFFF5252), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.overlay_stop),
                                tint = Color.White
                            )
                        }
                        Text(
                            text = stringResource(R.string.overlay_stop),
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PulsingMic() {
        var pulse by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                pulse = !pulse
                delay(500L)
            }
        }
        val scale = if (pulse) 1.15f else 0.9f
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .background(
                    color = Color(0xFFFF5252).copy(alpha = if (pulse) 0.25f else 0.10f),
                    shape = CircleShape
                )
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size((56f * scale).dp)
            )
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    companion object {
        private const val TAG = "VoiceRecordOverlay"
        private const val MAX_RECORD_MS = 60_000L // 1 minute hard cap
    }
}
