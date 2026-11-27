package com.ajmat.voiceflow.speech

/**
 * Maps the UI language selection to Whisper's transcription parameters.
 *
 * "auto" asks Whisper to detect the language and then transcribe. The
 * detect_language flag is reserved for detection-only calls and must remain
 * false for normal voice typing.
 */
data class WhisperLanguageConfig(
    val language: String,
    val detectLanguage: Boolean
)

object WhisperLanguagePolicy {
    fun forTranscription(selection: String?): WhisperLanguageConfig {
        val language = RecognitionPolicy.normalizeLanguage(selection)
        return WhisperLanguageConfig(
            language = language,
            detectLanguage = false
        )
    }
}
