package com.ajmat.voiceflow.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionPolicyTest {
    @Test
    fun newInstallDefaultsToBaseQ5() {
        assertEquals("base-q5", ModelSelectionPolicy.resolveModelId(null))
    }

    @Test
    fun legacyTinySelectionMigratesToBaseQ5() {
        assertEquals("base-q5", ModelSelectionPolicy.resolveModelId("tiny-q5"))
    }

    @Test
    fun existingSmallSelectionIsPreserved() {
        assertEquals("small-q5", ModelSelectionPolicy.resolveModelId("small-q5"))
    }
}
