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
import org.teslasoft.assistant.preferences.backup.portable.RecentModelsBackup
import org.teslasoft.assistant.preferences.backup.portable.DedicatedModelsBackup
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/** The Summarizer and Memory Scanner keep their own favorites and Recent
 *  lists, apart from chat and from each other. */
class DedicatedModelListsTest {

    @Test
    fun summarizerFavoritesAreKeptApartFromChatFavorites() {
        val state = FakeSharedPreferences()
        installEndpoints(state, "ep-1")
        val chat = FavoriteModelsPreferences.createForTest(state)
        val summarizer = FavoriteModelsPreferences.createSeparateForTest(FakeSharedPreferences(), { setOf("ep-1") })

        summarizer.addFavoriteModel(FavoriteModelObject("model-a", "ep-1", FavoriteModelObject.ROUTING_ONLY, "chutes"))

        assertTrue(summarizer.isFavorite("model-a", "ep-1"))
        assertFalse(chat.isFavorite("model-a", "ep-1"))
        assertEquals("chutes", summarizer.getFavorite("model-a", "ep-1")?.selectedProvider)
    }

    @Test
    fun summarizerFavoritesOfADeletedProfileAreHiddenAndDroppedOnTheNextWrite() {
        var endpoints = setOf("ep-1", "ep-2")
        val prefs = FakeSharedPreferences()
        val summarizer = FavoriteModelsPreferences.createSeparateForTest(prefs, { endpoints })
        summarizer.addFavoriteModel(FavoriteModelObject("model-a", "ep-1"))
        summarizer.addFavoriteModel(FavoriteModelObject("model-b", "ep-2"))

        endpoints = setOf("ep-1")
        assertEquals(listOf("model-a"), summarizer.getFavoriteModels().map { it["modelId"] })

        endpoints = setOf("ep-1", "ep-2")
        summarizer.addFavoriteModel(FavoriteModelObject("model-c", "ep-1"))
        endpoints = setOf("ep-1")
        summarizer.setFavoriteModels(summarizer.getFavoriteModels())
        endpoints = setOf("ep-1", "ep-2")
        assertEquals(setOf("model-a", "model-c"), summarizer.getFavoriteModels().map { it["modelId"] }.toSet())
    }

    @Test
    fun aModelForAProfileThatDoesNotExistIsNeverSaved() {
        val summarizer = FavoriteModelsPreferences.createSeparateForTest(FakeSharedPreferences(), { setOf("ep-1") })
        summarizer.addFavoriteModel(FavoriteModelObject("model-a", "ep-missing"))
        assertTrue(summarizer.getFavoriteModels().isEmpty())
    }

    @Test
    fun summarizerFavoriteKeepsItsSavedParametersAndClearsThem() {
        val summarizer = FavoriteModelsPreferences.createSeparateForTest(FakeSharedPreferences(), { setOf("ep-1") })
        summarizer.addFavoriteModel(FavoriteModelObject("model-a", "ep-1").apply {
            streaming = true
            temperature = 0.1f
        })
        assertEquals(0.1f, summarizer.getFavorite("model-a", "ep-1")?.temperature)
        assertEquals(true, summarizer.getFavorite("model-a", "ep-1")?.streaming)

        summarizer.clearSamplingParameters("model-a", "ep-1")
        assertFalse(summarizer.getFavorite("model-a", "ep-1")!!.hasSamplingParameters())
    }

    @Test
    fun theSummarizersRecentListFollowsTheSharedSizeSetting() {
        val chatFile = FakeSharedPreferences()
        val chat = RecentModelsPreferences(chatFile)
        val summarizer = RecentModelsPreferences(FakeSharedPreferences(), chatFile)
        summarizer.record(FavoriteModelObject("model-a", "ep-1"))
        summarizer.record(FavoriteModelObject("model-b", "ep-1"))

        chat.setLimit(1)
        summarizer.trimToLimit()

        assertEquals(1, summarizer.limit())
        assertEquals(listOf("model-b"), summarizer.models().map { it.modelId })
        assertTrue(chat.models().isEmpty())
    }

    @Test
    fun restoringTheSummarizersRecentListLeavesTheSharedSizeSettingAlone() {
        val chatFile = FakeSharedPreferences()
        RecentModelsPreferences(chatFile).setLimit(7)
        val summarizer = RecentModelsPreferences(FakeSharedPreferences(), chatFile)

        summarizer.replaceAll(3, listOf(FavoriteModelObject("model-a", "ep-1")))

        assertEquals(7, summarizer.limit())
        assertEquals(listOf("model-a"), summarizer.models().map { it.modelId })
    }

    @Test
    fun summarizerListsTravelInBackups() {
        val favorite = mapOf(
            "modelId" to "model-a", "endpointId" to "ep-1", "routingType" to FavoriteModelObject.ROUTING_ONLY,
            "selectedProvider" to "chutes", "temperature" to "0.2"
        )
        val gone = mapOf("modelId" to "model-x", "endpointId" to "ep-gone")
        val recents = RecentModelsBackup.Snapshot(
            20, listOf(FavoriteModelObject("model-b", "ep-1").apply { topP = 0.5f }), emptyList(), emptyMap()
        )
        val data = ModelEndpointPortableCodec.Data(
            listOf(endpoint("ep-1")), emptyList(), null,
            DedicatedModelsBackup.Snapshot(listOf(favorite, gone), recents)
        )

        val parsed = (ModelEndpointPortableCodec.parse(ModelEndpointPortableCodec.encode(data))
            as ModelEndpointPortableCodec.Result.Ok).data

        assertNull(parsed.recents)
        val summarizer = parsed.summarizer!!
        assertEquals(listOf(favorite), summarizer.favorites)
        assertEquals(0.5f, summarizer.recents.models.single().topP)
    }

