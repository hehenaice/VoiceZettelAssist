package com.voicezettel.assist

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes transcribed voice notes into a daily Markdown file inside the
 * Zettel Notes vault folder (selected by the user via SAF).
 *
 * File name convention:   YYYY-MM-DD.md
 * Append format:
 *
 *   ## [HH:mm] Voice Note
 *   <transcript>
 *
 */
class MarkdownNoteWriter(private val context: Context) {

    sealed class Result {
        data class Success(val fileName: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Appends the transcript as a timestamped Markdown entry into today's daily note.
     *
     * @param vaultUri  Tree URI granted via `takePersistableUriPermission` in the
     *                  settings activity.
     * @param transcript Plain-text transcript returned by Gemini.
     */
    suspend fun appendVoiceNote(
        vaultUri: Uri,
        transcript: String
    ): Result = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) return@withContext Result.Failure("Empty transcript")

        val tree = DocumentFile.fromTreeUri(context, vaultUri)
            ?: return@withContext Result.Failure("Invalid vault URI")

        if (!tree.canWrite()) {
            return@withContext Result.Failure("No write access to vault — re-pick folder in Settings")
        }

        val now = Date()
        val dayName = dayFormat.format(now)
        val timeStr = timeFormat.format(now)
        val daily = tree.findFile(dayName) ?: tree.createFile("text/markdown", dayName)
            ?: return@withContext Result.Failure("Could not create daily note")

        val entry = buildEntry(timeStr, transcript)

        try {
            appendToDocumentFile(daily.uri, entry)
            Result.Success(dayName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append note", e)
            Result.Failure(e.message ?: "Unknown I/O error")
        }
    }

    /** Builds the Markdown block we append to the daily note. */
    private fun buildEntry(timeStr: String, transcript: String): String {
        val clean = transcript.trim().replace("\r\n", "\n")
        return buildString {
            append("\n\n## [").append(timeStr).append("] Voice Note\n")
            append(clean)
            append("\n")
        }
    }

    /**
     * Appends [text] to the file at [uri] by reading the existing content and
     * rewriting it back. DocumentFile doesn't expose an append mode directly,
     * so we read → concatenate → truncate-and-write.
     */
    private fun appendToDocumentFile(uri: Uri, text: String) {
        val existing = readAll(uri)
        val combined = if (existing.isEmpty()) {
            // First entry of the day — prepend a small header so the file isn't bare.
            buildString {
                append("# ").append(dayFormat.format(Date())).append("\n")
                append(text)
            }
        } else {
            existing + text
        }
        writeAll(uri, combined)
    }

    private fun readAll(uri: Uri): String {
        val resolver = context.contentResolver
        return resolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: ""
    }

    private fun writeAll(uri: Uri, content: String) {
        val resolver = context.contentResolver
        // "wt" = truncate, so we overwrite cleanly with the combined content.
        val out: OutputStream = resolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Could not open output stream for $uri")
        out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    companion object {
        private const val TAG = "MarkdownNoteWriter"

        private val dayFormat: SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        private val timeFormat: SimpleDateFormat =
            SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }
}
