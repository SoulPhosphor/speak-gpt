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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

class DedicatedModelRoutingPolicyTest {

    private fun favorite(
        routing: String,
        selected: String = "",
        order: List<String> = emptyList(),
        ignored: List<String> = emptyList()
    ) = FavoriteModelObject(
        "openai/gpt-4o", "ep-test", routing, selected,
        true, order, ignored
    )

    @Test
    fun nonFavoriteAndGenericEndpointDefaultToAutomatic() {
        assertEquals(
            FavoriteModelObject.ROUTING_AUTOMATIC,
            DedicatedModelRoutingPolicy.modeForSelectedModel(true, null)
        )
        assertEquals(
            FavoriteModelObject.ROUTING_AUTOMATIC,
            DedicatedModelRoutingPolicy.modeForSelectedModel(
                false,
                favorite(FavoriteModelObject.ROUTING_ONLY, selected = "deepinfra")
            )
        )
    }

    @Test
    fun configuredFavoriteAdoptsItsSavedDefault() {
        assertEquals(
            FavoriteModelObject.ROUTING_PREFERRED,
            DedicatedModelRoutingPolicy.modeForSelectedModel(
                true,
                favorite(FavoriteModelObject.ROUTING_PREFERRED, order = listOf("deepinfra"))
            )
        )
        assertEquals(
            FavoriteModelObject.ROUTING_ONLY,
            DedicatedModelRoutingPolicy.modeForSelectedModel(
                true,
                favorite(FavoriteModelObject.ROUTING_ONLY, selected = "deepinfra")
            )
        )
    }

    @Test
    fun incompleteFavoriteDoesNotPromiseUnavailableRouting() {
        assertEquals(
            FavoriteModelObject.ROUTING_AUTOMATIC,
            DedicatedModelRoutingPolicy.modeForSelectedModel(
                true,
                favorite(FavoriteModelObject.ROUTING_PREFERRED)
            )
        )
        assertTrue(
            DedicatedModelRoutingPolicy.needsSetup(
                FavoriteModelObject.ROUTING_ONLY,
                favorite(FavoriteModelObject.ROUTING_AUTOMATIC)
            )
        )
    }

    @Test
    fun requestOverrideCopiesProviderDataWithoutMutatingFavorite() {
        val stored = favorite(
            FavoriteModelObject.ROUTING_ONLY,
            selected = "deepinfra",
            order = listOf("openai"),
            ignored = listOf("novita")
        )
        val request = DedicatedModelRoutingPolicy.favoriteForRequest(
            stored.modelId,
            stored.endpointId,
            FavoriteModelObject.ROUTING_PREFERRED,
            stored
        )!!

        assertEquals(FavoriteModelObject.ROUTING_PREFERRED, request.routingType)
        assertEquals(listOf("openai"), request.providerOrder)
        assertEquals(listOf("novita"), request.ignoredProviders)
        assertEquals(FavoriteModelObject.ROUTING_ONLY, stored.routingType)
    }

    @Test
    fun automaticNonFavoriteNeedsNoSyntheticFavorite() {
        assertNull(
            DedicatedModelRoutingPolicy.favoriteForRequest(
                "model", "endpoint", FavoriteModelObject.ROUTING_AUTOMATIC, null
            )
        )
        assertFalse(
            DedicatedModelRoutingPolicy.needsSetup(
                FavoriteModelObject.ROUTING_AUTOMATIC, null
            )
        )
    }
}
