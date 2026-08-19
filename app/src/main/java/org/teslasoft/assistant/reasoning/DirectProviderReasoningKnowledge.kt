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

/**
 * Direct-provider reasoning knowledge (chat-redesign-plan.md §7.7 tier 2 —
 * "provider-adapter knowledge second").
 *
 * A generic (non-OpenRouter) endpoint is just an OpenAI-compatible base URL and
 * key; it exposes no `supported_parameters` metadata, so the model's own
 * official identity is the signal. This classifier recognizes only well-known,
 * officially reasoning-capable model FAMILIES by their stable provider id
 * patterns — OpenAI's o-series / GPT-5 line and DeepSeek's reasoner. That is
 * "current official provider model capability information", not the forbidden
 * authoritative name list (§7.7 bans a name list as the PRIMARY classifier and
 * bans generic substrings like `thinking`/`pro`/`r1`; a curated official-family
 * map sitting BELOW structured metadata is exactly tier 2).
 *
 * Anything not matched here returns null so the resolver falls through to the
 * weaker variant-marker tier and then to Unknown — never to a false "absent".
 */
object DirectProviderReasoningKnowledge {

    /** The stable common OpenAI effort ladder for the reasoning families. */
    private val OPENAI_EFFORTS = listOf(
        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH
    )

    /**
     * Capability for a model id on a direct provider path, or null when this
     * tier does not recognize the model. [modelId] is matched case-insensitively
     * against official id patterns only.
     */
    fun fromModelId(modelId: String?): ReasoningCapability? {
        val id = modelId?.trim()?.lowercase() ?: return null
        if (id.isEmpty()) return null

        deepSeekReasoner(id)?.let { return it }
        openAiReasoning(id)?.let { return it }
        return null
    }

    /**
     * DeepSeek's reasoner (R1 line): returns `reasoning_content` on the delta,
     * reasoning is inherent and not effort-configurable, and cannot be turned
     * off. `deepseek-chat` (V3) is intentionally NOT matched — it does not
     * reason. Matching is anchored on the official `reasoner`/`-r1` markers, not
     * a bare `deepseek` substring.
     */
    private fun deepSeekReasoner(id: String): ReasoningCapability? {
        val isDeepSeek = id.contains("deepseek")
        val isReasoner = id.contains("reasoner") || id.contains("-r1") || id.endsWith("r1") || id.contains("/r1")
        if (!(isDeepSeek && isReasoner)) return null
        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            supportedEfforts = emptyList(),
            canDisableReasoning = false,
            canReturnVisibleReasoning = true,
            tokenBudgetSupported = false,
            source = CapabilitySource.PROVIDER_ADAPTER
        )
    }

    /**
     * OpenAI's reasoning families (o1/o3/o4-mini and the GPT-5 line). They
     * accept `reasoning_effort` but, over the Chat Completions API this app
     * uses, do NOT return visible chain-of-thought — so Show Reasoning yields
     * no Thinking row there, which §7.8 says is not a failure. Reasoning is
     * mandatory (no Off). gpt-5-pro is the one documented family member that
     * accepts only `high`, so it is offered only `high` to honor §7.9's "never
     * present an effort the path is known not to support".
     */
    private fun openAiReasoning(id: String): ReasoningCapability? {
        val isOSeries = OSERIES.any { id == it || id.startsWith("$it-") || id.contains("/$it") }
        val isGpt5 = id.contains("gpt-5")
        if (!isOSeries && !isGpt5) return null

        val efforts = if (id.contains("gpt-5-pro")) listOf(ReasoningEffort.HIGH) else OPENAI_EFFORTS
        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = true,
            supportedEfforts = efforts,
            canDisableReasoning = false,
            canReturnVisibleReasoning = false,
            tokenBudgetSupported = false,
            source = CapabilitySource.PROVIDER_ADAPTER
        )
    }

    /** Official o-series reasoning ids (exact stems; variants add suffixes). */
    private val OSERIES = listOf("o1", "o1-mini", "o1-preview", "o3", "o3-mini", "o4-mini")
}
