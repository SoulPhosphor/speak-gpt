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
 * The §7.7 confidence ladder in one place: given whatever is known about a
 * model/provider path, produce a single [ReasoningCapability], preferring the
 * strongest available source and never converting uncertainty to "absent".
 *
 * Order, strongest first:
 *  1. **Provider/model metadata** — a catalog entry's dedicated reasoning
 *     object, with `supported_parameters` as the compatibility fallback
 *     ([OpenRouterReasoningCapability]).
 *  2. **Provider-adapter knowledge** — official reasoning families recognized
 *     by stable id patterns ([DirectProviderReasoningKnowledge]).
 *  3. **Strong variant marker** — a provider-defined reasoning variant suffix
 *     ([ReasoningVariantMarkers]).
 *  4. **Unknown** — [ReasoningCapability.UNKNOWN].
 *
 * Capability is keyed to the effective endpoint/provider/model path, not just
 * the visible model name (§7.9): the caller passes the model id AND, when it
 * has one, that model's catalog entry, so two providers serving the same id can
 * resolve differently. This function is pure and side-effect free so it is
 * fully unit-testable and safe to call from a request-prep hot path.
 */
object ReasoningCapabilityResolver {

    /**
     * @param modelId the effective model id for this path.
     * @param modelCatalogEntry the model's catalog entry when the active
     *   endpoint exposed one; null otherwise.
     * @param providerHint optional provider label from the endpoint profile.
     * @param endpointHost optional endpoint base URL.
     */
    fun resolve(
        modelId: String?,
        modelCatalogEntry: JsonObject? = null,
        providerHint: String? = null,
        endpointHost: String? = null
    ): ReasoningCapability {
        val requestFormat = ReasoningRequestFormat.forEndpoint(providerHint, endpointHost)
        OpenRouterReasoningCapability.fromModelEntry(modelCatalogEntry, requestFormat)?.let { return it }
        DirectProviderReasoningKnowledge.fromModelId(modelId, providerHint, endpointHost)?.let { return it }
        ReasoningVariantMarkers.fromModelId(modelId, requestFormat)?.let { return it }
        return ReasoningCapability.UNKNOWN
    }
}
