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
}
