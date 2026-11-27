package com.ajmat.voiceflow.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionPolicyTest {
    @Test
    fun blankEndpointUsesOfflineOnly() {
        assertEquals(RecognitionRoute.OFFLINE_ONLY, RecognitionPolicy.routeFor("  "))
        assertEquals(RecognitionRoute.OFFLINE_ONLY, RecognitionPolicy.routeFor(null))
    }

    @Test
    fun configuredEndpointUsesOnlineThenOffline() {
        assertEquals(
            RecognitionRoute.ONLINE_THEN_OFFLINE,
            RecognitionPolicy.routeFor("https://speech.example.test/transcribe")
        )
    }

    @Test
    fun localeLanguageMapsToWhisperLanguage() {
        assertEquals("hi", RecognitionPolicy.languageFromLocale("hi-IN"))
        assertEquals("en", RecognitionPolicy.languageFromLocale("en-IN"))
        assertEquals("en", RecognitionPolicy.languageFromLocale("en-US"))
        assertEquals("auto", RecognitionPolicy.languageFromLocale(null))
        assertEquals("auto", RecognitionPolicy.languageFromLocale(""))
    }

    @Test
    fun languageSelectionIsNormalized() {
        assertEquals("hi", RecognitionPolicy.normalizeLanguage(" Hindi "))
        assertEquals("en", RecognitionPolicy.normalizeLanguage("ENGLISH"))
        assertEquals("auto", RecognitionPolicy.normalizeLanguage("Hinglish"))
        assertEquals("auto", RecognitionPolicy.normalizeLanguage(null))
    }
}
