package com.ajmat.voiceflow.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperRuntimePolicyTest {
    @Test
    fun inferenceDoesNotTimeoutBeforeTwoMinutes() {
        assertFalse(WhisperRuntimePolicy.hasTimedOut(startedAtMs = 1_000L, nowMs = 120_999L))
    }

    @Test
    fun inferenceTimesOutAtTwoMinutes() {
        assertTrue(WhisperRuntimePolicy.hasTimedOut(startedAtMs = 1_000L, nowMs = 121_000L))
    }

    @Test
    fun progressIsClampedToValidRange() {
        assertTrue(WhisperRuntimePolicy.normalizeProgress(-1) == 0)
        assertTrue(WhisperRuntimePolicy.normalizeProgress(45) == 45)
        assertTrue(WhisperRuntimePolicy.normalizeProgress(101) == 100)
    }
}
