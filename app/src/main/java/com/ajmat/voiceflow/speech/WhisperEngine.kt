package com.ajmat.voiceflow.speech

import android.content.Context
import com.ajmat.voiceflow.logging.VoiceFlowLog
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
            throw IllegalStateException("Offline model is missing or incomplete. Open Ajmat VoiceFlow and download it again.")
        }

        if (loadedModelPath != modelFile.absolutePath || contextPointer == 0L) {
            releaseModel()
            VoiceFlowLog.info(context, "Loading Whisper model " + model.id + " (" + modelFile.length() + " bytes).")
            val loadStarted = System.currentTimeMillis()
            contextPointer = WhisperJNI.loadModel(modelFile.absolutePath)
            if (contextPointer == 0L) {
                throw IllegalStateException("Whisper could not load the model. Check free RAM and try Tiny Q5.")
            }
            loadedModelPath = modelFile.absolutePath
            VoiceFlowLog.info(context, "Whisper model loaded in " + (System.currentTimeMillis() - loadStarted) + " ms.")
        }

        val normalizedLanguage = RecognitionPolicy.normalizeLanguage(language)
        VoiceFlowLog.info(context, "Starting whisper_full; language=" + normalizedLanguage + ".")
        val runStarted = System.currentTimeMillis()
        val result = WhisperJNI.transcribeAudio(
            contextPointer,
            wavFile.absolutePath,
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            normalizedLanguage
        )
        val elapsed = System.currentTimeMillis() - runStarted
        if (result.startsWith("ERROR:")) {
            throw IllegalStateException(result.removePrefix("ERROR:").trim())
        }
        VoiceFlowLog.info(context, "whisper_full returned in " + elapsed + " ms.")
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
