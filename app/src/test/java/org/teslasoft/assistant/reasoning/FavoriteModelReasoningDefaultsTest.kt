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

package org.teslasoft.assistant.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * Guards the §7.9 saved defaults on the favorite DTO and the intentional
 * coupling between the DTO's storage constant and the reasoning vocabulary.
 */
class FavoriteModelReasoningDefaultsTest {

    @Test
    fun newFavoriteDefaultsToAutoAndShowReasoningOn() {
        val fav = FavoriteModelObject(modelId = "m", endpointId = "e")
        assertEquals(FavoriteModelObject.REASONING_AUTO, fav.reasoningEffort)
        assertTrue(fav.showReasoning)
    }

    @Test
    fun dtoConstantMatchesReasoningAutoSerialization() {
        // The DTO deliberately holds a plain string constant to avoid depending
        // on the reasoning package; this test enforces that they never drift.
        assertEquals(ReasoningEffort.AUTO.serialized, FavoriteModelObject.REASONING_AUTO)
    }

    @Test
    fun savedEffortParsesBackToAKnownLevel() {
        val fav = FavoriteModelObject(modelId = "m", endpointId = "e").apply {
            reasoningEffort = ReasoningEffort.HIGH.serialized
        }
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromSerialized(fav.reasoningEffort))
    }
}
