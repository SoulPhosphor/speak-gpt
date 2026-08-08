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
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.junit.Test

/**
 * Pins the new-chat provider-restore decision (owner spec, Aug 8 2026),
 * including the honesty rule: a missing local configuration is never conflated
 * with the model being unavailable.
 */
class NewChatProviderRestoreTest {

    private val auto = FavoriteModelObject.ROUTING_AUTOMATIC
    private val only = FavoriteModelObject.ROUTING_ONLY
    private val preferred = FavoriteModelObject.ROUTING_PREFERRED

    @Test fun nothingRecordedIsNoConfig() {
        assertEquals(
            NewChatProviderRestore.Outcome.NO_CONFIG,
            NewChatProviderRestore.decide("", "", auto, endpointExists = false, favoriteExists = false)
        )
        assertEquals(
            NewChatProviderRestore.Outcome.NO_CONFIG,
            NewChatProviderRestore.decide("ep", "", auto, endpointExists = true, favoriteExists = true)
        )
        assertEquals(
            NewChatProviderRestore.Outcome.NO_CONFIG,
            NewChatProviderRestore.decide("", "gpt-x", auto, endpointExists = true, favoriteExists = true)
        )
    }

    @Test fun deletedProviderProfileIsMissingConfig() {
        assertEquals(
            NewChatProviderRestore.Outcome.MISSING_CONFIG,
            NewChatProviderRestore.decide("ep", "gpt-x", auto, endpointExists = false, favoriteExists = false)
        )
    }

    @Test fun automaticRestoresWithoutAFavorite() {
        // Automatic is "any provider" — a missing favorite is not lost config.
        assertEquals(
            NewChatProviderRestore.Outcome.RESTORE,
            NewChatProviderRestore.decide("ep", "gpt-x", auto, endpointExists = true, favoriteExists = false)
        )
    }

    @Test fun onlyOrPreferredWithoutFavoriteIsMissingConfig() {
        assertEquals(
            NewChatProviderRestore.Outcome.MISSING_CONFIG,
            NewChatProviderRestore.decide("ep", "gpt-x", only, endpointExists = true, favoriteExists = false)
        )
        assertEquals(
            NewChatProviderRestore.Outcome.MISSING_CONFIG,
            NewChatProviderRestore.decide("ep", "gpt-x", preferred, endpointExists = true, favoriteExists = false)
        )
    }

    @Test fun onlyOrPreferredWithFavoriteRestores() {
        assertEquals(
            NewChatProviderRestore.Outcome.RESTORE,
            NewChatProviderRestore.decide("ep", "gpt-x", only, endpointExists = true, favoriteExists = true)
        )
        assertEquals(
            NewChatProviderRestore.Outcome.RESTORE,
            NewChatProviderRestore.decide("ep", "gpt-x", preferred, endpointExists = true, favoriteExists = true)
        )
    }
}
