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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * Request-time enforcement rules (owner plan, Aug 2 2026). These are the
 * rules request wiring must obey when it is built; they are locked in here
 * so stale or corrupted saved data can never bypass them.
 */
class ProviderRoutingEnforcerTest {

    private fun favorite(
        routing: String,
        selected: String = "",
        fallbacks: Boolean = true,
        order: List<String> = emptyList(),
        ignored: List<String> = emptyList()
    ) = FavoriteModelObject(
        "openai/gpt-4o", "ep-test", routing, selected, fallbacks, order, ignored
    )

    /* ------------------------------ Only mode ------------------------------ */

    @Test
    fun onlyModeWithoutSelectionBlocksTheRequest() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(FavoriteModelObject.ROUTING_ONLY), setOf("openai")
        )
        assertEquals(RoutingBlock.ONLY_PROVIDER_NOT_SELECTED, decision.block)
        assertFalse(decision.allowed)
    }

    @Test
    fun onlyModeWithUnavailableSelectionBlocksNeverDowngrades() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(FavoriteModelObject.ROUTING_ONLY, selected = "lepton"),
            setOf("openai", "azure")
        )
        assertEquals(RoutingBlock.ONLY_PROVIDER_UNAVAILABLE, decision.block)
        assertFalse(decision.allowed)
    }

    @Test
    fun onlyModeWithAvailableSelectionSendsExactlyThatProvider() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(FavoriteModelObject.ROUTING_ONLY, selected = "openai", ignored = listOf("azure")),
            setOf("openai", "azure")
        )
        assertTrue(decision.allowed)
        assertEquals("openai", decision.only)
        // Ignore list is not sent in Only mode.
        assertTrue(decision.ignore.isEmpty())
    }

    @Test
    fun onlyModeWithUnknownAvailabilityProceedsWithSavedSelection() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(FavoriteModelObject.ROUTING_ONLY, selected = "openai"), null
        )
        assertTrue(decision.allowed)
        assertEquals("openai", decision.only)
    }

    /* ------------------------------ Preferred mode ------------------------------ */

    @Test
    fun preferredOrderDropsUnavailableProvidersKeepingRelativeOrder() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(
                FavoriteModelObject.ROUTING_PREFERRED,
                order = listOf("lepton", "openai", "azure")
            ),
            setOf("openai", "azure")
        )
        assertTrue(decision.allowed)
        assertEquals(listOf("openai", "azure"), decision.order)
    }

    @Test
    fun preferredWithAllUnavailableAndFallbacksOffBlocks() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(
                FavoriteModelObject.ROUTING_PREFERRED,
                fallbacks = false,
                order = listOf("lepton", "hyperbolic")
            ),
            setOf("openai")
        )
        assertEquals(RoutingBlock.NO_PREFERRED_AVAILABLE, decision.block)
        assertFalse(decision.allowed)
    }

    @Test
    fun preferredWithAllUnavailableButFallbacksOnProceedsForAutomaticFallback() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(
                FavoriteModelObject.ROUTING_PREFERRED,
                fallbacks = true,
                order = listOf("lepton")
            ),
            setOf("openai")
        )
        assertTrue(decision.allowed)
        assertTrue(decision.order.isEmpty())
        assertTrue(decision.allowFallbacks)
    }

    @Test
    fun preferredWithUnknownAvailabilitySendsSavedConfigurationUnchanged() {
        val saved = listOf("lepton", "openai")
        val decision = ProviderRoutingEnforcer.decide(
            favorite(
                FavoriteModelObject.ROUTING_PREFERRED,
                fallbacks = false,
                order = saved,
                ignored = listOf("azure")
            ),
            null
        )
        // Unknown availability never blocks Preferred and never rewrites the
        // saved lists.
        assertTrue(decision.allowed)
        assertEquals(saved, decision.order)
        assertEquals(listOf("azure"), decision.ignore)
    }

    /* ------------------------------ Automatic + ignore ------------------------------ */

    @Test
    fun automaticModeSendsOnlyAvailableIgnoredProviders() {
        val decision = ProviderRoutingEnforcer.decide(
            favorite(
                FavoriteModelObject.ROUTING_AUTOMATIC,
                ignored = listOf("lepton", "azure")
            ),
            setOf("openai", "azure")
        )
        assertTrue(decision.allowed)
        assertEquals(listOf("azure"), decision.ignore)
    }

    @Test
    fun noFavoriteMeansNoProviderPreferencesAndNoBlock() {
        val decision = ProviderRoutingEnforcer.decide(null, setOf("openai"))
        assertTrue(decision.allowed)
        assertTrue(decision.order.isEmpty())
        assertTrue(decision.ignore.isEmpty())
    }
}
