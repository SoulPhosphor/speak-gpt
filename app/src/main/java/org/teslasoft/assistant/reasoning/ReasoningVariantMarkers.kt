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
 * Lowest-confidence reasoning evidence: an unambiguous provider-DEFINED variant
 * marker (chat-redesign-plan.md §7.7 tier 3).
 *
 * The plan is explicit that generic substrings such as `thinking`, `reasoning`,
 * `r1`, `deep`, or `pro` must never become the authoritative classifier. This
 * tier therefore matches only a marker that a provider attaches to denote a
 * reasoning SKU as a distinct addressable variant — currently OpenRouter's
 * `:thinking` variant suffix (e.g. `anthropic/claude-3.7-sonnet:thinking`). The
 * suffix is a structural variant selector, not a word that merely appears in a
 * display name, so it is safe as weak evidence when no stronger source spoke.
 *
 * It reports only that the model reasons and can return visible reasoning; it
 * deliberately does NOT claim effort is configurable, because a marker alone
 * does not establish an effort ladder. Returns null for everything else so the
 * resolver falls through to Unknown rather than inventing capability.
 */
object ReasoningVariantMarkers {

    fun fromModelId(
        modelId: String?,
        requestFormat: ReasoningRequestFormat = ReasoningRequestFormat.OPENAI_COMPATIBLE
    ): ReasoningCapability? {
        val id = modelId?.trim()?.lowercase() ?: return null
        if (id.isEmpty()) return null

        // A provider variant suffix, e.g. "…:thinking". Only the suffix form
        // counts — a bare "thinking" elsewhere in the id is not a marker.
        val hasThinkingVariant = id.endsWith(":thinking") || id.contains(":thinking:")
        if (!hasThinkingVariant) return null

        return ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            supportedEfforts = emptyList(),
            canDisableReasoning = false,
            canReturnVisibleReasoning = requestFormat == ReasoningRequestFormat.OPENROUTER,
            tokenBudgetSupported = false,
            source = CapabilitySource.VARIANT_MARKER,
            requestFormat = requestFormat,
            continuationStateSupported = requestFormat == ReasoningRequestFormat.OPENROUTER
        )
    }
}
