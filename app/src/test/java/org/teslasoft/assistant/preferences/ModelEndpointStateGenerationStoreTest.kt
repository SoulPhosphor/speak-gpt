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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.portable.ModelEndpointMergePlanner
import org.teslasoft.assistant.preferences.backup.portable.ModelEndpointPortableCodec
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity

class ModelEndpointStateGenerationStoreTest {
    @Test
    fun recentFavoriteAndPinKeepHistoryWhileRecentDeleteKeepsTheFavorite() {
        val preferences = FakeSharedPreferences()
        val state = ModelEndpointStateGenerationStore.createForTest(preferences)
        assertTrue(state.install(ModelEndpointPortableCodec.Data(
            listOf(endpoint("ep-1", "One", "model-a")), emptyList()
        )))
        val favorites = FavoriteModelsPreferences.createForTest(preferences)
        val recents = RecentModelsPreferences(FakeSharedPreferences())
        val model = FavoriteModelObject("model-a", "ep-1", selectedProvider = "saved")
        recents.record(model)

        favorites.addFavoriteIfAbsent(model)
        assertEquals(null, favorites.getFavoriteModels().single()["pinned"])
        favorites.setPinned(model, true)
        favorites.addFavoriteIfAbsent(FavoriteModelObject("model-a", "ep-1", selectedProvider = "different"))
        assertEquals("true", favorites.getFavoriteModels().single()["pinned"])
        assertEquals("saved", recents.models().single().selectedProvider)

        recents.remove(ModelIdentity("ep-1", "model-a"))
        assertTrue(recents.models().isEmpty())
        assertEquals("saved", favorites.getFavorite("model-a", "ep-1")?.selectedProvider)
        assertEquals("true", favorites.getFavoriteModels().single()["pinned"])
    }

    @Test
    fun pinAndUnpinPreserveSettingsAndFavoriteDeletionPreservesRecentHistory() {
        val preferences = FakeSharedPreferences()
        val state = ModelEndpointStateGenerationStore.createForTest(preferences)
        assertTrue(state.install(data(endpoint("ep-1", "One", "model-a"), favorite("ep-1", "model-a"))))
        val favorites = FavoriteModelsPreferences.createForTest(preferences)
        val recents = RecentModelsPreferences(FakeSharedPreferences())
        val model = FavoriteModelObject("model-a", "ep-1").apply {
            routingType = FavoriteModelObject.ROUTING_ONLY
            selectedProvider = "test-provider"
        }
        favorites.addFavoriteModel(model)
        recents.record(model)

        favorites.setPinned(model, true)
        model.temperature = 0.5f
        favorites.addFavoriteModel(model)
        assertEquals("true", favorites.getFavoriteModels().single()["pinned"])

        favorites.setPinned(model, false)
        assertEquals(null, favorites.getFavoriteModels().single()["pinned"])
        assertEquals("test-provider", favorites.getFavorite(model.modelId, model.endpointId)?.selectedProvider)
        assertEquals(0.5f, favorites.getFavorite(model.modelId, model.endpointId)?.temperature)

        favorites.setPinned(model, true)
        favorites.removeFavoriteModel(model.modelId, model.endpointId)
        assertTrue(favorites.getFavoriteModels().isEmpty())
        assertEquals("test-provider", recents.models().single().selectedProvider)
    }

    @Test
    fun recentModelsKeepLatestSettingsAndApplyTheStoredLimit() {
        val preferences = FakeSharedPreferences()
        val recents = RecentModelsPreferences(preferences)
        assertEquals(20, recents.limit())
        val first = FavoriteModelObject("model-a", "ep-1")
        val otherProfile = FavoriteModelObject("model-a", "ep-2")
        recents.record(first)
        recents.record(otherProfile)
        recents.record(first.apply { routingType = FavoriteModelObject.ROUTING_PREFERRED })

        val reopened = RecentModelsPreferences(preferences)
        assertEquals(listOf("ep-1", "ep-2"), reopened.models().map { it.endpointId })
        assertEquals(FavoriteModelObject.ROUTING_PREFERRED, reopened.models().first().routingType)
        reopened.setLimit(1)
        assertEquals(listOf("ep-1"), recents.models().map { it.endpointId })
        reopened.setLimit(20)
        assertEquals(listOf("ep-1"), recents.models().map { it.endpointId })
    }

