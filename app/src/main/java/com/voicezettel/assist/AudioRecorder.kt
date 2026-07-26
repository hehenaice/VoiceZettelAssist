package com.voicezettel.assist

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] that produces an AAC-encoded audio file
 * suitable for upload to the Gemini 2.0 Flash `inline_data` endpoint.
 *
 * Lifecycle:
 *   start() -> (recording) -> stop() -> outputFile populated
 *   cancel() -> releases recorder & deletes the partial file
 *
 * Designed to be used from a single foreground coroutine — not thread-safe.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L
    val isRecording: Boolean get() = recorder != null

    /**
     * Starts recording into a fresh temp file inside the app's cache dir.
     * Returns the file the recording will be written to (also accessible via [file]).
     */
    fun start(): File {
        check(recorder == null) { "Recorder already running" }

        val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.aac")
        outputFile = file

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(BIT_RATE)
            setAudioSamplingRate(SAMPLE_RATE)
            setOutputFile(file.absolutePath)
            try {
                prepare()
                start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recorder", e)
                releaseSafely()
                outputFile = null
                throw e
            }
        }
        recorder = rec
        startedAt = SystemClock.elapsedRealtime()
        return file
    }

    /**
     * Stops recording and returns the produced audio file, or null on failure.
     */
    fun stop(): File? {
        val rec = recorder ?: return outputFile
        return try {
            // Stop can throw RuntimeException on certain devices if the recorder
            // was started/stopped too quickly (e.g. < 100ms of audio). Treat as
            // failure rather than crash the overlay.
            rec.stop()
            outputFile
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaRecorder.stop() failed", e)
            outputFile?.takeIf { !isValidAac(it) }?.let { broken ->
                broken.delete()
                outputFile = null
            }
            outputFile
        } finally {
            releaseSafely()
        }
    }

    /** Cancels the in-flight recording and deletes the partial file. */
    fun cancel() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // ignore — we're cancelling anyway
        }
        releaseSafely()
        outputFile?.delete()
        outputFile = null
    }

    /** The file the current (or last completed) recording was written to. */
    val file: File? get() = outputFile

    /** Elapsed recording time in ms. */
    fun elapsedMs(): Long =
        if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    private fun releaseSafely() {
        recorder?.run {
            try {
                reset()
                release()
            } catch (e: Exception) {
                Log.w(TAG, "release() failed", e)
            }
        }
        recorder = null
    }

    private fun isValidAac(f: File): Boolean =
        f.exists() && f.length() > MIN_VALID_FILE_BYTES

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 44_100
        private const val BIT_RATE = 96_000
        private const val MIN_VALID_FILE_BYTES = 256L
    }
}
