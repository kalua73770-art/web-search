package com.ajmat.voiceflow.speech

enum class RecognitionRoute {
    OFFLINE_ONLY,
    ONLINE_THEN_OFFLINE
}

/**
 * Online recognition is attempted only when the user has configured an
 * HTTPS endpoint. An absent endpoint always means fully local recognition.
 */
object RecognitionPolicy {
    fun routeFor(endpoint: String?): RecognitionRoute =
        if (endpoint?.trim()?.isNotEmpty() == true) {
            RecognitionRoute.ONLINE_THEN_OFFLINE
        } else {
            RecognitionRoute.OFFLINE_ONLY
        }

    fun normalizeLanguage(language: String?): String = when (language?.trim()?.lowercase()) {
        "hi", "hindi" -> "hi"
        "en", "english" -> "en"
        else -> "auto"
    }
}
