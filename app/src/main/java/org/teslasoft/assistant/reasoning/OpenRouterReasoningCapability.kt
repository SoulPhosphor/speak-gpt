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

/**
 * Reads reasoning capability from an OpenRouter `/models` catalog entry
 * (chat-redesign-plan.md §7.7 tier 1 — "provider/model metadata first").
 *
 * OpenRouter advertises per-model capability in a dedicated `reasoning` object
 * in current catalogs. Older catalogs use a `supported_parameters` array; two
 * markers matter there:
 *
 * - `reasoning` — the model accepts the unified `reasoning` request object.
 * - `include_reasoning` — the legacy flag: reasoning tokens can be returned,
 *   but there is no effort control.
 *
 * This is deliberately metadata-only: it never inspects the model NAME. A
 * model with neither marker returns null so the resolver can fall through to
 * the next confidence tier rather than this parser guessing "no reasoning"
 * from silence (§7.7 #4 — unknown stays unknown).
 */
object OpenRouterReasoningCapability {

    /** Current gateway ladder, used only when the catalog explicitly says the
     * supported effort list is null (meaning all gateway values are accepted). */
    private val ALL_GATEWAY_EFFORTS = listOf(
        ReasoningEffort.MAX,
        ReasoningEffort.XHIGH,
        ReasoningEffort.HIGH,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.LOW,
        ReasoningEffort.MINIMAL
    )

    /** Compatibility ladder for older catalogs with only the legacy
     * `reasoning` supported-parameter marker. */
    private val LEGACY_EFFORTS = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH
    )

    /**
     * Capability for one catalog entry, or null when the entry's metadata does
     * not mention reasoning. [requestFormat] lets the same normalized
     * capability be learned from a non-OpenRouter catalog as well.
     */
    fun fromModelEntry(
        entry: JsonObject?,
        requestFormat: ReasoningRequestFormat = ReasoningRequestFormat.OPENROUTER
    ): ReasoningCapability? {
        entry ?: return null
        return try {
            val richReasoning = entry.get("reasoning")
                ?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
            if (richReasoning != null) {
                fromRichReasoningMetadata(entry, richReasoning, requestFormat)
            } else {
                fromLegacyParameters(entry, requestFormat)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Parse the current catalog contract. Missing effort metadata means that
     * this model exposes no named levels; when it is not mandatory, the UI may
     * still offer the real Auto/Off control without inventing levels. A JSON
     * null effort list explicitly means every current gateway effort is
     * accepted. */
    private fun fromRichReasoningMetadata(
        entry: JsonObject,
        reasoning: JsonObject,
        requestFormat: ReasoningRequestFormat
    ): ReasoningCapability {
        val effortElement = reasoning.get("supported_efforts")
        val rawEfforts = effortElement
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                if (!element.isJsonPrimitive) null else element.asString.trim().lowercase()
            }
            .orEmpty()
        val efforts = when {
            effortElement == null -> emptyList()
            effortElement.isJsonNull -> ALL_GATEWAY_EFFORTS
            effortElement.isJsonArray -> rawEfforts.mapNotNull { ReasoningEffort.fromSerialized(it) }
                .filter { it.isExplicitLevel }
            else -> emptyList()
        }
        // `none` is an On/Off-only capability, not an effort level. Keeping the
        // selector enabled in that case lets the UI expose Auto/Off without
        // inventing Low/Medium/High.
        val mandatory = booleanOrNull(reasoning, "mandatory") == true
        val effortConfigurable = when {
            effortElement == null -> !mandatory
            else -> efforts.isNotEmpty() || rawEfforts.any { it == "none" || it == "off" }
        }
        val canReturnVisible = booleanOrNull(reasoning, "can_return_visible")
            ?: booleanOrNull(entry, "can_return_visible")
            ?: DirectProviderReasoningKnowledge.fromModelId(
                stringOrNull(entry, "id")
            )?.canReturnVisibleReasoning
            ?: true

        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = effortConfigurable,
            supportedEfforts = efforts,
            canDisableReasoning = !mandatory,
            canReturnVisibleReasoning = canReturnVisible,
            tokenBudgetSupported = booleanOrNull(reasoning, "supports_max_tokens") == true,
            source = CapabilitySource.PROVIDER_METADATA,
            requestFormat = requestFormat,
            continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
        )
    }

    /** Compatibility parser for older catalog entries with no `reasoning`
     * object. It deliberately does not infer token-budget support from the
     * ordinary `max_tokens` completion parameter. */
    private fun fromLegacyParameters(
        entry: JsonObject,
        requestFormat: ReasoningRequestFormat
    ): ReasoningCapability? {
        val params = supportedParameters(entry)
        val hasReasoningObject = params.contains("reasoning")
        val hasIncludeReasoning = params.contains("include_reasoning")
        if (!hasReasoningObject && !hasIncludeReasoning) return null

        return if (hasReasoningObject) {
            ReasoningCapability(
                support = ReasoningSupport.KNOWN,
                effortConfigurable = true,
                supportedEfforts = LEGACY_EFFORTS,
                canDisableReasoning = true,
                canReturnVisibleReasoning = true,
                tokenBudgetSupported = false,
                source = CapabilitySource.PROVIDER_METADATA,
                requestFormat = requestFormat,
                continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
            )
        } else {
            ReasoningCapability(
                support = ReasoningSupport.KNOWN,
                effortConfigurable = false,
                supportedEfforts = emptyList(),
                canDisableReasoning = false,
                canReturnVisibleReasoning = true,
                tokenBudgetSupported = false,
                source = CapabilitySource.PROVIDER_METADATA,
                requestFormat = requestFormat,
                continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
            )
        }
    }

    /** Lower-cased, trimmed `supported_parameters` values, or empty on any
     * shape mismatch. */
    private fun supportedParameters(entry: JsonObject): Set<String> {
        val array = entry.get("supported_parameters")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (el in array) {
            if (el != null && !el.isJsonNull && el.isJsonPrimitive) {
                el.asString.trim().lowercase().takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
        }
        return out
    }

    private fun booleanOrNull(obj: JsonObject, name: String): Boolean? = try {
        obj.get(name)
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean
    } catch (_: Exception) {
        null
    }

    private fun stringOrNull(obj: JsonObject, name: String): String? = try {
        obj.get(name)
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
