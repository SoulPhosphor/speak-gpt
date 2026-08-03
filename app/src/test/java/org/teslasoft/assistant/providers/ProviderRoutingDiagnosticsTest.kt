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

/** The clearly labeled routing-request summary written to the lifecycle log. */
class ProviderRoutingDiagnosticsTest {

    private fun favorite(
        routing: String,
        selected: String = "",
        fallbacks: Boolean = true,
        order: List<String> = emptyList(),
        ignored: List<String> = emptyList()
    ) = FavoriteModelObject("openai/gpt-4o", "ep-test", routing, selected, fallbacks, order, ignored)

    @Test
    fun genericEndpointReadsNotApplied() {
        assertEquals(
            "Provider Routing: not applicable (direct endpoint)",
            ProviderRoutingDiagnostics.describe(false, favorite(FavoriteModelObject.ROUTING_ONLY, "deepinfra"), "provider object attached")
        )
    }

    @Test
    fun onlyModeShowsProviderAndTheCallerSuppliedStatus() {
        val s = ProviderRoutingDiagnostics.describe(true, favorite(FavoriteModelObject.ROUTING_ONLY, "deepinfra"), "provider object attached")
        assertTrue(s.contains("Provider Routing Mode: only"))
        assertTrue(s.contains("Requested Model Provider: deepinfra"))
        assertTrue(s.contains("Routing Request Status: provider object attached"))
    }

    @Test
    fun preferredShowsOrderFallbacksAndExclusions() {
        val s = ProviderRoutingDiagnostics.describe(
            true,
            favorite(FavoriteModelObject.ROUTING_PREFERRED, fallbacks = false, order = listOf("deepinfra", "together"), ignored = listOf("openai")),
            "provider object attached"
        )
        assertTrue(s.contains("Provider Routing Mode: preferred"))
        assertTrue(s.contains("Requested Provider Order: deepinfra, together"))
        assertTrue(s.contains("Fallbacks Allowed: false"))
        assertTrue(s.contains("Excluded Model Providers: openai"))
    }

    @Test
    fun rendersTheExactStatusPhraseItIsGiven() {
        // The describer never infers attachment; it prints the caller's phrase.
        val requested = ProviderRoutingDiagnostics.describe(true, favorite(FavoriteModelObject.ROUTING_ONLY, "deepinfra"), "attachment requested (send hook did not run)")
        assertTrue(requested.contains("attachment requested (send hook did not run)"))
        assertFalse(requested.contains("attached"))

        val blocked = ProviderRoutingDiagnostics.describe(true, favorite(FavoriteModelObject.ROUTING_ONLY, ""), "BLOCKED (not sent)")
        assertTrue(blocked.contains("BLOCKED (not sent)"))
    }

    @Test
    fun automaticIsExplicitlyARequestAndNeverMasqueradesAsActualProvider() {
        val s = ProviderRoutingDiagnostics.describe(
            true, favorite(FavoriteModelObject.ROUTING_AUTOMATIC),
            "no provider object (Automatic / no saved routing)"
        )
        assertTrue(s.contains("Requested Model Provider: automatic selection"))
        assertFalse(s.contains("Actual Model Provider"))
    }
}
