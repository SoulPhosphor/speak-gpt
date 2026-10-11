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
 * Decides whether it is safe to change message content into explicit-cache
 * blocks. A cache-read price is not proof that an endpoint accepts explicit
 * breakpoints: most endpoints cache implicitly. Every endpoint the request can
 * reach must instead report a cache-write price and must not report implicit
 * caching.
 */
object PromptCachingPolicy {

    fun shouldAddExplicitBreakpoint(
        endpoints: List<ProviderEndpointInfo>,
        favorite: FavoriteModelObject?,
        catalogAuthoritative: Boolean = false
    ): Boolean {
        if (endpoints.isEmpty()) return false

        val ignored = favorite?.ignoredProviders.orEmpty().normalized()
        val available = endpoints.filterNot { it.matchesAny(ignored) }
        val candidates = when (favorite?.routingType) {
            FavoriteModelObject.ROUTING_ONLY -> {
                val selected = favorite.selectedProvider.trim()
                if (selected.isEmpty()) return false
                available.filter { it.matches(selected) }
            }
            FavoriteModelObject.ROUTING_PREFERRED -> {
                if (favorite.allowFallbacks) {
                    if (!catalogAuthoritative) return false
                    available
                } else {
                    val order = favorite.providerOrder.normalized()
                    if (order.isEmpty()) return false
                    val matched = available.filter { it.matchesAny(order) }
                    if (order.any { wanted -> matched.none { it.matches(wanted) } }) return false
                    matched
                }
            }
            else -> {
                if (!catalogAuthoritative) return false
                available
            }
        }

        return candidates.isNotEmpty() && candidates.all {
            it.supportsCaching != true &&
                it.cacheWritePrice?.let { price -> price.isFinite() && price >= 0.0 } == true
        }
    }

    private fun List<String>.normalized(): Set<String> =
        map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun ProviderEndpointInfo.matches(value: String): Boolean =
        slug.equals(value, ignoreCase = true) || providerName.equals(value, ignoreCase = true)

    private fun ProviderEndpointInfo.matchesAny(values: Set<String>): Boolean =
        values.any { value -> matches(value) }
}
