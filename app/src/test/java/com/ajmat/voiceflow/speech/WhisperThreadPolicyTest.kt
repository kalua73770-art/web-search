package com.ajmat.voiceflow.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperThreadPolicyTest {
    @Test
    fun usesAtLeastTwoThreadsOnSmallDevices() {
        assertEquals(2, WhisperThreadPolicy.threadCount(1))
        assertEquals(2, WhisperThreadPolicy.threadCount(2))
    }

    @Test
    fun usesAvailableThreadsUpToEight() {
        assertEquals(4, WhisperThreadPolicy.threadCount(4))
        assertEquals(8, WhisperThreadPolicy.threadCount(8))
        assertEquals(8, WhisperThreadPolicy.threadCount(12))
    }
}
