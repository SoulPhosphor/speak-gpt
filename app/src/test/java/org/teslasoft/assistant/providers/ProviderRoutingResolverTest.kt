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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * The request-boundary resolution used by the chat send path. Proves generic
 * endpoints attach nothing, that model selection is independent of routing,
 * that an unsatisfiable config is blocked (never silently sent unrestricted),
 * and that resolution carries no state between calls.
 */
class ProviderRoutingResolverTest {

    private fun favorite(
        routing: String,
        selected: String = "",
        fallbacks: Boolean = true,
        order: List<String> = emptyList(),
        ignored: List<String> = emptyList()
    ) = FavoriteModelObject("openai/gpt-4o", "ep-test", routing, selected, fallbacks, order, ignored)

    @Test
    fun genericEndpointAttachesNothingAndNeverBlocks() {
        val r = ProviderRoutingResolver.resolve(false, favorite(FavoriteModelObject.ROUTING_ONLY, selected = "deepinfra"))
        assertNull(r.providerJson)
        assertEquals(RoutingBlock.NONE, r.block)
    }

    @Test
    fun openRouterWithNoFavoriteAttachesNothing() {
        val r = ProviderRoutingResolver.resolve(true, null)
        assertNull(r.providerJson)
        assertEquals(RoutingBlock.NONE, r.block)
    }

    @Test
    fun automaticWithoutExclusionsAttachesNothing() {
        val r = ProviderRoutingResolver.resolve(true, favorite(FavoriteModelObject.ROUTING_AUTOMATIC))
        assertNull(r.providerJson)
        assertEquals(RoutingBlock.NONE, r.block)
    }

    @Test
    fun automaticWithExclusionsAttachesIgnore() {
        val r = ProviderRoutingResolver.resolve(true, favorite(FavoriteModelObject.ROUTING_AUTOMATIC, ignored = listOf("openai")))
        assertNotNull(r.providerJson)
        assertEquals("openai", r.providerJson!!.getAsJsonArray("ignore")[0].asString)
        assertEquals(RoutingBlock.NONE, r.block)
    }

    @Test
    fun onlyWithProviderAttachesRestriction() {
        val r = ProviderRoutingResolver.resolve(true, favorite(FavoriteModelObject.ROUTING_ONLY, selected = "deepinfra"))
        assertNotNull(r.providerJson)
        assertEquals("deepinfra", r.providerJson!!.getAsJsonArray("order")[0].asString)
        assertEquals(false, r.providerJson!!.get("allow_fallbacks").asBoolean)
        assertEquals(RoutingBlock.NONE, r.block)
    }

    @Test
    fun onlyWithNoProviderIsBlockedNotSentUnrestricted() {
        val r = ProviderRoutingResolver.resolve(true, favorite(FavoriteModelObject.ROUTING_ONLY, selected = ""))
        // Nothing to attach AND a block reason — the request must not go out.
        assertNull(r.providerJson)
        assertEquals(RoutingBlock.ONLY_PROVIDER_NOT_SELECTED, r.block)
    }

    @Test
    fun preferredWithNoProvidersAndNoFallbackIsBlocked() {
        val r = ProviderRoutingResolver.resolve(
            true, favorite(FavoriteModelObject.ROUTING_PREFERRED, fallbacks = false, order = emptyList())
        )
        assertNull(r.providerJson)
        assertEquals(RoutingBlock.NO_PREFERRED_AVAILABLE, r.block)
    }

    @Test
    fun resolutionCarriesNoStateBetweenCalls() {
        // A restricted resolution must not bleed into the next, unrelated one.
        val only = ProviderRoutingResolver.resolve(true, favorite(FavoriteModelObject.ROUTING_ONLY, selected = "deepinfra"))
        assertNotNull(only.providerJson)
        val generic = ProviderRoutingResolver.resolve(false, null)
        assertNull(generic.providerJson)
        assertEquals(RoutingBlock.NONE, generic.block)
        val automatic = ProviderRoutingResolver.resolve(true, favorite(FavoriteModelObject.ROUTING_AUTOMATIC))
        assertNull(automatic.providerJson)
    }
}
