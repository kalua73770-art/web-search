package com.ajmat.voiceflow.speech

internal object WhisperJNI {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun loadModel(modelPath: String): Long
    external fun freeModel(contextPointer: Long)
    external fun transcribeAudio(
        contextPointer: Long,
        wavPath: String,
        numThreads: Int,
        language: String
    ): String

    /** Cooperative native abort, checked between Whisper compute graphs. */
    external fun cancelTranscription()

    /** Returns -1 if idle; otherwise the last native progress callback, 0..100. */
    external fun getProgress(): Int

    external fun getSystemInfo(): String
}
