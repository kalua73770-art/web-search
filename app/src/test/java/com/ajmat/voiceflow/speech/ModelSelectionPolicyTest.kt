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
    fun initial130UpgradeDefaultsToBaseEvenWhenSmallWasPreviouslySelected() {
        assertEquals("base-q5", ModelSelectionPolicy.initialModelId("small-q5", false))
    }

    @Test
    fun laterUserSelectionOfSmallIsPreserved() {
        assertEquals("small-q5", ModelSelectionPolicy.initialModelId("small-q5", true))
    }

    @Test
    fun existingSmallSelectionIsPreserved() {
        assertEquals("small-q5", ModelSelectionPolicy.resolveModelId("small-q5"))
    }
}
