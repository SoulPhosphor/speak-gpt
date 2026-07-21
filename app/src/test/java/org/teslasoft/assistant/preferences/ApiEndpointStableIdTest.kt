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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.Hash

/**
 * API endpoint profiles must key themselves — and their encrypted API key,
 * favorite-model links and per-chat selection — by a STABLE id, not a hash of
 * the (mutable) label. A rename must keep the id so the credential and every
 * reference stay attached, and must never re-encrypt the key under a new id.
 */
class ApiEndpointStableIdTest {

    private class FakeSecrets : ApiEndpointPreferences.SecretStore {
        val values = HashMap<String, String>()
        val setKeys = ArrayList<String>()
        override fun get(key: String): String = values[key] ?: ""
        override fun set(key: String, value: String) { setKeys.add(key); values[key] = value }
    }

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var secrets: FakeSecrets
    private lateinit var store: ApiEndpointPreferences

    @Before fun setUp() {
        prefs = FakeSharedPreferences()
        secrets = FakeSecrets()
        store = ApiEndpointPreferences.createForTest(prefs, secrets)
    }

    private fun sample(label: String) = ApiEndpointObject(label, "https://api.example.com/v1/", "sk-secret-123")

    @Test fun creatingAssignsAStableIdAndStoresTheKeyUnderIt() {
        val ep = sample("z.ai")
        val id = store.setApiEndpoint(ep)
        assertTrue(id.isNotEmpty())
        assertTrue("id is minted, not a name hash", id.startsWith("ep-"))
        assertEquals(id, ep.id)
        assertEquals("key stored under <id>_api_key", "sk-secret-123", secrets.get(id + "_api_key"))
    }

    @Test fun loadingPreservesTheId() {
        val id = store.setApiEndpoint(sample("z.ai"))
        assertEquals(id, store.getApiEndpoint(id).id)
        assertEquals(id, store.getApiEndpointsList().single().id)
    }

    @Test fun renamingKeepsIdAndCredentialsAndDoesNotReEncryptUnderANewId() {
        val id = store.setApiEndpoint(sample("z.ai"))
        secrets.setKeys.clear()

        val loaded = store.getApiEndpoint(id)
        assertEquals("editor sees the existing key", "sk-secret-123", loaded.apiKey)
        loaded.label = "Zhipu"
        store.setApiEndpoint(loaded)

        assertEquals("rename keeps the id", id, loaded.id)
        assertEquals("Zhipu", store.getApiEndpoint(id).label)
        assertEquals("credential unchanged and still attached", "sk-secret-123", store.getApiEndpoint(id).apiKey)
        // The only key touched was the SAME one — no orphaned <newId>_api_key.
        assertEquals(listOf(id + "_api_key"), secrets.setKeys)
    }

    @Test fun editingOtherFieldsKeepsTheId() {
        val id = store.setApiEndpoint(sample("z.ai"))
        val loaded = store.getApiEndpoint(id)
        loaded.model = "glm-4-plus"
        store.setApiEndpoint(loaded)
        assertEquals(id, loaded.id)
        assertEquals("glm-4-plus", store.getApiEndpoint(id).model)
    }

    @Test fun renamingCreatesNoDuplicateAndKeepsFavoriteAndSelectionRefsValid() {
        val id = store.setApiEndpoint(sample("z.ai"))
        // A favorite / per-chat selection stored this id.
        val storedRef = id

        val loaded = store.getApiEndpoint(id)
        loaded.label = "Zhipu"
        store.setApiEndpoint(loaded)

        assertEquals("still exactly one profile", 1, store.getApiEndpointsList().size)
        // The stored reference still resolves to the (renamed) endpoint.
        assertEquals("Zhipu", store.getApiEndpoint(storedRef).label)
    }

    @Test fun deletingUsesTheStoredIdAndClearsTheKey() {
        val id = store.setApiEndpoint(sample("z.ai"))
        store.deleteApiEndpoint(id)
        assertTrue(store.getApiEndpointsList().isEmpty())
        assertEquals("key nulled on delete", "null", secrets.get(id + "_api_key"))
    }

    @Test fun theBuiltInDefaultProfileUsesTheReservedConstantId() {
        val ep = ApiEndpointObject("Default", "https://api.openai.com/v1/", "", id = ApiEndpointObject.DEFAULT_ENDPOINT_ID)
        val id = store.setApiEndpoint(ep)
        assertEquals("Default keeps the reserved id so the default reference resolves",
            ApiEndpointObject.DEFAULT_ENDPOINT_ID, id)
    }

    @Test fun legacyEndpointKeepsItsHashedIdAfterLoadAndSave() {
        val legacyId = Hash.hash("Legacy")
        prefs.edit()
            .putString(legacyId + "_label", "Legacy")
            .putString(legacyId + "_host", "https://legacy.example.com/v1/")
            .apply()
        secrets.values[legacyId + "_api_key"] = "sk-legacy"

        val loaded = store.getApiEndpointsList().single()
        assertEquals("legacy endpoint keeps its original hashed id", legacyId, loaded.id)
        assertEquals("sk-legacy", loaded.apiKey)

        loaded.label = "Renamed"
        store.setApiEndpoint(loaded)
        assertEquals(legacyId, loaded.id)
        assertEquals(1, store.getApiEndpointsList().size)
        assertEquals("sk-legacy", store.getApiEndpoint(legacyId).apiKey)
        assertNotEquals("id not re-derived from the new label", Hash.hash("Renamed"), loaded.id)
    }
}
