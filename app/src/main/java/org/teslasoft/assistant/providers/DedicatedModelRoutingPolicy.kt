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
 * Provider-routing overrides for dedicated background models such as Memory
 * Assistant and Summarizer. Each feature keeps an independent selected mode,
 * while provider details are borrowed from the selected model favorite. The
 * favorite itself is never mutated merely because a feature selects a mode.
 */
object DedicatedModelRoutingPolicy {

    val routingTypes = listOf(
        FavoriteModelObject.ROUTING_AUTOMATIC,
        FavoriteModelObject.ROUTING_PREFERRED,
        FavoriteModelObject.ROUTING_ONLY
    )

    fun normalize(mode: String): String =
        mode.takeIf { it in routingTypes } ?: FavoriteModelObject.ROUTING_AUTOMATIC

    /** A newly selected favorite adopts its saved default when that default is
     * actually configured. Non-favorites and incomplete legacy favorites use
     * Automatic rather than displaying a routing promise they cannot honor. */
    fun modeForSelectedModel(
        endpointSupportsRouting: Boolean,
        favorite: FavoriteModelObject?
    ): String {
        if (!endpointSupportsRouting || favorite == null) {
            return FavoriteModelObject.ROUTING_AUTOMATIC
        }
        val mode = normalize(favorite.routingType)
        return if (needsSetup(mode, favorite)) {
            FavoriteModelObject.ROUTING_AUTOMATIC
        } else {
            mode
        }
    }

    fun needsSetup(mode: String, favorite: FavoriteModelObject?): Boolean = when (normalize(mode)) {
        FavoriteModelObject.ROUTING_PREFERRED -> favorite == null || favorite.providerOrder.isEmpty()
        FavoriteModelObject.ROUTING_ONLY -> favorite == null || favorite.selectedProvider.isBlank()
        else -> false
    }

    /**
     * Copy the selected favorite's provider data while replacing only the
     * feature's request mode. Automatic keeps the favorite's Ignore list,
     * matching ordinary chat routing.
     */
    fun favoriteForRequest(
        modelId: String,
        endpointId: String,
        mode: String,
        favorite: FavoriteModelObject?
    ): FavoriteModelObject? {
        val normalized = normalize(mode)
        if (favorite == null && normalized == FavoriteModelObject.ROUTING_AUTOMATIC) return null
        return FavoriteModelObject(
            modelId = modelId,
            endpointId = endpointId,
            routingType = normalized,
            selectedProvider = favorite?.selectedProvider.orEmpty(),
            allowFallbacks = favorite?.allowFallbacks ?: true,
            providerOrder = favorite?.providerOrder ?: emptyList(),
            ignoredProviders = favorite?.ignoredProviders ?: emptyList()
        )
    }
}
