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

import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * One-line, human-readable summary of the provider-routing decision for a
 * single request, for the Response Lifecycle log — so the resolved routing and
 * whether a provider object was attached are visible on the app's own
 * diagnostic surface, not just Logcat. Pure and unit-tested.
 */
object ProviderRoutingDiagnostics {

    /**
     * [attachmentStatus] is the caller's TRUTHFUL account of what actually
     * happened to the outgoing request — e.g. "provider object attached" only
     * once the interceptor confirmed it replaced the body, or "attachment
     * requested (…)" when it could not be confirmed. This function never infers
     * attachment from the routing decision; it only renders what it is told.
     */
    fun describe(
        endpointIsOpenRouter: Boolean,
        favorite: FavoriteModelObject?,
        attachmentStatus: String
    ): String {
        if (!endpointIsOpenRouter) return "generic endpoint — not applied"

        val mode = favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC
        val parts = mutableListOf("mode=$mode")
        when (mode) {
            FavoriteModelObject.ROUTING_ONLY ->
                parts.add("provider=${favorite?.selectedProvider?.ifBlank { "(none)" } ?: "(none)"}")
            FavoriteModelObject.ROUTING_PREFERRED -> {
                val order = favorite?.providerOrder ?: emptyList()
                parts.add("order=[${order.joinToString(",")}]")
                parts.add("fallbacks=${favorite?.allowFallbacks ?: true}")
            }
        }
        val excluded = favorite?.ignoredProviders ?: emptyList()
        if (excluded.isNotEmpty()) parts.add("excluded=[${excluded.joinToString(",")}]")

        parts.add(attachmentStatus)
        return parts.joinToString("; ")
    }
}
