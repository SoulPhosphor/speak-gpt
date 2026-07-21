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

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.util.Hash

/**
 * Logit-bias configs must key their bias values by a STABLE id. Renaming a
 * config must change only its label and keep the id, so the values stored under
 * `logit_bias_config_<id>` and any per-chat/global selection stay attached (no
 * movePreferences needed anymore).
 */
class LogitBiasConfigStableIdTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: LogitBiasConfigPreferences

    @Before fun setUp() {
        prefs = FakeSharedPreferences()
        store = LogitBiasConfigPreferences.createForTest(prefs)
    }

    private fun idOf(label: String): String =
        store.getAllConfigs().first { it["label"] == label }["id"]!!

    @Test fun addingAssignsAStableId() {
        store.addConfig("Creative")
        val id = idOf("Creative")
        assertTrue("a new config gets a minted id", id.isNotEmpty())
        assertTrue("id is stable/random, not a name hash", id.startsWith("lb-"))
    }

    @Test fun loadingPreservesTheId() {
        store.addConfig("Creative")
        val id = idOf("Creative")
        assertEquals(id, store.getConfigById(id)!!["id"])
        assertEquals("Creative", store.getConfigById(id)!!["label"])
    }

    @Test fun renamingChangesOnlyTheLabelAndKeepsTheId() {
        store.addConfig("Creative")
        val id = idOf("Creative")

        store.editConfig(id, "Focused")

        assertEquals("id unchanged by rename", "Focused", store.getConfigById(id)!!["label"])
        assertEquals("still exactly one config", 1, store.getAllConfigs().size)
        // The values file is keyed by this id, so it stays attached.
        assertEquals(id, store.getConfigById(id)!!["id"])
    }

    @Test fun renamingCreatesNoDuplicate() {
        store.addConfig("Creative")
        val id = idOf("Creative")
        store.editConfig(id, "Focused")
        assertEquals(1, store.getAllConfigs().size)
    }

    @Test fun deletingUsesTheStoredId() {
        store.addConfig("Creative")
        val id = idOf("Creative")
        store.deleteConfig(id)
        assertTrue(store.getAllConfigs().isEmpty())
        assertNull(store.getConfigById(id))
    }

    @Test fun legacyConfigKeepsItsHashedIdAfterRename() {
        // Simulate a config written by the old build: id = Hash.hash(label).
        val legacyId = Hash.hash("Legacy")
        val seeded = arrayListOf(hashMapOf("label" to "Legacy", "id" to legacyId))
        prefs.edit().putString("configs", Gson().toJson(seeded)).apply()

        assertEquals(legacyId, idOf("Legacy"))

        store.editConfig(legacyId, "Renamed")
        assertEquals("legacy config keeps its original id after rename", "Renamed", store.getConfigById(legacyId)!!["label"])
        assertNotEquals("id is not re-derived from the new label", Hash.hash("Renamed"), store.getConfigById(legacyId)!!["id"])
        assertEquals(1, store.getAllConfigs().size)
    }
}
