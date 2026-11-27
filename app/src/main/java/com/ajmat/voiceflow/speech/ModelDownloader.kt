package com.ajmat.voiceflow.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelDownloader {
    fun download(
        context: Context,
        model: ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        var lastFailure: IOException? = null
        for (attempt in 1..3) {
            try {
                return downloadOnce(context, model, onProgress)
            } catch (error: IOException) {
                lastFailure = error
                if (attempt == 3 || error.message?.contains("checksum", ignoreCase = true) == true) throw error
                try {
                    Thread.sleep(attempt * 800L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Model download retry was interrupted.", interrupted)
                }
            }
        }
        throw lastFailure ?: IOException("Model download failed.")

    }

    private fun downloadOnce(
        context: Context,
        model: ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        val directory = File(context.filesDir, "models").apply { mkdirs() }
        val destination = File(directory, model.fileName)
        if (destination.exists() && destination.length() >= model.minimumBytes) {
            return destination
        }

        val temporary = File(directory, model.fileName + ".part")
        temporary.delete()
        val digest = MessageDigest.getInstance("SHA-1")
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AjmatVoiceFlow/1.0")
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Model download failed (HTTP " + status + ")")

            val total = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }

            if (temporary.length() < model.minimumBytes) {
                throw IOException("The downloaded model is incomplete.")
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(model.expectedSha1, ignoreCase = true)) {
                throw IOException("Model checksum did not match. Please retry the download.")
            }

            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not save the model to internal storage.")
            }
            return destination
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }
}
