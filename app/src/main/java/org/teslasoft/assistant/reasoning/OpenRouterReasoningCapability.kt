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
 * OpenRouter advertises per-model capability in a `supported_parameters`
 * array. Two markers matter for reasoning:
 *
 * - `reasoning` — the model accepts the unified `reasoning` request object,
 *   so effort is configurable (OpenRouter documents `low`/`medium`/`high`) and
 *   reasoning can be requested, disabled, or excluded from the response.
 * - `include_reasoning` — the legacy flag: reasoning tokens can be returned,
 *   but there is no effort control.
 *
 * This is deliberately metadata-only: it never inspects the model NAME. A
 * model with neither marker returns null so the resolver can fall through to
 * the next confidence tier rather than this parser guessing "no reasoning"
 * from silence (§7.7 #4 — unknown stays unknown).
 */
object OpenRouterReasoningCapability {

    /** OpenRouter's documented effort ladder for the `reasoning` object. */
    private val OPENROUTER_EFFORTS = listOf(
        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH
    )

    /**
     * Capability for one OpenRouter catalog entry, or null when the entry's
     * metadata does not mention reasoning at all (caller falls through). Never
     * throws: any malformed entry yields null.
     */
    fun fromModelEntry(entry: JsonObject?): ReasoningCapability? {
        entry ?: return null
        val params = supportedParameters(entry)
        if (params.isEmpty()) return null

        val hasReasoningObject = params.contains("reasoning")
        val hasIncludeReasoning = params.contains("include_reasoning")
        if (!hasReasoningObject && !hasIncludeReasoning) return null

        return if (hasReasoningObject) {
            // Full reasoning control: effort ladder, returnable reasoning, and
            // the documented disable signal (`reasoning.enabled = false`).
            ReasoningCapability(
                support = ReasoningSupport.KNOWN,
                effortConfigurable = true,
                supportedEfforts = OPENROUTER_EFFORTS,
                canDisableReasoning = true,
                canReturnVisibleReasoning = true,
                tokenBudgetSupported = true, // OpenRouter's reasoning.max_tokens
                source = CapabilitySource.PROVIDER_METADATA
            )
        } else {
            // include_reasoning only: reasoning can be returned/excluded, but the
            // model exposes no effort control and no thinking-disable signal.
            ReasoningCapability(
                support = ReasoningSupport.KNOWN,
                effortConfigurable = false,
                supportedEfforts = emptyList(),
                canDisableReasoning = false,
                canReturnVisibleReasoning = true,
                tokenBudgetSupported = false,
                source = CapabilitySource.PROVIDER_METADATA
            )
        }
    }

    /** Lower-cased, trimmed `supported_parameters` values, or empty on any
     *  shape mismatch. */
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
}
