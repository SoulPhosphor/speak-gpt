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
 * Clearly labeled summary of the provider-routing REQUEST for one lifecycle
 * entry. This deliberately says what the app requested, never who actually
 * served the response; that separate fact must come from the API response.
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
        if (!endpointIsOpenRouter) return "Provider Routing: not applicable (direct endpoint)"

        val mode = favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC
        val lines = mutableListOf("Provider Routing Mode: $mode")
        when (mode) {
            FavoriteModelObject.ROUTING_ONLY ->
                lines.add("Requested Model Provider: ${favorite?.selectedProvider?.ifBlank { "(none)" } ?: "(none)"}")
            FavoriteModelObject.ROUTING_PREFERRED -> {
                val order = favorite?.providerOrder ?: emptyList()
                lines.add("Requested Provider Order: ${order.ifEmpty { listOf("(none)") }.joinToString(", ")}")
                lines.add("Fallbacks Allowed: ${favorite?.allowFallbacks ?: true}")
            }
            else -> lines.add("Requested Model Provider: automatic selection")
        }
        val excluded = favorite?.ignoredProviders ?: emptyList()
        if (excluded.isNotEmpty()) lines.add("Excluded Model Providers: ${excluded.joinToString(", ")}")

        lines.add("Routing Request Status: $attachmentStatus")
        return lines.joinToString("\n")
    }
}
