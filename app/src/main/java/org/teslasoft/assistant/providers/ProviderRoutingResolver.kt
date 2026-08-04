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

package org.teslasoft.assistant.providers

import com.google.gson.JsonObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * The pure, request-boundary decision used by chat and dedicated background
 * model requests: given the endpoint's routing identity and the model's saved
 * favorite, decide what (if anything) to attach to the outgoing request, or
 * whether to block it.
 *
 * Availability is treated as unknown at request time (the saved lists are sent
 * as-is; OpenRouter's own rejection is surfaced by the normal error path), so
 * this never guesses a provider is offline. It holds no state, so it can never
 * carry one request's routing into another.
 */
object ProviderRoutingResolver {

    /**
     * [providerJson] is the OpenRouter `provider` object to attach, or null when
     * nothing should be attached. [block] is [RoutingBlock.NONE] unless the
     * configuration cannot be satisfied and the request must be blocked before
     * dispatch. The two are mutually exclusive: a block yields no provider JSON.
     */
    data class Resolution(val providerJson: JsonObject?, val block: RoutingBlock)

    private val ALLOWED = Resolution(null, RoutingBlock.NONE)

    fun resolve(endpointIsOpenRouter: Boolean, favorite: FavoriteModelObject?): Resolution {
        // Generic endpoints never serialize an OpenRouter provider object.
        if (!endpointIsOpenRouter) return ALLOWED
        // No saved routing for this model → Automatic; nothing to attach.
        if (favorite == null) return ALLOWED

        val decision = ProviderRoutingEnforcer.decide(favorite, null)
        if (!decision.allowed) return Resolution(null, decision.block)
        return Resolution(ProviderRoutingSerializer.providerObject(decision), RoutingBlock.NONE)
    }
}
