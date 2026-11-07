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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.portable.ModelEndpointPortableCodec
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity

class ModelSettingsStorageTest {

    private val identity = ModelIdentity("ep-1", "model-a")

    @Test
    fun recentUseNeverSavesTheChatsParameters() {
        val recents = RecentModelsPreferences(FakeSharedPreferences())
        recents.record(FavoriteModelObject("model-a", "ep-1").apply { temperature = 1.5f })

        assertFalse(recents.models().single().hasSamplingParameters())
        assertNull(recents.savedParameters(identity))
    }

    @Test
    fun recentSavedParametersSurviveLaterUseAndLeaveWithTheEntry() {
        val recents = RecentModelsPreferences(FakeSharedPreferences())
        recents.setSavedParameters(identity, FavoriteModelObject("model-a", "ep-1").apply {
            streaming = false
            temperature = 0.4f
        })
        recents.record(FavoriteModelObject("model-a", "ep-1").apply { temperature = 1.9f })

        assertEquals(0.4f, recents.savedParameters(identity)?.temperature)
        assertEquals(0.4f, recents.models().single().temperature)

        recents.setLimit(1)
        recents.record(FavoriteModelObject("model-b", "ep-1"))
        recents.setLimit(20)
        recents.record(FavoriteModelObject("model-a", "ep-1"))
        assertNull(recents.savedParameters(identity))
    }

    @Test
    fun restoringRecentDefaultsRemovesItsSavedParameters() {
        val recents = RecentModelsPreferences(FakeSharedPreferences())
        recents.setSavedParameters(identity, FavoriteModelObject("model-a", "ep-1").apply { topP = 0.5f })
        recents.setSavedParameters(identity, null)
        assertNull(recents.savedParameters(identity))
        assertEquals(listOf("model-a"), recents.models().map { it.modelId })
    }

    @Test
    fun aRecentModelBecomingAFavoriteBringsItsSavedParameters() {
        val state = FakeSharedPreferences()
        installEndpoint(state)
        val recents = RecentModelsPreferences(FakeSharedPreferences())
        recents.setSavedParameters(identity, FavoriteModelObject("model-a", "ep-1").apply { temperature = 0.3f })
        val favorites = FavoriteModelsPreferences.createForTest(state, recentSavedParameters = recents::savedParameters)

        favorites.addFavoriteIfAbsent(FavoriteModelObject("model-a", "ep-1"))

        assertEquals(0.3f, favorites.getFavorite("model-a", "ep-1")?.temperature)
    }

    @Test
    fun favoriteProvidersSurviveRoutingSavesAndAreNeverAddedImplicitly() {
        val state = FakeSharedPreferences()
        installEndpoint(state)
        val favorites = FavoriteModelsPreferences.createForTest(state)

        assertFalse(favorites.setFavoriteProviders("model-a", "ep-1", listOf("deepinfra")))
        assertTrue(favorites.getFavoriteModels().isEmpty())

        favorites.addFavoriteModel(FavoriteModelObject("model-a", "ep-1"))
        assertTrue(favorites.setFavoriteProviders("model-a", "ep-1", listOf("deepinfra", "DeepInfra", "chutes")))
        favorites.addFavoriteModel(FavoriteModelObject("model-a", "ep-1", FavoriteModelObject.ROUTING_ONLY, "chutes"))

        val saved = favorites.getFavorite("model-a", "ep-1")!!
        assertEquals(listOf("deepinfra", "chutes"), saved.favoriteProviders)
        assertEquals("chutes", saved.selectedProvider)

        favorites.setFavoriteProviders("model-a", "ep-1", emptyList())
        assertTrue(favorites.getFavorite("model-a", "ep-1")!!.favoriteProviders.isEmpty())
    }

    @Test
    fun clearingAFavoritesParametersKeepsEverythingElse() {
        val state = FakeSharedPreferences()
        installEndpoint(state)
        val favorites = FavoriteModelsPreferences.createForTest(state)
        favorites.addFavoriteModel(FavoriteModelObject("model-a", "ep-1", FavoriteModelObject.ROUTING_ONLY, "chutes").apply {
            temperature = 1.1f
            streaming = false
        })

        favorites.clearSamplingParameters("model-a", "ep-1")

        val saved = favorites.getFavorite("model-a", "ep-1")!!
        assertFalse(saved.hasSamplingParameters())
        assertEquals("chutes", saved.selectedProvider)
    }

