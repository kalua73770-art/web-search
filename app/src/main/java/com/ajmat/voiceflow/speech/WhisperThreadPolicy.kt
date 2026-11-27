package com.ajmat.voiceflow.speech

object WhisperThreadPolicy {
    fun threadCount(availableProcessors: Int): Int = availableProcessors.coerceIn(2, 8)
}
