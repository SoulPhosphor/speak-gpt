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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectProviderReasoningKnowledgeTest {

    @Test
    fun openAiOSeriesReasonsWithEffortButNoVisibleReasoningAndNoOff() {
        for (id in listOf("o1", "o1-mini", "o3", "o3-mini", "o4-mini", "openai/o3")) {
            val cap = DirectProviderReasoningKnowledge.fromModelId(id)
            assertTrue("expected reasoning for $id", cap != null && cap.isReasoningCapable)
            assertTrue(cap!!.effortConfigurable)
            assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), cap.supportedEfforts)
            assertFalse(cap.canReturnVisibleReasoning) // chat completions hides CoT
            assertFalse(cap.canDisableReasoning)       // reasoning mandatory
            assertEquals(CapabilitySource.PROVIDER_ADAPTER, cap.source)
        }
    }

    @Test
    fun gpt5FamilyIsReasoning() {
        val cap = DirectProviderReasoningKnowledge.fromModelId("gpt-5")
        assertTrue(cap != null && cap.effortConfigurable)
        assertEquals(
            listOf(
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH
            ),
            cap!!.supportedEfforts
        )
        assertFalse(cap.canDisableReasoning)
    }

    @Test
    fun gpt5ProAcceptsOnlyHigh() {
        val cap = DirectProviderReasoningKnowledge.fromModelId("gpt-5-pro")!!
        assertEquals(listOf(ReasoningEffort.HIGH), cap.supportedEfforts)
        assertTrue(cap.supports(ReasoningEffort.HIGH))
        assertFalse(cap.supports(ReasoningEffort.MEDIUM))
    }

    @Test
    fun newerGpt5FamiliesKeepTheirKnownEffortShapes() {
        val gpt51 = DirectProviderReasoningKnowledge.fromModelId("gpt-5.1")!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            gpt51.supportedEfforts
        )
        assertTrue(gpt51.canDisableReasoning)

        val gpt52 = DirectProviderReasoningKnowledge.fromModelId("gpt-5.2")!!
        assertTrue(gpt52.supports(ReasoningEffort.XHIGH))
        assertTrue(gpt52.canDisableReasoning)

        val gpt56 = DirectProviderReasoningKnowledge.fromModelId("gpt-5.6")!!
        assertEquals(
            listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX
            ),
            gpt56.supportedEfforts
        )
        assertTrue(gpt56.canDisableReasoning)

        // A future/unidentified generation must not inherit the classic GPT-5
        // ladder merely because its name contains "gpt-5".
        assertNull(DirectProviderReasoningKnowledge.fromModelId("gpt-5.3"))
    }

    @Test
    fun deepSeekReasonerReturnsVisibleReasoningWithoutEffortControl() {
        for (id in listOf("deepseek-reasoner", "deepseek/deepseek-r1", "deepseek-r1")) {
            val cap = DirectProviderReasoningKnowledge.fromModelId(id)
            assertTrue("expected reasoning for $id", cap != null && cap.isReasoningCapable)
            assertFalse(cap!!.effortConfigurable)
            assertTrue(cap.canReturnVisibleReasoning)
            assertFalse(cap.canDisableReasoning)
        }
    }

    @Test
    fun openRouterEndpointKeepsOpenRouterBoundaryForFallbackKnowledge() {
        val cap = DirectProviderReasoningKnowledge.fromModelId(
            modelId = "deepseek-reasoner",
            providerHint = "OpenRouter",
            endpointHost = "https://openrouter.ai/api/v1"
        )!!
        assertEquals(ReasoningRequestFormat.OPENROUTER, cap.requestFormat)
        assertTrue(cap.continuationStateSupported)
    }

    @Test
    fun anthropicThinkingFamilyIsKnownWithoutInventingEfforts() {
        val cap = DirectProviderReasoningKnowledge.fromModelId(
            modelId = "claude-3-7-sonnet",
            providerHint = "Anthropic",
            endpointHost = "https://api.anthropic.com/v1"
        )!!
        assertTrue(cap.isReasoningCapable)
        assertFalse(cap.canReturnVisibleReasoning)
        assertFalse(cap.canDisableReasoning)
        assertFalse(cap.effortConfigurable)
        assertFalse(cap.tokenBudgetSupported)
        assertFalse(cap.hasConfigurableSetting)
    }

    @Test
    fun geminiThinkingFamiliesExposeOnlyKnownLevels() {
        val gemini25 = DirectProviderReasoningKnowledge.fromModelId("gemini-2.5-flash")!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            gemini25.supportedEfforts
        )
        assertTrue(gemini25.canDisableReasoning)
        assertFalse(gemini25.tokenBudgetSupported)
        assertFalse(gemini25.canReturnVisibleReasoning)
        assertTrue(gemini25.hasConfigurableSetting)

        val gemini3 = DirectProviderReasoningKnowledge.fromModelId("google/gemini-3-flash-preview")!!
        assertEquals(
            listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            gemini3.supportedEfforts
        )
        assertFalse(gemini3.canDisableReasoning)

        val gemini3Pro = DirectProviderReasoningKnowledge.fromModelId("google/gemini-3-pro-preview")!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            gemini3Pro.supportedEfforts
        )
        assertFalse(gemini3Pro.supports(ReasoningEffort.MINIMAL))
        assertFalse(gemini3Pro.canDisableReasoning)

        val gemini31Pro = DirectProviderReasoningKnowledge.fromModelId("gemini-3.1-pro-preview")!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            gemini31Pro.supportedEfforts
        )
        assertFalse(gemini31Pro.canDisableReasoning)

        // Do not turn an unrecognized Gemini 3 family into a universal ladder.
        assertNull(DirectProviderReasoningKnowledge.fromModelId("gemini-3-unknown"))
    }

    @Test
    fun nonReasoningIdsAreNotClassifiedHere() {
        // deepseek-chat (V3) is not a reasoner; plain chat models are unknown to
        // this tier and must fall through (null), never to a false "absent".
        assertNull(DirectProviderReasoningKnowledge.fromModelId("deepseek-chat"))
        assertNull(DirectProviderReasoningKnowledge.fromModelId("gpt-4o"))
        assertNull(DirectProviderReasoningKnowledge.fromModelId("claude-3.5-sonnet"))
        assertNull(DirectProviderReasoningKnowledge.fromModelId(""))
        assertNull(DirectProviderReasoningKnowledge.fromModelId(null))
    }

    @Test
    fun genericSubstringsDoNotTriggerThisTier() {
        // "pro" / "thinking" / "deep" as loose substrings must not classify here
        // (§7.7 forbids generic-substring authority). "gpt-4-pro-preview" has no
        // gpt-5 / o-series identity and no deepseek-reasoner identity.
        assertNull(DirectProviderReasoningKnowledge.fromModelId("some-thinking-model"))
        assertNull(DirectProviderReasoningKnowledge.fromModelId("deepthink-42"))
    }
}
