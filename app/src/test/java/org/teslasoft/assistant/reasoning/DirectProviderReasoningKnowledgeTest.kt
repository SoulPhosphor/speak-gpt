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
    }

    @Test
    fun gpt5ProAcceptsOnlyHigh() {
        val cap = DirectProviderReasoningKnowledge.fromModelId("gpt-5-pro")!!
        assertEquals(listOf(ReasoningEffort.HIGH), cap.supportedEfforts)
        assertTrue(cap.supports(ReasoningEffort.HIGH))
        assertFalse(cap.supports(ReasoningEffort.MEDIUM))
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
    fun anthropicThinkingFamilyIsKnownWithoutInventingEfforts() {
        val cap = DirectProviderReasoningKnowledge.fromModelId("claude-3-7-sonnet")!!
        assertTrue(cap.isReasoningCapable)
        assertTrue(cap.canReturnVisibleReasoning)
        assertTrue(cap.canDisableReasoning)
        assertFalse(cap.effortConfigurable)
        assertTrue(cap.tokenBudgetSupported)
    }

    @Test
    fun geminiThinkingFamiliesExposeOnlyKnownLevels() {
        val gemini25 = DirectProviderReasoningKnowledge.fromModelId("gemini-2.5-flash")!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            gemini25.supportedEfforts
        )
        assertTrue(gemini25.canDisableReasoning)
        assertTrue(gemini25.tokenBudgetSupported)

        val gemini3 = DirectProviderReasoningKnowledge.fromModelId("google/gemini-3-flash")!!
        assertEquals(
            listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            gemini3.supportedEfforts
        )
        assertFalse(gemini3.canDisableReasoning)
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
