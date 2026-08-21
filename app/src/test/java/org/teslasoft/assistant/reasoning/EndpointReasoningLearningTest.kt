/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointReasoningLearningTest {

    /** An OpenRouter catalog entry that advertises the unified reasoning object. */
    private fun reasoningEntry(id: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", id)
        val params = JsonArray()
        params.add("reasoning")
        obj.add("supported_parameters", params)
        return obj
    }

    @Test
    fun gatewayMetadataOffersMinimalAndXhighOptimistically() {
        val cap = EndpointReasoningCapability.resolveWithLearnedRejections(
            reasoningCapabilityByModel = null,
            rejectedLevelsByModel = null,
            modelId = "vendor/m",
            liveModelEntry = reasoningEntry("vendor/m")
        )
        assertEquals(
            listOf(
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH, ReasoningEffort.XHIGH
            ),
            cap.supportedEfforts
        )
    }

    @Test
    fun learnedRejectionsAreSubtracted() {
        val rejected = RejectedReasoningLevelStore.add(null, "vendor/m", ReasoningEffort.XHIGH)
        val cap = EndpointReasoningCapability.resolveWithLearnedRejections(
            reasoningCapabilityByModel = null,
            rejectedLevelsByModel = rejected,
            modelId = "vendor/m",
            liveModelEntry = reasoningEntry("vendor/m")
        )
        assertFalse(cap.supportedEfforts.contains(ReasoningEffort.XHIGH))
        assertTrue(cap.supportedEfforts.contains(ReasoningEffort.MINIMAL))
        assertTrue(cap.supportedEfforts.contains(ReasoningEffort.HIGH))
    }

    @Test
    fun rejectionIsScopedToTheExactModel() {
        val rejected = RejectedReasoningLevelStore.add(null, "vendor/m", ReasoningEffort.MINIMAL)
        val other = EndpointReasoningCapability.resolveWithLearnedRejections(
            null, rejected, "vendor/other", reasoningEntry("vendor/other")
        )
        assertTrue(other.supportedEfforts.contains(ReasoningEffort.MINIMAL))
    }

    @Test
    fun nonReasoningPathIsUnchanged() {
        val cap = EndpointReasoningCapability.resolveWithLearnedRejections(
            null, null, "plain-chat-model", null
        )
        assertFalse(cap.isReasoningCapable)
        assertTrue(cap.supportedEfforts.isEmpty())
    }
}