    @Test
    fun favoriteProvidersTravelInBackups() {
        val favorite = linkedMapOf(
            "endpointId" to "ep-1",
            "modelId" to "model-a",
            "favoriteProviders" to "[\"deepinfra\",\"chutes\"]"
        )
        val data = ModelEndpointPortableCodec.Data(listOf(endpoint()), listOf(favorite))

        val parsed = ModelEndpointPortableCodec.parse(ModelEndpointPortableCodec.encode(data))

        assertTrue(parsed is ModelEndpointPortableCodec.Result.Ok)
        assertEquals(
            "[\"deepinfra\",\"chutes\"]",
            (parsed as ModelEndpointPortableCodec.Result.Ok).data.favorites.single()["favoriteProviders"]
        )
    }

    @Test
    fun recentModelsTravelInBackupsWithTheirSettings() {
        val recent = FavoriteModelObject("model-a", "ep-1", FavoriteModelObject.ROUTING_ONLY, "chutes").apply {
            temperature = 0.2f
            favoriteProviders = listOf("chutes")
        }
        val gone = FavoriteModelObject("model-b", "ep-gone")
        val snapshot = org.teslasoft.assistant.preferences.backup.portable.RecentModelsBackup.Snapshot(
            12, listOf(recent, gone), listOf(Triple("ep-1", "model-a", listOf("chutes", "novita"))), mapOf("chutes" to "Chutes")
        )
        val data = ModelEndpointPortableCodec.Data(listOf(endpoint()), emptyList(), snapshot)

        val parsed = (ModelEndpointPortableCodec.parse(ModelEndpointPortableCodec.encode(data))
            as ModelEndpointPortableCodec.Result.Ok).data.recents!!

        assertEquals(12, parsed.limit)
        assertEquals(listOf("model-a"), parsed.models.map { it.modelId })
        assertEquals(0.2f, parsed.models.single().temperature)
        assertEquals(listOf("chutes"), parsed.models.single().favoriteProviders)
        assertEquals("chutes", parsed.models.single().selectedProvider)
        assertEquals(listOf("chutes", "novita"), parsed.providers.single().third)
        assertEquals("Chutes", parsed.names["chutes"])
    }

    @Test
    fun backupsWithoutRecentModelsStillRestore() {
        val data = ModelEndpointPortableCodec.Data(listOf(endpoint()), emptyList())
        val parsed = ModelEndpointPortableCodec.parse(ModelEndpointPortableCodec.encode(data))
        assertTrue(parsed is ModelEndpointPortableCodec.Result.Ok)
        assertNull((parsed as ModelEndpointPortableCodec.Result.Ok).data.recents)
    }

    @Test
    fun recentFavoriteProvidersAreKeptAcrossUse() {
        val recents = RecentModelsPreferences(FakeSharedPreferences())
        recents.setFavoriteProviders(identity, listOf("chutes"))
        recents.record(FavoriteModelObject("model-a", "ep-1", FavoriteModelObject.ROUTING_ONLY, "novita"))
        assertEquals(listOf("chutes"), recents.favoriteProviders(identity))
        assertEquals("novita", recents.models().single().selectedProvider)
    }

    @Test
    fun aProviderTheFavoriteDoesNotHoldIsATrial() {
        val favorite = FavoriteModelObject("model-a", "ep-1", FavoriteModelObject.ROUTING_ONLY, "deepinfra").apply {
            favoriteProviders = listOf("chutes")
        }
        fun only(slug: String) = FavoriteModelObject("model-a", "ep-1", FavoriteModelObject.ROUTING_ONLY, slug)

        assertTrue(ChatModelRouting.isTrial(favorite, only("novita")))
        assertFalse(ChatModelRouting.isTrial(favorite, only("Chutes")))
        assertFalse(ChatModelRouting.isTrial(favorite, only("deepinfra")))
        assertFalse(ChatModelRouting.isTrial(favorite, FavoriteModelObject("model-a", "ep-1")))
    }

    @Test
    fun aCopyIsIndependentOfTheOriginal() {
        val original = FavoriteModelObject("model-a", "ep-1", providerOrder = listOf("a"))
        val copy = original.copy()
        copy.providerOrder = listOf("b")
        copy.selectedProvider = "x"
        assertEquals(listOf("a"), original.providerOrder)
        assertEquals("", original.selectedProvider)
        assertTrue(ChatModelRouting.sameRouting(original, original.copy()))
        assertFalse(ChatModelRouting.sameRouting(original, copy))
    }

    private fun installEndpoint(state: FakeSharedPreferences) {
        assertTrue(
            ModelEndpointStateGenerationStore.createForTest(state)
                .install(ModelEndpointPortableCodec.Data(listOf(endpoint()), emptyList()))
        )
    }

    private fun endpoint() = ModelEndpointPortableCodec.Endpoint(
        id = "ep-1",
        label = "One",
        host = "https://example.com/v1",
        chatEndpoint = "/chat/completions",
        speechEndpoint = "/audio/speech",
        authType = "bearer",
        model = "model-a",
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
