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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/** The one-line routing summary written to the Response Lifecycle log. */
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
            "generic endpoint — not applied",
            ProviderRoutingDiagnostics.describe(false, favorite(FavoriteModelObject.ROUTING_ONLY, "deepinfra"), false, false)
        )
    }

    @Test
    fun onlyModeShowsProviderAndAttachment() {
        val s = ProviderRoutingDiagnostics.describe(true, favorite(FavoriteModelObject.ROUTING_ONLY, "deepinfra"), true, false)
        assertTrue(s.contains("mode=only"))
        assertTrue(s.contains("provider=deepinfra"))
        assertTrue(s.contains("provider object attached=true"))
    }

    @Test
    fun preferredShowsOrderFallbacksAndExclusions() {
        val s = ProviderRoutingDiagnostics.describe(
            true,
            favorite(FavoriteModelObject.ROUTING_PREFERRED, fallbacks = false, order = listOf("deepinfra", "together"), ignored = listOf("openai")),
            true,
            false
        )
        assertTrue(s.contains("mode=preferred"))
        assertTrue(s.contains("order=[deepinfra,together]"))
        assertTrue(s.contains("fallbacks=false"))
        assertTrue(s.contains("excluded=[openai]"))
    }

    @Test
    fun blockedReadsBlockedNotSent() {
        val s = ProviderRoutingDiagnostics.describe(true, favorite(FavoriteModelObject.ROUTING_ONLY, ""), false, true)
        assertTrue(s.contains("BLOCKED (not sent)"))
    }
}
