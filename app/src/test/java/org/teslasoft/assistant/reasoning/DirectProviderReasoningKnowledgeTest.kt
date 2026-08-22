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
            val cap = DirectProviderReasoningKnowledge.fromModelId(id, ReasoningProviderPath.OPENAI)
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
        val cap = DirectProviderReasoningKnowledge.fromModelId("gpt-5", ReasoningProviderPath.OPENAI)
        assertTrue(cap != null && cap.effortConfigurable)
    }

    @Test
    fun gpt5ProAcceptsOnlyHigh() {
        val cap = DirectProviderReasoningKnowledge.fromModelId("gpt-5-pro", ReasoningProviderPath.OPENAI)!!
        assertEquals(listOf(ReasoningEffort.HIGH), cap.supportedEfforts)
        assertTrue(cap.supports(ReasoningEffort.HIGH))
        assertFalse(cap.supports(ReasoningEffort.MEDIUM))
    }

    @Test
    fun deepSeekReasonerReturnsVisibleReasoningWithoutEffortControl() {
        for (id in listOf("deepseek-reasoner", "deepseek/deepseek-r1", "deepseek-r1")) {
            val cap = DirectProviderReasoningKnowledge.fromModelId(id, ReasoningProviderPath.DEEPSEEK)
            assertTrue("expected reasoning for $id", cap != null && cap.isReasoningCapable)
            assertFalse(cap!!.effortConfigurable)
            assertTrue(cap.canReturnVisibleReasoning)
            assertFalse(cap.canDisableReasoning)
        }
    }

    @Test
    fun adapterKnowledgeMarksMandatoryReasoningAsFixedButKeepsDisableableFamiliesUnfixed() {
        // Claude 5: no ladder, cannot disable → genuinely fixed.
        val claude = DirectProviderReasoningKnowledge.fromModelId(
            "claude-opus-5", ReasoningProviderPath.ANTHROPIC_OPENAI_COMPATIBLE
        )!!
        assertTrue(claude.reasoningMandatory)
        assertTrue(claude.isFixedReasoning)

        // DeepSeek reasoner: no ladder, cannot disable → fixed.
        val deepseek = DirectProviderReasoningKnowledge.fromModelId(
            "deepseek-reasoner", ReasoningProviderPath.DEEPSEEK
        )!!
        assertTrue(deepseek.reasoningMandatory)
        assertTrue(deepseek.isFixedReasoning)

        // gpt-5 line: mandatory (no Off) but has an effort ladder, so it is not
        // "Fixed" — the Thinking control still shows.
        val gpt5 = DirectProviderReasoningKnowledge.fromModelId("gpt-5", ReasoningProviderPath.OPENAI)!!
        assertTrue(gpt5.reasoningMandatory)
        assertFalse(gpt5.isFixedReasoning)

        // Gemini 2.5 Flash can disable reasoning → not mandatory, not fixed.
        val flash = DirectProviderReasoningKnowledge.fromModelId(
            "gemini-2.5-flash", ReasoningProviderPath.GEMINI_OPENAI_COMPATIBLE
        )!!
        assertTrue(flash.canDisableReasoning)
        assertFalse(flash.reasoningMandatory)
        assertFalse(flash.isFixedReasoning)
    }

    @Test
    fun nonReasoningIdsAreNotClassifiedHere() {
        // deepseek-chat (V3) is not a reasoner; plain chat models are unknown to
        // this tier and must fall through (null), never to a false "absent".
        assertNull(DirectProviderReasoningKnowledge.fromModelId("deepseek-chat", ReasoningProviderPath.DEEPSEEK))
        assertNull(DirectProviderReasoningKnowledge.fromModelId("gpt-4o", ReasoningProviderPath.OPENAI))
        assertNull(DirectProviderReasoningKnowledge.fromModelId("claude-3.5-sonnet", ReasoningProviderPath.ANTHROPIC_OPENAI_COMPATIBLE))
        assertNull(DirectProviderReasoningKnowledge.fromModelId("", ReasoningProviderPath.OPENAI))
        assertNull(DirectProviderReasoningKnowledge.fromModelId(null, ReasoningProviderPath.OPENAI))
    }

    @Test
    fun genericSubstringsDoNotTriggerThisTier() {
        // "pro" / "thinking" / "deep" as loose substrings must not classify here
        // (§7.7 forbids generic-substring authority). "gpt-4-pro-preview" has no
        // gpt-5 / o-series identity and no deepseek-reasoner identity.
        assertNull(DirectProviderReasoningKnowledge.fromModelId("some-thinking-model", ReasoningProviderPath.GENERIC_OPENAI_COMPATIBLE))
        assertNull(DirectProviderReasoningKnowledge.fromModelId("deepthink-42", ReasoningProviderPath.GENERIC_OPENAI_COMPATIBLE))
    }

    @Test
    fun officialGeminiAndAnthropicPathsUseProviderAdapters() {
        val gemini = DirectProviderReasoningKnowledge.fromModelId(
            "gemini-3.1-pro",
            ReasoningProviderPath.GEMINI_OPENAI_COMPATIBLE
        )!!
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            gemini.supportedEfforts
        )
        val claude = DirectProviderReasoningKnowledge.fromModelId(
            "claude-opus-5",
            ReasoningProviderPath.ANTHROPIC_OPENAI_COMPATIBLE
        )!!
        assertTrue(claude.isReasoningCapable)
        assertFalse(claude.effortConfigurable)
    }
}
