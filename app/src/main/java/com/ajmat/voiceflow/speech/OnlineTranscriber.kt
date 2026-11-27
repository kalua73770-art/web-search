package com.ajmat.voiceflow.speech

import android.content.Context
import com.ajmat.voiceflow.logging.VoiceFlowLog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object OnlineTranscriber {
    /**
     * POST multipart/form-data: "audio" (WAV) and "language" (auto|hi|en).
     * The endpoint must return JSON {"text":"..."}. Network failures return
     * null after logging a diagnostic; caller then falls back to local Whisper.
     */
    fun transcribe(
        context: Context,
        audioFile: File,
        endpoint: String,
        language: String,
        timeoutMs: Int
    ): String? {
        if (endpoint.isBlank()) return null

        var connection: HttpURLConnection? = null
        val startedAt = System.currentTimeMillis()
        try {
            val url = URL(endpoint)
            require(url.protocol.equals("https", ignoreCase = true)) {
                "Online endpoint must use HTTPS."
            }

            val boundary = "----AjmatVoiceFlow-" + UUID.randomUUID()
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = timeoutMs.coerceIn(5_000, 60_000)
                readTimeout = timeoutMs.coerceIn(5_000, 60_000)
                instanceFollowRedirects = true
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AjmatVoiceFlow/2.0")
            }

            connection.outputStream.use { output ->
                fun write(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                write("--" + boundary + "\r\n")
                write("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                write(RecognitionPolicy.normalizeLanguage(language) + "\r\n")
                write("--" + boundary + "\r\n")
                write("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n")
                write("Content-Type: audio/wav\r\n\r\n")
                audioFile.inputStream().use { it.copyTo(output) }
                write("\r\n--" + boundary + "--\r\n")
                output.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                VoiceFlowLog.warn(
                    context,
                    "Online endpoint host=" + url.host + " returned HTTP " + responseCode + "."
                )
                return null
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = JSONObject(response).optString("text").trim()
            if (text.isEmpty()) {
                VoiceFlowLog.warn(context, "Online endpoint returned HTTP success but an empty text field.")
                return null
            }
            VoiceFlowLog.info(
                context,
                "Online endpoint host=" + url.host + " succeeded in " +
                    (System.currentTimeMillis() - startedAt) + " ms."
            )
            return text
        } catch (error: Exception) {
            VoiceFlowLog.error(
                context,
                "Online recognition failed after " + (System.currentTimeMillis() - startedAt) + " ms.",
                error
            )
            return null
        } finally {
            connection?.disconnect()
        }
    }
}
