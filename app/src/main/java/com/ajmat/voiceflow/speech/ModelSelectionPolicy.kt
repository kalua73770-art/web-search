package com.ajmat.voiceflow.speech

object ModelSelectionPolicy {
    fun resolveModelId(savedId: String?): String = when (savedId) {
        null, "", "tiny-q5" -> "base-q5"
        "base-q5" -> "base-q5"
        "small-q5" -> "small-q5"
        else -> "base-q5"
    }
}
