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
 * OpenRouter's structured `reasoning` object is the primary authority. The
 * older `supported_parameters` array remains a coarse fallback:
 *
 * - `reasoning` — the model accepts the unified request object, but without a
 *   structured `supported_efforts` list SpeakGPT does not invent a ladder.
 * - `include_reasoning` — the legacy flag: reasoning tokens can be returned,
 *   but there is no effort control.
 *
 * This is deliberately metadata-only: it never inspects the model name. A
 * present `supported_parameters` array with neither marker is authoritative
 * negative evidence for that catalog entry; a missing/malformed capability
 * shape remains Unknown.
 */
object OpenRouterReasoningCapability {

    /**
     * Capability for one OpenRouter catalog entry, or null when the entry's
     * metadata cannot classify reasoning at all. Never throws: malformed
     * capability shapes yield null.
     *
     * The structured `reasoning` object is authoritative when present: its
     * `supported_efforts` becomes the exact offered ladder in provider order,
     * and an explicitly published `mandatory` value decides whether reasoning
     * can be turned off. Omitted/null effort metadata never becomes a guessed
     * Minimal/Low/Medium/High/Extra High/Max ladder.
     */
    fun fromModelEntry(entry: JsonObject?): ReasoningCapability? {
        entry ?: return null

        val reasoning = entry.get("reasoning")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        if (reasoning != null) {
            val mandatory = booleanOrNull(reasoning, "mandatory")
            val efforts = parseEfforts(reasoning)
            val publishedOff = supportsOff(reasoning)
            val exactEffortList = reasoning.get("supported_efforts")
                ?.takeUnless { it.isJsonNull }
                ?.isJsonArray == true
            return ReasoningCapability(
                support = ReasoningSupport.KNOWN,
                effortConfigurable = efforts.isNotEmpty(),
                supportedEfforts = efforts,
                // Do not infer disableability when `mandatory` was omitted.
                canDisableReasoning = mandatory == false || (mandatory != true && publishedOff),
                canReturnVisibleReasoning = true,
                tokenBudgetSupported = booleanOrNull(reasoning, "supports_max_tokens") == true,
                source = CapabilitySource.PROVIDER_METADATA,
                effortsAuthoritative = exactEffortList,
                // "Fixed" is justified only by a published, positive mandatory
                // flag. An omitted `mandatory` stays unknown-config, never fixed.
                reasoningMandatory = mandatory == true
            )
        }

        val params = supportedParameters(entry)
        if (params == null) return null

        val hasReasoningObject = params.contains("reasoning")
        val hasIncludeReasoning = params.contains("include_reasoning")
        if (!hasReasoningObject && !hasIncludeReasoning) {
            return ReasoningCapability.ABSENT.copy(source = CapabilitySource.PROVIDER_METADATA)
        }

        return if (hasReasoningObject) {
            // Coarse request-object support proves reasoning, not which effort
            // strings this exact model accepts or whether reasoning is optional.
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

    /** Explicit levels from `reasoning.supported_efforts`, preserving provider
     *  order and membership while ignoring unknown values and `none`. */
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
        return found.toList()
    }

    private fun supportsOff(reasoning: JsonObject): Boolean {
        val array = reasoning.get("supported_efforts")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray ?: return false
        return array.any { element ->
            val value = element?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.lowercase()
            value == "none" || value == ReasoningEffort.OFF.serialized
        }
    }

    /** Lower-cased, trimmed `supported_parameters` values, or null when the
     *  field is missing/malformed and therefore cannot classify the model. */
    private fun supportedParameters(entry: JsonObject): Set<String>? {
        val array = entry.get("supported_parameters")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return null
        val out = LinkedHashSet<String>()
        for (el in array) {
            if (el != null && !el.isJsonNull && el.isJsonPrimitive) {
                el.asString.trim().lowercase().takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
        }
        return out
    }

    private fun booleanOrNull(obj: JsonObject, name: String): Boolean? = try {
        obj.get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asBoolean
    } catch (_: Exception) {
        null
    }
}
