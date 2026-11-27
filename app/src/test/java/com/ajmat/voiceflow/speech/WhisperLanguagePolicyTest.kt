package com.ajmat.voiceflow.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WhisperLanguagePolicyTest {
    @Test
    fun autoDetectSelectsAutomaticLanguageWithoutStoppingAfterDetection() {
        val config = WhisperLanguagePolicy.forTranscription("auto")

        assertEquals("auto", config.language)
        assertFalse(config.detectLanguage)
    }

    @Test
    fun explicitHindiLanguageAlsoRunsTranscription() {
        val config = WhisperLanguagePolicy.forTranscription("hi")

        assertEquals("hi", config.language)
        assertFalse(config.detectLanguage)
    }

    @Test
    fun localeAndUnknownSelectionAreNormalizedBeforeNativeCall() {
        assertEquals("en", WhisperLanguagePolicy.forTranscription("en-IN").language)
        assertEquals("auto", WhisperLanguagePolicy.forTranscription("Hinglish").language)
    }
}
