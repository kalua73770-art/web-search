package com.ajmat.voiceflow.speech

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object OnlineTranscriber {
    /**
     * POST multipart/form-data: "audio" (WAV) and "language" (auto|hi|en).
     * The endpoint must return JSON {"text":"..."}. Errors return null so
     * the caller can fall back to local Whisper.
     */
    fun transcribe(
        audioFile: File,
        endpoint: String,
        language: String,
        timeoutMs: Int
    ): String? {
        if (endpoint.isBlank()) return null
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Online endpoint must use HTTPS."
        }

        val boundary = "----AjmatVoiceFlow-" + UUID.randomUUID()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs.coerceIn(5_000, 60_000)
            readTimeout = timeoutMs.coerceIn(5_000, 60_000)
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AjmatVoiceFlow/1.0")
        }

        return try {
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
            if (responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = JSONObject(response).optString("text").trim()
            text.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
