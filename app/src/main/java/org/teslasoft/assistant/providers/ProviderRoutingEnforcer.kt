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

/** Why a request must not be sent. NONE = the request may proceed. */
enum class RoutingBlock {
    NONE,
    /** Only mode with no provider selected. */
    ONLY_PROVIDER_NOT_SELECTED,
    /** Only mode whose selected provider is confirmed unavailable. */
    ONLY_PROVIDER_UNAVAILABLE,
    /** Preferred mode, fallbacks off, and no listed provider is available. */
    NO_PREFERRED_AVAILABLE
}

/**
 * What request wiring should actually send. All lists are already filtered
 * per the rules below; the SAVED configuration is never modified — filtering
 * happens on the outgoing payload only.
 */
data class RoutingDecision(
    val block: RoutingBlock,
    /** Only mode: the single permitted provider slug. */
    val only: String? = null,
    /** Preferred mode: the order list to send (available providers only,
     *  saved relative order preserved). */
    val order: List<String> = emptyList(),
    /** Ignore list to send (available providers only; empty in Only mode). */
    val ignore: List<String> = emptyList(),
    val allowFallbacks: Boolean = true
) {
    val allowed: Boolean get() = block == RoutingBlock.NONE
}

/**
 * Request-time enforcement of the saved provider routing rules (owner plan,
 * Aug 2 2026). The Choose Provider screen's save-time validation is NOT
 * sufficient — availability changes after saving — so request wiring MUST
 * route every outgoing chat request's provider preferences through [decide],
 * with the freshest authoritative discovery information available.
 *
 * NOTE: request wiring does not exist yet. This module is the single,
 * pre-tested rule set it must call; nothing sends provider preferences today.
 *
 * [availableSlugs] carries the lowercase slugs of a COMPLETE, authoritative
 * discovery result, or null when availability is unknown (failed, partial,
 * paginated, truncated, or empty result). Unknown availability never blocks
 * a Preferred request, never marks anything unavailable, and never rewrites
 * the user's saved configuration — the saved lists are sent as they are.
 */
object ProviderRoutingEnforcer {

    fun decide(favorite: FavoriteModelObject?, availableSlugs: Set<String>?): RoutingDecision {
        favorite ?: return RoutingDecision(RoutingBlock.NONE)

        return when (favorite.routingType) {
            FavoriteModelObject.ROUTING_ONLY -> decideOnly(favorite, availableSlugs)
            FavoriteModelObject.ROUTING_PREFERRED -> decidePreferred(favorite, availableSlugs)
            else -> RoutingDecision(
                RoutingBlock.NONE,
                ignore = filterAvailable(favorite.ignoredProviders, availableSlugs)
            )
        }
    }

    /** Only mode: exactly one currently available provider, or the request is
     *  blocked. Never silently downgraded to Automatic. The ignore list is
     *  not sent in Only mode (owner rule). */
    private fun decideOnly(favorite: FavoriteModelObject, available: Set<String>?): RoutingDecision {
        val selected = favorite.selectedProvider
        if (selected.isBlank()) {
            return RoutingDecision(RoutingBlock.ONLY_PROVIDER_NOT_SELECTED)
        }
        if (available != null && selected.lowercase() !in available) {
            return RoutingDecision(RoutingBlock.ONLY_PROVIDER_UNAVAILABLE)
        }
        return RoutingDecision(RoutingBlock.NONE, only = selected)
    }

    private fun decidePreferred(favorite: FavoriteModelObject, available: Set<String>?): RoutingDecision {
        val order = filterAvailable(favorite.providerOrder, available)
        val ignore = filterAvailable(favorite.ignoredProviders, available)

        // Every saved preferred provider confirmed unavailable and fallbacks
        // off: no permitted provider remains — block. With fallbacks on the
        // request proceeds and automatic fallback applies.
        if (favorite.providerOrder.isNotEmpty() &&
            available != null &&
            order.isEmpty() &&
            !favorite.allowFallbacks
        ) {
            return RoutingDecision(RoutingBlock.NO_PREFERRED_AVAILABLE)
        }

        return RoutingDecision(
            RoutingBlock.NONE,
            order = order,
            ignore = ignore,
            allowFallbacks = favorite.allowFallbacks
        )
    }

    /** With known availability, unavailable slugs are dropped from the
     *  OUTGOING list only (saved data untouched); with unknown availability
     *  the saved list passes through unchanged. */
    private fun filterAvailable(saved: List<String>, available: Set<String>?): List<String> {
        available ?: return saved
        return saved.filter { it.lowercase() in available }
    }
}
