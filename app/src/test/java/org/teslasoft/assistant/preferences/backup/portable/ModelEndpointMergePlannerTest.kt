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

package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEndpointMergePlannerTest {
    @Test
    fun identicalStableIdentityIsSkippedAndNewIdentityIsAdded() {
        val current = data(endpoint("ep-1", "Current"), favorite("ep-1", "model-a", "automatic"))
        val backup = ModelEndpointPortableCodec.Data(
            endpoints = listOf(endpoint("ep-1", "Current"), endpoint("ep-2", "Backup")),
            favorites = listOf(
                favorite("ep-1", "model-a", "automatic"),
                favorite("ep-2", "model-b", "preferred")
            )
        )

        val planned = ModelEndpointMergePlanner.merge(current, backup)

        assertEquals(listOf("ep-1", "ep-2"), planned.data.endpoints.map { it.id })
        assertEquals(2, planned.report.identicalSkipped)
        assertEquals(1, planned.report.addedEndpoints)
        assertEquals(1, planned.report.addedFavorites)
        assertTrue(planned.report.conflicts.isEmpty())
    }

    @Test
    fun divergentStableIdentityKeepsCurrentAndReportsConflict() {
        val currentEndpoint = endpoint("ep-1", "Current")
        val currentFavorite = favorite("ep-1", "model-a", "automatic")
        val planned = ModelEndpointMergePlanner.merge(
            data(currentEndpoint, currentFavorite),
            data(endpoint("ep-1", "Backup"), favorite("ep-1", "model-a", "only"))
        )

        assertEquals(currentEndpoint, planned.data.endpoints.single())
        assertEquals(currentFavorite, planned.data.favorites.single())
        assertEquals(
            listOf(
                ModelEndpointMergePlanner.Conflict(ModelEndpointMergePlanner.Kind.ENDPOINT, "ep-1"),
                ModelEndpointMergePlanner.Conflict(
                    ModelEndpointMergePlanner.Kind.FAVORITE,
                    "ep-1\u0000model-a"
                )
            ),
            planned.report.conflicts
        )
    }

    private fun data(
        endpoint: ModelEndpointPortableCodec.Endpoint,
        favorite: Map<String, String>
    ) = ModelEndpointPortableCodec.Data(listOf(endpoint), listOf(favorite))

    private fun favorite(endpointId: String, modelId: String, routing: String) = linkedMapOf(
        "endpointId" to endpointId,
        "modelId" to modelId,
        "routingType" to routing
    )

    private fun endpoint(id: String, label: String) = ModelEndpointPortableCodec.Endpoint(
        id = id,
        label = label,
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
