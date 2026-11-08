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

package org.teslasoft.assistant.preferences.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import org.teslasoft.assistant.preferences.ModelPickerScope

class ClearedSelectedModelsTest {

    private val summary = ModelIdentity("ep-1", "model-s")
    private val scanner = ModelIdentity("ep-1", "model-m")

    private val selected = mapOf(ModelPickerScope.SUMMARIZER to summary, ModelPickerScope.MEMORY_SCANNER to scanner)

    @Test
    fun onlyAJobWhoseSelectedModelThisCheckFoundMissingIsCleared() {
        val found = ClearedSelectedModels.findUnavailable(
            selected, mapOf("ep-1" to EndpointCatalogCheck.Checked(setOf("model-s")))
        )
        assertEquals(listOf(ClearedSelectedModels.Entry(ModelPickerScope.MEMORY_SCANNER, scanner)), found)
    }

    @Test
    fun nothingIsClearedWhenEveryModelIsAvailable() {
        assertTrue(ClearedSelectedModels.findUnavailable(
            selected, mapOf("ep-1" to EndpointCatalogCheck.Checked(setOf("model-s", "model-m")))
        ).isEmpty())
    }

    @Test
    fun aProfileThatCouldNotBeCheckedClearsNothing() {
        assertTrue(ClearedSelectedModels.findUnavailable(selected, mapOf("ep-1" to EndpointCatalogCheck.Unchecked)).isEmpty())
        assertTrue(ClearedSelectedModels.findUnavailable(selected, emptyMap()).isEmpty())
    }

    @Test
    fun aModelWhoseAvailabilityIsUndeterminedIsNotCleared() {
        val found = ClearedSelectedModels.findUnavailable(
            selected,
            mapOf("ep-1" to EndpointCatalogCheck.Checked(setOf("model-s"), indeterminateModelIds = setOf("model-m")))
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun anUnreadableListReadsAsNothingCleared() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("entries", "not json").apply()
        assertTrue(ClearedSelectedModels(prefs).load().isEmpty())
    }
}