    @Test
    fun stagedWriteLeavesOldGenerationVisibleUntilThePointerSwitch() {
        val preferences = FakeSharedPreferences()
        val store = ModelEndpointStateGenerationStore.createForTest(preferences)
        val old = data(endpoint("ep-old", "Old", "old/model"), favorite("ep-old", "old/model"))
        val desired = data(endpoint("ep-new", "New", "new/model"), favorite("ep-new", "new/model"))
        assertTrue(store.install(old))
        val originalGeneration = store.activeGenerationId()

        val desiredGeneration = store.stage(desired)

        assertNotNull(desiredGeneration)
        assertEquals(old, ModelEndpointStateGenerationStore.createForTest(preferences).read())
        assertEquals(originalGeneration, store.activeGenerationId())

        assertTrue(store.activate(desiredGeneration!!))
        assertEquals(desired, ModelEndpointStateGenerationStore.createForTest(preferences).read())
        assertNotEquals(originalGeneration, store.activeGenerationId())
    }

    @Test
    fun applyTwiceAndRollbackTwiceAreIdempotentPointerSwitches() {
        val preferences = FakeSharedPreferences()
        val store = ModelEndpointStateGenerationStore.createForTest(preferences)
        val old = data(endpoint("ep-1", "Old", "model-a"), favorite("ep-1", "model-a"))
        val desired = data(endpoint("ep-1", "New", "model-b"), favorite("ep-1", "model-b"))
        assertTrue(store.install(old))
        val originalGeneration = store.activeGenerationId()!!
        val desiredGeneration = store.stage(desired)!!

        assertTrue(store.activate(desiredGeneration))
        assertTrue(store.activate(desiredGeneration))
        assertEquals(desired, store.read())

        assertTrue(store.activate(originalGeneration))
        assertTrue(store.activate(originalGeneration))
        assertEquals(old, store.read())
    }

    @Test
    fun mergeInstalledTwiceKeepsOneCompleteLogicalState() {
        val preferences = FakeSharedPreferences()
        val store = ModelEndpointStateGenerationStore.createForTest(preferences)
        val current = data(endpoint("ep-1", "One", "model-a"), favorite("ep-1", "model-a"))
        val incoming = data(endpoint("ep-2", "Two", "model-b"), favorite("ep-2", "model-b"))
        assertTrue(store.install(current))
        val merged = ModelEndpointMergePlanner.merge(current, incoming).data

        assertTrue(store.install(merged))
        val firstGeneration = store.activeGenerationId()
        assertTrue(store.install(merged))

        assertEquals(firstGeneration, store.activeGenerationId())
        assertEquals(merged, store.read())
    }

    @Test
    fun legacyFieldsFavoritesAndRejectedVoicesMigrateTogetherWithoutTouchingSecrets() {
        val definitions = FakeSharedPreferences()
        val favorites = FakeSharedPreferences()
        definitions.edit()
            .putString("ep-legacy_label", "Legacy")
            .putString("ep-legacy_host", "https://legacy.example/v1")
            .putString("ep-legacy_model", "legacy/model")
            .putString("ep-legacy_temperature", "0.4")
            .putString("ep-legacy_tts_rejected_voices", "[\"voice-b\",\"voice-a\"]")
            .commit()
        favorites.edit().putString(
            "favorite_models",
            "[{\"endpointId\":\"ep-legacy\",\"modelId\":\"legacy/model\"}]"
        ).commit()
        val secrets = FakeSecrets().apply {
            values["ep-legacy_api_key"] = "keep-me"
        }
        val endpointPreferences = ApiEndpointPreferences.createForTest(
            definitions, secrets, favorites
        )

        val migrated = endpointPreferences.getApiEndpointsList().single()
        val migratedFavorites = FavoriteModelsPreferences.createForTest(
            definitions, favorites
        ).getFavoriteModels()

        assertEquals("Legacy", migrated.label)
        assertEquals("keep-me", migrated.apiKey)
        assertEquals(setOf("voice-a", "voice-b"), endpointPreferences.getRejectedTtsVoices("ep-legacy"))
        assertEquals("ep-legacy", migratedFavorites.single()["endpointId"])
        assertTrue(secrets.setKeys.isEmpty())
        assertTrue(definitions.contains(ModelEndpointStateGenerationStore.ACTIVE_GENERATION_KEY))
    }

