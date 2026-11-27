package com.ajmat.voiceflow.speech

import android.content.Context
import com.ajmat.voiceflow.logging.VoiceFlowLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object WhisperEngine {
    private var contextPointer: Long = 0L
    private var loadedModelPath: String? = null
    private val cancelReason = AtomicReference<String?>(null)
    private val cancelRequested = AtomicBoolean(false)

    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "VoiceFlow-Whisper-Timer").apply { isDaemon = true }
    }
    private val progressPoller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "VoiceFlow-Whisper-Progress").apply { isDaemon = true }
    }

    @Synchronized
    fun transcribe(
        context: Context,
        wavFile: File,
        language: String,
        onProgress: (Int) -> Unit = {}
    ): String {
        // Reset once, before model loading, so Cancel also works during model setup.
        cancelReason.set(null)
        cancelRequested.set(false)

        val preferences = context.getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
        val model = ModelCatalog.byId(preferences.getString("model_id", "base-q5"))
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
                throw IllegalStateException("Whisper could not load the model. Check free RAM and try Base Q5.")
            }
            loadedModelPath = modelFile.absolutePath
            VoiceFlowLog.info(
                context,
                "Whisper model loaded in " + (System.currentTimeMillis() - loadStarted) +
                    " ms. Native CPU features: " + WhisperJNI.getSystemInfo()
            )
        }

        if (cancelRequested.get()) {
            throw IllegalStateException(cancelReason.get() ?: "Recognition cancelled by user.")
        }

        val languageConfig = WhisperLanguagePolicy.forTranscription(language)
        val normalizedLanguage = languageConfig.language
        val startedAt = System.currentTimeMillis()
        VoiceFlowLog.info(
            context,
            "Native inference starting; model=" + model.id +
                ", language=" + normalizedLanguage +
                ", audioBytes=" + wavFile.length() +
                ", timeoutMs=" + WhisperRuntimePolicy.INFERENCE_TIMEOUT_MS + "."
        )

        val timeoutFuture: ScheduledFuture<*> = timer.schedule({
            val reason = "Whisper recognition exceeded 120 seconds and was stopped. Try a shorter recording or Base Q5."
            if (cancelReason.compareAndSet(null, reason)) {
                cancelRequested.set(true)
                VoiceFlowLog.error(context, reason)
                try {
                    WhisperJNI.cancelTranscription()
                } catch (error: Exception) {
                    VoiceFlowLog.error(context, "Could not signal native Whisper timeout.", error)
                }
            }
        }, WhisperRuntimePolicy.INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        var lastLoggedProgress = -10
        val progressFuture = progressPoller.scheduleAtFixedRate({
            try {
                val rawProgress = WhisperJNI.getProgress()
                if (rawProgress >= 0) {
                    val value = WhisperRuntimePolicy.normalizeProgress(rawProgress)
                    onProgress(value)
                    if (value >= lastLoggedProgress + 10 || value == 100) {
                        lastLoggedProgress = value
                        VoiceFlowLog.info(context, "Whisper native progress: " + value + "%.")
                    }
                }
            } catch (error: Exception) {
                VoiceFlowLog.warn(context, "Unable to read Whisper progress: " + (error.message ?: "unknown error"))
            }
        }, 0, 500, TimeUnit.MILLISECONDS)

        try {
            val available = Runtime.getRuntime().availableProcessors()
            val threads = available.coerceIn(2, 6)
            val result = WhisperJNI.transcribeAudio(
                contextPointer,
                wavFile.absolutePath,
                threads,
                normalizedLanguage,
                languageConfig.detectLanguage
            )
            val elapsed = System.currentTimeMillis() - startedAt
            val reason = cancelReason.get()
            if (cancelRequested.get() || reason != null) {
                val message = reason ?: "Recognition cancelled by user."
                VoiceFlowLog.warn(context, "Native inference stopped: " + message)
                throw IllegalStateException(message)
            }
            if (result.startsWith("ERROR:")) {
                throw IllegalStateException(result.removePrefix("ERROR:").trim())
            }
            if (result.isBlank()) {
                throw IllegalStateException("Whisper returned no text. Try speaking for 2–3 seconds in a quiet place.")
            }
            onProgress(100)
            VoiceFlowLog.info(
                context,
                "Native inference completed in " + elapsed + " ms using " + threads +
                    " CPU threads; transcriptLength=" + result.length + "."
            )
            return result.trim()
        } catch (error: Exception) {
            VoiceFlowLog.error(context, "Native Whisper inference failed.", error)
            throw error
        } finally {
            timeoutFuture.cancel(false)
            progressFuture.cancel(false)
            cancelReason.set(null)
            cancelRequested.set(false)
        }
    }

    /**
     * Intentionally not synchronized: Cancel must work while transcribe()
     * holds the model lock and is blocked inside native inference.
     */
    fun cancelTranscription() {
        cancelRequested.set(true)
        cancelReason.compareAndSet(null, "Recognition cancelled by user.")
        try {
            WhisperJNI.cancelTranscription()
        } catch (_: Exception) {
            // Native inference may have completed or still be loading the model.
        }
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
