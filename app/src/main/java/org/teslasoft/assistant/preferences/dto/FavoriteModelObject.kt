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

package org.teslasoft.assistant.preferences.dto

/**
 * A favorited model, scoped to the endpoint profile it was starred under.
 *
 * [routingType] is the preferred model-provider routing for this favorite
 * (OpenRouter): the memory of how the user wants this model routed rides on
 * the favorite itself, so removing the favorite also removes that memory
 * (owner ruling — favorites are the housekeeping unit for provider choices).
 * A model that is not a favorite keeps no routing memory; it reads back as
 * [ROUTING_AUTOMATIC]. Kept at the END of the constructor so existing
 * positional callers stay valid.
 */
class FavoriteModelObject(
    var modelId: String,
    var endpointId: String,
    var routingType: String = ROUTING_AUTOMATIC,
    /** Provider slug chosen in Only mode (empty = none chosen). */
    var selectedProvider: String = "",
    /** Preferred mode: whether the API may fall back to other providers when
     *  the preferred ones fail. Defaults on. */
    var allowFallbacks: Boolean = true,
    /** Preferred mode: provider slugs in priority order (first = most
     *  preferred; the owner's "lower is less preferred"). Slugs currently
     *  unavailable for the model STAY stored in position (they return when the
     *  provider does) but must be filtered out of the API order payload at
     *  request time. */
    var providerOrder: List<String> = emptyList(),
    /** Provider slugs the user marked Ignore in the chart. Applies in
     *  automatic and preferred modes; not sent in Only mode. Unavailable
     *  slugs stay stored but must be filtered out of the API ignore payload
     *  at request time. */
    var ignoredProviders: List<String> = emptyList(),
    /** This favorite's saved default reasoning effort (chat-redesign-plan.md
     *  §7.4/§7.9). Stored independently from provider routing — a model may
     *  support routing, reasoning, both, or neither. Default [REASONING_AUTO]:
     *  send no explicit effort and let the provider/model default apply. A
     *  favorite saved before reasoning existed reads back as [REASONING_AUTO].
     *  Kept near the END of the constructor so existing positional callers stay
     *  valid. */
    var reasoningEffort: String = REASONING_AUTO,
    /** This favorite's saved Show Reasoning preference (§7.4/§7.9). Controls
     *  whether available provider-supplied reasoning is requested/returned for
     *  display; it never disables the model's reasoning. Default On (true). */
    var showReasoning: Boolean = true
) {
    companion object {
        /** Provider chooses each turn; no specific provider is remembered. The
         *  default for every model, favorite or not. */
        const val ROUTING_AUTOMATIC = "automatic"

        /** Use the chosen provider when possible, automatic backups still on. */
        const val ROUTING_PREFERRED = "preferred"

        /** Only the chosen provider; no automatic fallback. */
        const val ROUTING_ONLY = "only"

        /** Default saved reasoning effort: send no explicit effort and allow
         *  the provider/model default to apply (§7.9). Matches
         *  ReasoningEffort.AUTO.serialized; kept here as a string constant so
         *  this DTO carries no dependency on the reasoning package. */
        const val REASONING_AUTO = "auto"
    }
}