    @Test
    fun portableReplacementDeletionIdOverlapAndRollbackNeverTouchCredentials() {
        val preferences = FakeSharedPreferences()
        val secrets = FakeSecrets()
        val endpointPreferences = ApiEndpointPreferences.createForTest(preferences, secrets)
        endpointPreferences.setApiEndpoint(
            ApiEndpointObject("One", "https://one.example/v1", "secret-one", id = "ep-1")
        )
        endpointPreferences.setApiEndpoint(
            ApiEndpointObject("Delete", "https://delete.example/v1", "secret-delete", id = "ep-2")
        )
        val store = ModelEndpointStateGenerationStore.createForTest(preferences)
        val originalGeneration = store.activeGenerationId()!!
        secrets.setKeys.clear()
        val replacement = data(
            endpoint("ep-1", "Replaced", "replacement/model"),
            favorite("ep-1", "replacement/model")
        )
        val replacementGeneration = store.stage(replacement)!!

        assertTrue(store.activate(replacementGeneration))
        assertEquals("secret-one", endpointPreferences.getApiEndpoint("ep-1").apiKey)
        assertEquals("secret-delete", secrets.get("ep-2_api_key"))
        assertTrue(secrets.setKeys.isEmpty())

        assertTrue(store.activate(originalGeneration))
        assertEquals("secret-one", endpointPreferences.getApiEndpoint("ep-1").apiKey)
        assertEquals("secret-delete", endpointPreferences.getApiEndpoint("ep-2").apiKey)
        assertTrue(secrets.setKeys.isEmpty())
    }

    @Test
    fun definitionDeletionRemovesItsFavoritesAtomicallyButPreservesItsCredential() {
        val preferences = FakeSharedPreferences()
        val secrets = FakeSecrets()
        val endpoints = ApiEndpointPreferences.createForTest(preferences, secrets)
        endpoints.setApiEndpoint(
            ApiEndpointObject("One", "https://one.example/v1", "secret-one", id = "ep-1")
        )
        FavoriteModelsPreferences.createForTest(preferences).setFavoriteModels(
            arrayListOf(favorite("ep-1", "model-a"))
        )
        secrets.setKeys.clear()

        endpoints.deleteApiEndpointDefinition("ep-1")

        val active = ModelEndpointStateGenerationStore.createForTest(preferences).read()!!
        assertTrue(active.endpoints.isEmpty())
        assertTrue(active.favorites.isEmpty())
        assertEquals("secret-one", secrets.get("ep-1_api_key"))
        assertTrue(secrets.setKeys.isEmpty())
    }

    @Test
    fun orphanLegacyFavoritesAreNotPublishedIntoTheActiveGeneration() {
        val definitions = FakeSharedPreferences()
        val favorites = FakeSharedPreferences()
        favorites.edit().putString(
            "favorite_models",
            "[{\"endpointId\":\"missing\",\"modelId\":\"model-a\"}]"
        ).commit()

        val active = ModelEndpointStateGenerationStore.createForTest(definitions, favorites).read()!!

        assertTrue(active.endpoints.isEmpty())
        assertTrue(active.favorites.isEmpty())
    }

    private class FakeSecrets : ApiEndpointPreferences.SecretStore {
        val values = HashMap<String, String>()
        val setKeys = ArrayList<String>()
        override fun get(key: String): String = values[key].orEmpty()
        override fun set(key: String, value: String) {
            setKeys.add(key)
            values[key] = value
        }
    }

    private fun data(
        endpoint: ModelEndpointPortableCodec.Endpoint,
        favorite: Map<String, String>
    ) = ModelEndpointPortableCodec.Data(listOf(endpoint), listOf(favorite))

    private fun favorite(endpointId: String, modelId: String) = linkedMapOf(
        "endpointId" to endpointId,
        "modelId" to modelId,
        "routingType" to "automatic"
    )

    private fun endpoint(
        id: String,
        label: String,
        model: String
    ) = ModelEndpointPortableCodec.Endpoint(
        id = id,
        label = label,
        host = "https://example.com/v1",
        chatEndpoint = "/chat/completions",
        speechEndpoint = "/audio/speech",
        authType = "bearer",
        model = model,
        temperature = 0.7,
        topP = 1.0,
        frequencyPenalty = 0.0,
        presencePenalty = 0.0,
        maxTokens = 1500,
        endSeparator = "",
        prefix = "",
        provider = "",
        connectTimeoutSeconds = 30,
        responseTimeoutSeconds = 600,
        contextWindowTokens = null,
        contextWindowModelId = "",
        imageCapabilityByModel = "",
        toolCapabilityByModel = "",
        reasoningCapabilityByModel = "",
        reasoningRejectedLevelsByModel = "",
        providerDiscoveryPath = "",
        identity = "generic",
        rejectedTtsVoices = emptyList()
    )
}
