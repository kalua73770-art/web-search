package com.ajmat.voiceflow.speech

import android.content.Context
import com.ajmat.voiceflow.logging.VoiceFlowLog
import java.io.File

data class TranscriptionResult(
    val text: String,
    val usedOffline: Boolean,
    val elapsedMs: Long
)

object TranscriptionPipeline {
    fun transcribe(
        context: Context,
        audioFile: File,
        language: String,
        onStatus: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {}
    ): TranscriptionResult {
        val startedAt = System.currentTimeMillis()
        val preferences = context.getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
        val endpoint = preferences.getString("endpoint", "").orEmpty().trim()
        val timeout = preferences.getInt("timeout_ms", 20_000)

        VoiceFlowLog.info(
            context,
            "Transcription started; audioBytes=" + audioFile.length() +
                ", language=" + language +
                ", onlineConfigured=" + endpoint.isNotEmpty()
        )

        try {
            if (RecognitionPolicy.routeFor(endpoint) == RecognitionRoute.ONLINE_THEN_OFFLINE) {
                onStatus("Trying online recognition…")
                VoiceFlowLog.info(context, "Online recognition requested.")
                val onlineText = OnlineTranscriber.transcribe(
                    context, audioFile, endpoint, language, timeout
                )
                if (!onlineText.isNullOrBlank()) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    VoiceFlowLog.info(context, "Online recognition succeeded in " + elapsed + " ms.")
                    return TranscriptionResult(onlineText.trim(), false, elapsed)
                }
                VoiceFlowLog.warn(context, "Online recognition returned no result; switching to offline model.")
            }

            onStatus("Loading offline model…")
            VoiceFlowLog.info(context, "Offline Whisper recognition started.")
            onStatus("Starting on-device speech engine…")
            val text = WhisperEngine.transcribe(context, audioFile, language, onProgress)
            if (text.isBlank()) {
                throw IllegalStateException("No speech was recognized. Check microphone level and try again.")
            }

            val elapsed = System.currentTimeMillis() - startedAt
            VoiceFlowLog.info(context, "Offline recognition succeeded in " + elapsed + " ms.")
            return TranscriptionResult(text.trim(), true, elapsed)
        } catch (error: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            VoiceFlowLog.error(context, "Transcription failed after " + elapsed + " ms.", error)
            throw error
        } finally {
            if (audioFile.exists() && !audioFile.delete()) {
                VoiceFlowLog.warn(context, "Could not delete temporary audio file: " + audioFile.name)
            } else {
                VoiceFlowLog.info(context, "Temporary audio removed.")
            }
        }
    }
}
