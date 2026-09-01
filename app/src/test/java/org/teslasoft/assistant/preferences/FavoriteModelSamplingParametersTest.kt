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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * Guards the "all-null means no saved parameters" contract on the favorite DTO.
 * Selecting a model only applies its parameters when the favorite actually
 * carries some, so a fresh favorite must read back empty and never overwrite a
 * chat's own values.
 */
class FavoriteModelSamplingParametersTest {

    @Test
    fun newFavoriteHasNoSavedSamplingParameters() {
        val fav = FavoriteModelObject(modelId = "m", endpointId = "e")
        assertNull(fav.streaming)
        assertNull(fav.temperature)
        assertNull(fav.topP)
        assertNull(fav.frequencyPenalty)
        assertNull(fav.presencePenalty)
        assertFalse(fav.hasSamplingParameters())
    }

    @Test
    fun savingASliderMarksTheFavoriteAsHavingParameters() {
        val fav = FavoriteModelObject(modelId = "m", endpointId = "e").apply {
            temperature = 1.2f
        }
        assertTrue(fav.hasSamplingParameters())
    }

    @Test
    fun savingOnlyStreamingStillCountsAsHavingParameters() {
        val fav = FavoriteModelObject(modelId = "m", endpointId = "e").apply {
            streaming = false
        }
        assertTrue(fav.hasSamplingParameters())
    }
}
