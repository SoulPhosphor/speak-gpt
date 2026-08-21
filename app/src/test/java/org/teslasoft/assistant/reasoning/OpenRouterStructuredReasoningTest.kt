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

/**
 * Parsing of OpenRouter's live per-model `reasoning` object
 * (`supported_efforts`, `mandatory`), verified against real catalog shapes.
 */
class OpenRouterStructuredReasoningTest {

    private fun entry(
        supportedEfforts: List<String>?,
        mandatory: Boolean?,
        hasReasoningObject: Boolean = true,
        supportedParameters: List<String> = listOf("reasoning", "reasoning_effort")
    ): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", "vendor/m")
        val params = JsonArray().apply { supportedParameters.forEach { add(it) } }
        obj.add("supported_parameters", params)
        if (hasReasoningObject) {
            val reasoning = JsonObject()
            mandatory?.let { reasoning.addProperty("mandatory", it) }
            supportedEfforts?.let { list ->
                reasoning.add("supported_efforts", JsonArray().apply { list.forEach { add(it) } })
            }
            obj.add("reasoning", reasoning)
        }
        return obj
    }

    @Test
    fun museSparkFullLadderMandatory() {
        // reasoning: mandatory true, supported_efforts xhigh/high/medium/low/minimal
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry(listOf("xhigh", "high", "medium", "low", "minimal"), mandatory = true)
        )!!
        assertTrue(cap.isReasoningCapable)
        assertTrue(cap.effortConfigurable)
        assertTrue(cap.effortsAuthoritative)
        assertFalse(cap.canDisableReasoning) // mandatory
        assertEquals(
            listOf(
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH, ReasoningEffort.XHIGH
            ),
            cap.supportedEfforts
        )
    }

    @Test
    fun deepSeekMaxHighLowHasNoMediumAndKeepsMax() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry(listOf("max", "high", "low"), mandatory = false)
        )!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            cap.supportedEfforts
        )
        assertFalse(cap.supportedEfforts.contains(ReasoningEffort.MEDIUM))
        assertTrue(cap.canDisableReasoning) // not mandatory
        // The Thinking dropdown offers exactly Auto + the published ladder + Off.
        assertEquals(
            listOf(
                ReasoningEffort.AUTO, ReasoningEffort.LOW, ReasoningEffort.HIGH,
                ReasoningEffort.MAX, ReasoningEffort.OFF
            ),
            cap.thinkingChoices()
        )
    }

    @Test
    fun qwenXhighMediumLowHasNoHigh() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry(listOf("xhigh", "medium", "low"), mandatory = false)
        )!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.XHIGH),
            cap.supportedEfforts
        )
        assertFalse(cap.supportedEfforts.contains(ReasoningEffort.HIGH))
    }

    @Test
    fun reasoningObjectWithoutEffortsIsKnownButNotConfigurable() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry(supportedEfforts = null, mandatory = false)
        )!!
        assertTrue(cap.isReasoningCapable)
        assertFalse(cap.effortConfigurable)
        assertFalse(cap.effortsAuthoritative)
    }

    @Test
    fun singlePublishedLevelIsFixedNotAChoice() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry(listOf("high"), mandatory = true)
        )!!
        assertTrue(cap.isReasoningCapable)
        assertFalse(cap.effortConfigurable) // one level is not a choice
    }

    @Test
    fun noReasoningObjectFallsBackToSupportedParameters() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry(supportedEfforts = null, mandatory = null, hasReasoningObject = false)
        )!!
        assertTrue(cap.effortConfigurable)
        assertFalse(cap.effortsAuthoritative) // fallback ladder, learning may widen
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            cap.supportedEfforts
        )
    }

    @Test
    fun nonReasoningModelYieldsNull() {
        val obj = JsonObject()
        obj.addProperty("id", "tencent/translate")
        obj.add("supported_parameters", JsonArray().apply { add("max_tokens"); add("temperature") })
        assertEquals(null, OpenRouterReasoningCapability.fromModelEntry(obj))
    }

    @Test
    fun authoritativeLadderOverridesLearnedRejection() {
        // xhigh is a learnable level that would normally be subtracted on a
        // non-authoritative path. On an authoritative published ladder the
        // metadata wins and it is still offered.
        val e = entry(listOf("xhigh", "medium", "low"), mandatory = false)
        val rejected = RejectedReasoningLevelStore.add(null, "vendor/m", ReasoningEffort.XHIGH)
        val cap = EndpointReasoningCapability.resolveWithLearnedRejections(
            reasoningCapabilityByModel = null,
            rejectedLevelsByModel = rejected,
            modelId = "vendor/m",
            liveModelEntry = e
        )
        assertTrue(cap.supportedEfforts.contains(ReasoningEffort.XHIGH))
    }
}
