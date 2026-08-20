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

    /** The stable common OpenAI effort ladder for the older o-series. */
    private val OPENAI_EFFORTS = listOf(
        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH
    )
    private val GPT5_CLASSIC_EFFORTS = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH
    )
    private val GPT5_MODERN_EFFORTS = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH
    )
    private val GPT5_MAX_EFFORTS = GPT5_MODERN_EFFORTS + ReasoningEffort.MAX

    private data class EffortProfile(
        val efforts: List<ReasoningEffort>,
        val canDisable: Boolean
    )

    /**
     * Capability for a model id on a direct provider path, or null when this
     * tier does not recognize the model. [modelId] is matched case-insensitively
     * against official id patterns only.
     */
    fun fromModelId(
        modelId: String?,
        providerHint: String? = null,
        endpointHost: String? = null,
        requestFormat: ReasoningRequestFormat =
            ReasoningRequestFormat.forEndpoint(providerHint, endpointHost)
    ): ReasoningCapability? {
        val id = modelId?.trim()?.lowercase() ?: return null
        if (id.isEmpty()) return null

        deepSeekReasoner(id, requestFormat)?.let { return it }
        openAiReasoning(id, requestFormat)?.let { return it }
        anthropicReasoning(id, providerHint, endpointHost, requestFormat)?.let { return it }
        geminiReasoning(id, providerHint, endpointHost, requestFormat)?.let { return it }
        return null
    }

    /**
     * DeepSeek's reasoner (R1 line): returns `reasoning_content` on the delta,
     * reasoning is inherent and not effort-configurable, and cannot be turned
     * off. `deepseek-chat` (V3) is intentionally NOT matched — it does not
     * reason. Matching is anchored on the official `reasoner`/`-r1` markers, not
     * a bare `deepseek` substring.
     */
    private fun deepSeekReasoner(
        id: String,
        requestFormat: ReasoningRequestFormat
    ): ReasoningCapability? {
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
            source = CapabilitySource.PROVIDER_ADAPTER,
            requestFormat = requestFormat,
            continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
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
    private fun openAiReasoning(
        id: String,
        requestFormat: ReasoningRequestFormat
    ): ReasoningCapability? {
        val model = id.substringAfterLast('/')
        val isOSeries = OSERIES.any { model == it || model.startsWith("$it-") }
        val profile = when {
            model == "gpt-5-pro" || model.startsWith("gpt-5-pro-") ->
                EffortProfile(listOf(ReasoningEffort.HIGH), canDisable = false)
            model.startsWith("gpt-5.6-pro") -> null
            model == "gpt-5.6" ||
                (model.startsWith("gpt-5.6-") && !model.contains("-pro")) ->
                EffortProfile(GPT5_MAX_EFFORTS, canDisable = true)
            model.startsWith("gpt-5.5-pro") || model.startsWith("gpt-5.4-pro") ->
                EffortProfile(
                    listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH),
                    canDisable = false
                )
            model.startsWith("gpt-5.5") || model.startsWith("gpt-5.4") ->
                EffortProfile(GPT5_MODERN_EFFORTS, canDisable = true)
            model.startsWith("gpt-5.2-pro") ->
                EffortProfile(
                    listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH),
                    canDisable = false
                )
            model.startsWith("gpt-5.2-codex") || model.startsWith("gpt-5.1-codex") ||
                model.startsWith("gpt-5.3-codex") ->
                EffortProfile(GPT5_MODERN_EFFORTS, canDisable = false)
            model.startsWith("gpt-5.2") ->
                EffortProfile(GPT5_MODERN_EFFORTS, canDisable = true)
            model.startsWith("gpt-5.1") ->
                EffortProfile(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                    canDisable = true
                )
            model == "gpt-5" || model.startsWith("gpt-5-") ->
                EffortProfile(GPT5_CLASSIC_EFFORTS, canDisable = false)
            isOSeries -> EffortProfile(OPENAI_EFFORTS, canDisable = false)
            else -> null
        }
        if (profile == null) return null

        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = true,
            supportedEfforts = profile.efforts,
            canDisableReasoning = profile.canDisable,
            canReturnVisibleReasoning = false,
            tokenBudgetSupported = false,
            source = CapabilitySource.PROVIDER_ADAPTER,
            requestFormat = requestFormat,
            continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
        )
    }

    /** Anthropic's reasoning models are recognized only by their official
     * Claude family ids. The current SpeakGPT direct path is OpenAI-compatible;
     * it does not send Anthropic's native thinking configuration or parse native
     * thinking blocks, so this fallback must not advertise those controls. */
    private fun anthropicReasoning(
        id: String,
        providerHint: String?,
        endpointHost: String?,
        requestFormat: ReasoningRequestFormat
    ): ReasoningCapability? {
        val signal = listOf(id, providerHint.orEmpty(), endpointHost.orEmpty())
            .joinToString(" ")
            .lowercase()
        val isAnthropic = signal.contains("anthropic") || id.startsWith("claude-")
        if (!isAnthropic) return null

        val isReasoningFamily = listOf(
            "claude-3-7", "claude-3.7", "claude-4", "claude-opus-4",
            "claude-sonnet-4", "claude-haiku-4", "claude-5",
            "claude-opus-5", "claude-sonnet-5"
        ).any(id::contains)
        if (!isReasoningFamily) return null

        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            supportedEfforts = emptyList(),
            canDisableReasoning = false,
            canReturnVisibleReasoning = requestFormat == ReasoningRequestFormat.OPENROUTER,
            tokenBudgetSupported = false,
            source = CapabilitySource.PROVIDER_ADAPTER,
            requestFormat = requestFormat,
            continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
        )
    }

    /** Google exposes thinking on selected Gemini 2.5 and 3 families. The
     * app's OpenAI-compatible boundary can send the documented effort field,
     * but it does not send Google's separate include-thoughts request field or
     * parse Google-native thought summaries, so visible reasoning is only
     * claimed for an OpenRouter path that translates it. */
    private fun geminiReasoning(
        id: String,
        providerHint: String?,
        endpointHost: String?,
        requestFormat: ReasoningRequestFormat
    ): ReasoningCapability? {
        val signal = listOf(id, providerHint.orEmpty(), endpointHost.orEmpty())
            .joinToString(" ")
            .lowercase()
        val isGemini = signal.contains("gemini") || signal.contains("google")
        val model = id.substringAfterLast('/')
        if (!isGemini) return null

        val profile = when {
            model.startsWith("gemini-3.1-flash-lite-image") ->
                EffortProfile(listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH), canDisable = false)
            model.startsWith("gemini-3.1-pro") ->
                EffortProfile(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                    canDisable = false
                )
            model.startsWith("gemini-3.1-flash-lite") ||
                model.startsWith("gemini-3.6-flash") ||
                model.startsWith("gemini-3.5-flash") ||
                model.startsWith("gemini-3-flash") ->
                EffortProfile(
                    listOf(
                        ReasoningEffort.MINIMAL,
                        ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH
                    ),
                    canDisable = false
                )
            model.startsWith("gemini-3-pro") ->
                EffortProfile(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
                    canDisable = false
                )
            model.startsWith("gemini-2.5-pro") ->
                EffortProfile(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                    canDisable = false
                )
            model.startsWith("gemini-2.5-flash") || model.startsWith("gemini-2.5-flash-lite") ->
                EffortProfile(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                    canDisable = true
                )
            else -> null
        } ?: return null

        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = true,
            supportedEfforts = profile.efforts,
            canDisableReasoning = profile.canDisable,
            canReturnVisibleReasoning = requestFormat == ReasoningRequestFormat.OPENROUTER,
            tokenBudgetSupported = false,
            source = CapabilitySource.PROVIDER_ADAPTER,
            requestFormat = requestFormat,
            continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
        )
    }

    /** Official o-series reasoning ids (exact stems; variants add suffixes). */
    private val OSERIES = listOf("o1", "o1-mini", "o1-preview", "o3", "o3-mini", "o4-mini")
}
