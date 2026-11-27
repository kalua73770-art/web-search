package com.ajmat.voiceflow.speech

enum class RecognitionRoute {
    OFFLINE_ONLY,
    ONLINE_THEN_OFFLINE
}

/**
 * Maps Android locale tags to Whisper's language identifiers.
 * Unknown/mixed locales deliberately use Whisper's automatic detector.
 */
object RecognitionPolicy {
    fun routeFor(endpoint: String?): RecognitionRoute =
        if (endpoint?.trim()?.isNotEmpty() == true) {
            RecognitionRoute.ONLINE_THEN_OFFLINE
        } else {
            RecognitionRoute.OFFLINE_ONLY
        }

    fun normalizeLanguage(language: String?): String = when (language?.trim()?.lowercase()) {
        "hi", "hindi", "hi-in" -> "hi"
        "en", "english", "en-in", "en-us", "en-gb" -> "en"
        else -> "auto"
    }

    fun languageFromLocale(locale: String?): String {
        val languageTag = locale
            ?.trim()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.lowercase()
        return when (languageTag) {
            "hi" -> "hi"
            "en" -> "en"
            else -> "auto"
        }
    }
}
