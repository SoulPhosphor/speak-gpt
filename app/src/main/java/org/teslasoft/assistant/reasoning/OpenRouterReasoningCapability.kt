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

    /** Fallback ladder for a `reasoning`-capable model that publishes no
     *  explicit effort list. Not authoritative — the learning layer may widen it. */
    private val OPENROUTER_EFFORTS = listOf(
        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH
    )

    /** Presentation order for a published effort ladder, least → most. */
    private val CANONICAL_ORDER = listOf(
        ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX
    )

    /**
     * Capability for one OpenRouter catalog entry, or null when the entry's
     * metadata does not mention reasoning at all (caller falls through). Never
     * throws: any malformed entry yields null.
     *
     * The structured `reasoning` object is authoritative when present: its
     * `supported_efforts` becomes the exact offered ladder (so a model that
     * lists max/high/low is never offered medium, and its max is never dropped),
     * and `mandatory` decides whether reasoning can be turned off. Only when
     * there is no `reasoning` object does this fall back to the coarse
     * `supported_parameters` markers, whose ladder the learning layer may widen.
     */
    fun fromModelEntry(entry: JsonObject?): ReasoningCapability? {
        entry ?: return null

        val reasoning = entry.get("reasoning")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        if (reasoning != null) {
            val mandatory = reasoning.get("mandatory")
                ?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean ?: false
            val efforts = parseEfforts(reasoning)
            return if (efforts.isNotEmpty()) {
                ReasoningCapability(
                    support = ReasoningSupport.KNOWN,
                    // A single published level is a fixed level, not a choice.
                    effortConfigurable = efforts.size >= 2,
                    supportedEfforts = efforts,
                    canDisableReasoning = !mandatory,
                    canReturnVisibleReasoning = true,
                    tokenBudgetSupported = true,
                    source = CapabilitySource.PROVIDER_METADATA,
                    effortsAuthoritative = true
                )
            } else {
                // Reasons per the object, but publishes no effort ladder: known
                // reasoning, no selectable level (the learning layer leaves a
                // non-configurable path alone, so it reads as Fixed).
                ReasoningCapability(
                    support = ReasoningSupport.KNOWN,
                    effortConfigurable = false,
                    supportedEfforts = emptyList(),
                    canDisableReasoning = !mandatory,
                    canReturnVisibleReasoning = true,
                    tokenBudgetSupported = false,
                    source = CapabilitySource.PROVIDER_METADATA,
                    effortsAuthoritative = false
                )
            }
        }

        val params = supportedParameters(entry)
        if (params.isEmpty()) return null

        val hasReasoningObject = params.contains("reasoning")
        val hasIncludeReasoning = params.contains("include_reasoning")
        if (!hasReasoningObject && !hasIncludeReasoning) return null

        return if (hasReasoningObject) {
            // Full reasoning control with no published ladder: a fallback
            // low/medium/high that the learning layer may widen and prune.
            ReasoningCapability(
                support = ReasoningSupport.KNOWN,
                effortConfigurable = true,
                supportedEfforts = OPENROUTER_EFFORTS,
                canDisableReasoning = true,
                canReturnVisibleReasoning = true,
                tokenBudgetSupported = true, // OpenRouter's reasoning.max_tokens
                source = CapabilitySource.PROVIDER_METADATA,
                effortsAuthoritative = false
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
                source = CapabilitySource.PROVIDER_METADATA,
                effortsAuthoritative = false
            )
        }
    }

    /** The explicit levels from a `reasoning.supported_efforts` array, canonical
     *  order, ignoring unknown values and the `none` (off) signal. */
    private fun parseEfforts(reasoning: JsonObject): List<ReasoningEffort> {
        val array = reasoning.get("supported_efforts")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return emptyList()
        val found = LinkedHashSet<ReasoningEffort>()
        for (el in array) {
            if (el == null || el.isJsonNull || !el.isJsonPrimitive) continue
            ReasoningEffort.fromSerialized(el.asString)
                ?.takeIf { it.isExplicitLevel }
                ?.let { found.add(it) }
        }
        return CANONICAL_ORDER.filter { it in found }
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
