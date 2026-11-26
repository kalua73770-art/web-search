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
    fun languageSelectionIsNormalized() {
        assertEquals("hi", RecognitionPolicy.normalizeLanguage(" Hindi "))
        assertEquals("en", RecognitionPolicy.normalizeLanguage("ENGLISH"))
        assertEquals("auto", RecognitionPolicy.normalizeLanguage("Hinglish"))
        assertEquals("auto", RecognitionPolicy.normalizeLanguage(null))
    }
}