    @Test
    fun backupsWithoutSummarizerListsStillRestore() {
        val data = ModelEndpointPortableCodec.Data(listOf(endpoint("ep-1")), emptyList())
        val parsed = ModelEndpointPortableCodec.parse(ModelEndpointPortableCodec.encode(data))
        assertTrue(parsed is ModelEndpointPortableCodec.Result.Ok)
        assertNull((parsed as ModelEndpointPortableCodec.Result.Ok).data.summarizer)
    }

    @Test
    fun aMalformedSummarizerSectionIsRejected() {
        val json = org.json.JSONObject(
            ModelEndpointPortableCodec.encode(ModelEndpointPortableCodec.Data(listOf(endpoint("ep-1")), emptyList()))
        ).put("summarizer_models", org.json.JSONObject().put("favorites", org.json.JSONArray()))
        assertTrue(ModelEndpointPortableCodec.parse(json.toString()) is ModelEndpointPortableCodec.Result.Rejected)
    }

    @Test
    fun anUnknownTopLevelFieldIsStillRejected() {
        val json = org.json.JSONObject(
            ModelEndpointPortableCodec.encode(ModelEndpointPortableCodec.Data(listOf(endpoint("ep-1")), emptyList()))
        ).put("api_key", "secret")
        assertTrue(ModelEndpointPortableCodec.parse(json.toString()) is ModelEndpointPortableCodec.Result.Rejected)
    }

    @Test
    fun aMergeKeepsThisDevicesSummarizerFavoritesAndAddsTheBackupsOthers() {
        val empty = RecentModelsBackup.Snapshot(20, emptyList(), emptyList(), emptyMap())
        val mine = mapOf("modelId" to "model-a", "endpointId" to "ep-1", "selectedProvider" to "mine")
        val theirs = mapOf("modelId" to "model-a", "endpointId" to "ep-1", "selectedProvider" to "theirs")
        val other = mapOf("modelId" to "model-b", "endpointId" to "ep-1")
        val merged = DedicatedModelsBackup.merge(
            DedicatedModelsBackup.Snapshot(listOf(mine), empty),
            DedicatedModelsBackup.Snapshot(listOf(theirs, other), empty)
        )!!
        assertEquals(listOf(mine, other), merged.favorites)
    }

    @Test
    fun theRestoreFingerprintIgnoresTheSummarizersRecentHistory() {
        val favorite = mapOf("modelId" to "model-a", "endpointId" to "ep-1")
        val a = DedicatedModelsBackup.Snapshot(
            listOf(favorite), RecentModelsBackup.Snapshot(20, listOf(FavoriteModelObject("m", "ep-1")), emptyList(), emptyMap())
        )
        val b = a.copy(recents = RecentModelsBackup.Snapshot(20, emptyList(), emptyList(), emptyMap()))
        assertEquals(DedicatedModelsBackup.withoutHistory(a), DedicatedModelsBackup.withoutHistory(b))
    }

    @Test
    fun theRecommendedParametersSuitSummarizingAndScanning() {
        for (scope in listOf(ModelPickerScope.SUMMARIZER, ModelPickerScope.MEMORY_SCANNER)) {
            val recommended = RecommendedModelParameters.forScope(scope)
            assertEquals(false, recommended.streaming)
            assertEquals(0.3f, recommended.temperature)
            assertEquals(1.0f, recommended.topP)
            assertEquals(0.0f, recommended.frequencyPenalty)
            assertEquals(0.0f, recommended.presencePenalty)
        }
    }

    @Test
    fun eachJobKeepsItsOwnFiles() {
        assertEquals(setOf("", "summarizer_", "memory_scanner_"), ModelPickerScope.entries.map { it.filePrefix }.toSet())
        assertFalse(ModelPickerScope.CHAT.isDedicated)
        assertTrue(ModelPickerScope.SUMMARIZER.isDedicated && ModelPickerScope.MEMORY_SCANNER.isDedicated)
    }

    @Test
    fun theMemoryScannersListsTravelInBackupsApartFromTheSummarizers() {
        val empty = RecentModelsBackup.Snapshot(20, emptyList(), emptyList(), emptyMap())
        val summarizer = DedicatedModelsBackup.Snapshot(listOf(mapOf("modelId" to "model-s", "endpointId" to "ep-1")), empty)
        val scanner = DedicatedModelsBackup.Snapshot(listOf(mapOf("modelId" to "model-m", "endpointId" to "ep-1")), empty)
        val data = ModelEndpointPortableCodec.Data(listOf(endpoint("ep-1")), emptyList(), null, summarizer, scanner)

        val parsed = (ModelEndpointPortableCodec.parse(ModelEndpointPortableCodec.encode(data))
            as ModelEndpointPortableCodec.Result.Ok).data

        assertEquals(listOf("model-s"), parsed.summarizer!!.favorites.map { it["modelId"] })
        assertEquals(listOf("model-m"), parsed.memoryScanner!!.favorites.map { it["modelId"] })
    }

    private fun installEndpoints(state: FakeSharedPreferences, vararg ids: String) {
        assertTrue(
            ModelEndpointStateGenerationStore.createForTest(state)
                .install(ModelEndpointPortableCodec.Data(ids.map(::endpoint), emptyList()))
        )
    }

    private fun endpoint(id: String) = ModelEndpointPortableCodec.Endpoint(
        id = id,
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
