/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterReasoningCapabilityTest {

    private fun entry(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun currentReasoningMetadataUsesTheModelEffortList() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{"supported_efforts":["high","medium","low"],"supports_max_tokens":false,"mandatory":false,"can_return_visible":true}}""")
        )!!
        assertEquals(ReasoningSupport.KNOWN, cap.support)
        assertTrue(cap.effortConfigurable)
        assertEquals(
            listOf(ReasoningEffort.HIGH, ReasoningEffort.MEDIUM, ReasoningEffort.LOW),
            cap.supportedEfforts
        )
        assertTrue(cap.canDisableReasoning)
        assertTrue(cap.canReturnVisibleReasoning)
        assertFalse(cap.tokenBudgetSupported)
        assertEquals(CapabilitySource.PROVIDER_METADATA, cap.source)
    }

    @Test
    fun currentMetadataPreservesXhighMediumAndLow() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{"supported_efforts":["xhigh","medium","low"]}}""")
        )!!
        assertEquals(
            listOf(ReasoningEffort.XHIGH, ReasoningEffort.MEDIUM, ReasoningEffort.LOW),
            cap.supportedEfforts
        )
    }

    @Test
    fun currentMetadataPreservesMaxHighAndLow() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{"supported_efforts":["max","high","low"]}}""")
        )!!
        assertEquals(
            listOf(ReasoningEffort.MAX, ReasoningEffort.HIGH, ReasoningEffort.LOW),
            cap.supportedEfforts
        )
    }

    @Test
    fun richMetadataWithoutEffortsDoesNotInventAList() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{},"supported_parameters":["include_reasoning"]}""")
        )!!
        assertTrue(cap.isReasoningCapable)
        assertFalse(cap.effortConfigurable)
        assertTrue(cap.supportedEfforts.isEmpty())
        assertTrue(cap.canReturnVisibleReasoning)
    }

    @Test
    fun richMetadataWithoutVisibleEvidenceDoesNotPromiseThinking() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"unknown/model","reasoning":{"supported_efforts":["high"]}}""")
        )!!
        assertFalse(cap.canReturnVisibleReasoning)
    }

    @Test
    fun nullEffortListMeansAllCurrentGatewayEfforts() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{"supported_efforts":null}}""")
        )!!
        assertEquals(
            listOf(
                ReasoningEffort.MAX,
                ReasoningEffort.XHIGH,
                ReasoningEffort.HIGH,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.LOW,
                ReasoningEffort.MINIMAL
            ),
            cap.supportedEfforts
        )
    }

    @Test
    fun mandatoryReasoningOmitsOff() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{"supported_efforts":["max","high","low"],"mandatory":true}}""")
        )!!
        assertFalse(cap.canDisableReasoning)
        assertFalse(cap.thinkingChoices().contains(ReasoningEffort.OFF))
        assertFalse(cap.supports(ReasoningEffort.OFF))
    }

    @Test
    fun onOffOnlyMetadataDoesNotInventEffortLevels() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{"supported_efforts":["none"]}}""")
        )!!
        assertTrue(cap.effortConfigurable)
        assertTrue(cap.supportedEfforts.isEmpty())
        assertEquals(
            listOf(ReasoningEffort.AUTO, ReasoningEffort.OFF),
            cap.thinkingChoices()
        )
    }

    @Test
    fun supportsMaxTokensIsReadOnlyFromDedicatedMetadata() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","reasoning":{"supported_efforts":["high"],"supports_max_tokens":true}}""")
        )!!
        assertTrue(cap.tokenBudgetSupported)
    }

    @Test
    fun knownOpenAiReasoningModelDoesNotPromiseVisibleThinking() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"openai/o3","reasoning":{"supported_efforts":["low","high"]}}""")
        )!!
        assertFalse(cap.canReturnVisibleReasoning)
    }

    @Test
    fun legacySupportedParametersRemainACompatibilityFallback() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","supported_parameters":["max_tokens","reasoning","tools"]}""")
        )!!
        assertTrue(cap.isReasoningCapable)
        assertFalse(cap.effortConfigurable)
        assertTrue(cap.supportedEfforts.isEmpty())
        assertFalse(cap.tokenBudgetSupported)
        assertFalse(cap.canDisableReasoning)
        assertTrue(cap.canReturnVisibleReasoning)
    }

    @Test
    fun includeReasoningOnlyMeansReturnableButNotConfigurable() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","supported_parameters":["include_reasoning"]}""")
        )!!
        assertEquals(ReasoningSupport.KNOWN, cap.support)
        assertFalse(cap.effortConfigurable)
        assertTrue(cap.supportedEfforts.isEmpty())
        assertFalse(cap.canDisableReasoning)
        assertTrue(cap.canReturnVisibleReasoning)
    }

    @Test
    fun noReasoningMarkerFallsThroughAsNull() {
        assertNull(
            OpenRouterReasoningCapability.fromModelEntry(
                entry("""{"id":"x/y","supported_parameters":["max_tokens","tools","temperature"]}""")
            )
        )
    }

    @Test
    fun missingOrMalformedMetadataIsNullNeverAbsent() {
        assertNull(OpenRouterReasoningCapability.fromModelEntry(null))
        assertNull(OpenRouterReasoningCapability.fromModelEntry(entry("""{"id":"x/y"}""")))
        assertNull(OpenRouterReasoningCapability.fromModelEntry(entry("""{"supported_parameters":"reasoning"}""")))
        assertNull(OpenRouterReasoningCapability.fromModelEntry(entry("""{"supported_parameters":[]}""")))
    }

    @Test
    fun markerMatchingIsCaseInsensitive() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"supported_parameters":["Reasoning"]}""")
        )
        assertTrue(cap != null && cap.isReasoningCapable)
        assertFalse(cap!!.effortConfigurable)
    }
}
