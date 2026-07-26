package com.voicezettel.assist

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transcribes an AAC audio file using the Gemini 2.0 Flash REST API.
 *
 * Endpoint:
 *   https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=API_KEY
 *
 * Payload shape (per project spec):
 *   { "contents": [{ "parts": [ { "text": "<prompt>" },
 *                               { "inline_data": { "mime_type": "audio/aac", "data": "<base64>" } } ] }] }
 *
 * The plain transcript text is parsed out of the response and returned. On any
 * error an exception is thrown — callers should catch and surface a toast.
 */
class GeminiTranscriber(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient
) {

    /** Result wrapper so callers can distinguish hard failures from empty transcripts. */
    sealed class Result {
        data class Success(val transcript: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    suspend fun transcribe(audioFile: File): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Failure("Missing API key")
        if (!audioFile.exists() || audioFile.length() < 256L) {
            return@withContext Result.Failure("Audio file is empty or missing")
        }

        val base64Audio = try {
            Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return@withContext Result.Failure("Failed to read audio: ${e.message}")
        }

        val payload = buildPayload(base64Audio)
        val request = Request.Builder()
            .url("$ENDPOINT_BASE?key=$apiKey")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Gemini HTTP ${resp.code}: $body")
                    val rawMsg = parseError(body) ?: "HTTP ${resp.code}"
                    val friendly = humanizeError(resp.code, rawMsg)
                    return@withContext Result.Failure(friendly)
                }
                val transcript = parseTranscript(body)
                if (transcript.isBlank()) Result.Failure("Empty transcript")
                else Result.Success(transcript.trim())
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during transcription", e)
            Result.Failure("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response", e)
            Result.Failure("Parse error: ${e.message}")
        }
    }

    /**
     * Maps raw Gemini API error messages to short, actionable strings suitable
     * for surfacing in a Toast. The raw messages can be hundreds of characters
     * long (especially the 429 quota errors that list every quota metric) and
     * would otherwise wrap off the screen.
     */
    private fun humanizeError(httpCode: Int, rawMsg: String): String {
        // Truncate to ~140 chars to keep the toast readable.
        val truncated = if (rawMsg.length > 140) rawMsg.take(137) + "..." else rawMsg
        return when (httpCode) {
            400 -> "Bad request: $truncated"
            401 -> "Invalid API key — re-paste it in Settings"
            403 -> "API key lacks permission (check AI Studio)"
            404 -> "Model not found — update the app"
            429 -> "Quota exhausted (free tier limit reached). Wait 24h or enable billing in Google AI Studio"
            500, 502, 503 -> "Gemini server error — retry shortly"
            else -> "HTTP $httpCode: $truncated"
        }
    }

    private fun buildPayload(base64Audio: String): String {
        // Construct manually with org.json so we don't add a JSON library dependency.
        val parts = JSONArray().apply {
            put(JSONObject().put("text", PROMPT))
            put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", MIME_TYPE)
                        .put("data", base64Audio)
                )
            )
        }
        val contents = JSONArray().put(JSONObject().put("parts", parts))
        val root = JSONObject()
            .put("contents", contents)
            // Conservative generation config — we want a faithful plain-text transcript.
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("topP", 0.95)
                    .put("maxOutputTokens", 2048)
            )
        return root.toString()
    }

    /** Extracts the first candidate's text part from the response. */
    private fun parseTranscript(body: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            parts.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
        }
        return sb.toString()
    }

    private fun parseError(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "GeminiTranscriber"

        // The Gemini 1.5 Flash model was deprecated and removed from the v1beta
        // API in mid-2025. gemini-2.0-flash is the drop-in replacement: it
        // supports the same `inline_data` audio input, is available on the
        // free tier, and accepts identical generateContent payloads.
        private const val ENDPOINT_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

        private const val PROMPT =
            "Transcribe the following audio accurately. Output only the plain transcript text with no extra commentary or quotes."
        private const val MIME_TYPE = "audio/aac"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Default OkHttp instance with sensible timeouts for an upload-heavy request. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)   // audio upload
                .readTimeout(60, TimeUnit.SECONDS)    // model latency
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
