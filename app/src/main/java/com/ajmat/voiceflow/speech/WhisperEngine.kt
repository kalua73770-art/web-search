package com.ajmat.voiceflow.speech

import android.content.Context
import java.io.File

object WhisperEngine {
    private var contextPointer: Long = 0L
    private var loadedModelPath: String? = null

    @Synchronized
    fun transcribe(context: Context, wavFile: File, language: String): String {
        val preferences = context.getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
        val model = ModelCatalog.byId(preferences.getString("model_id", "small-q5"))
        val filename = preferences.getString("model_file", model.fileName) ?: model.fileName
        val modelFile = File(File(context.filesDir, "models"), filename)
        if (!modelFile.isFile || modelFile.length() < model.minimumBytes) {
            throw IllegalStateException("Download an offline model in Ajmat VoiceFlow first.")
        }

        if (loadedModelPath != modelFile.absolutePath || contextPointer == 0L) {
            releaseModel()
            contextPointer = WhisperJNI.loadModel(modelFile.absolutePath)
            if (contextPointer == 0L) {
                throw IllegalStateException("Whisper could not load this model. Try Tiny Q5.")
            }
            loadedModelPath = modelFile.absolutePath
        }

        val result = WhisperJNI.transcribeAudio(
            contextPointer,
            wavFile.absolutePath,
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            RecognitionPolicy.normalizeLanguage(language)
        )
        if (result.startsWith("ERROR:")) {
            throw IllegalStateException(result.removePrefix("ERROR:").trim())
        }
        return result.trim()
    }

    @Synchronized
    fun releaseModel() {
        if (contextPointer != 0L) {
            WhisperJNI.freeModel(contextPointer)
            contextPointer = 0L
            loadedModelPath = null
        }
    }
}
