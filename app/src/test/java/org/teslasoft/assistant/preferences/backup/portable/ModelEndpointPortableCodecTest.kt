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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEndpointPortableCodecTest {
    @Test
    fun roundTripKeepsEndpointFavoriteAndProviderSettings() {
        val data = fixture()
        val encoded = ModelEndpointPortableCodec.encode(data)

        assertFalse(encoded.contains("secret-value"))
        val parsed = ModelEndpointPortableCodec.parse(encoded)
        assertTrue(parsed is ModelEndpointPortableCodec.Result.Ok)
        assertEquals(data, (parsed as ModelEndpointPortableCodec.Result.Ok).data)
    }

    @Test
    fun parserRejectsAnInjectedCredentialField() {
        val root = JSONObject(ModelEndpointPortableCodec.encode(fixture()))
        root.getJSONArray("endpoints").getJSONObject(0).put("api_key", "secret-value")

        assertTrue(
            ModelEndpointPortableCodec.parse(root.toString())
                is ModelEndpointPortableCodec.Result.Rejected
        )
    }

    @Test
    fun duplicateFavoriteIdentityIsRejected() {
        val favorite = fixture().favorites.single()
        assertTrue(
            runCatching {
                ModelEndpointPortableCodec.encode(
                    fixture().copy(favorites = listOf(favorite, favorite))
                )
            }.isFailure
        )
    }

    private fun fixture() = ModelEndpointPortableCodec.Data(
        endpoints = listOf(
            ModelEndpointPortableCodec.Endpoint(
                id = "ep-00000000-0000-4000-8000-000000000001",
                label = "OpenRouter",
                host = "https://openrouter.ai/api/v1",
                chatEndpoint = "/chat/completions",
                speechEndpoint = "/audio/speech",
                authType = "bearer",
                model = "example/model",
                temperature = 0.6,
                topP = 0.9,
                frequencyPenalty = 0.1,
                presencePenalty = 0.2,
                maxTokens = 2048,
                endSeparator = "",
                prefix = "",
                provider = "OpenRouter",
                connectTimeoutSeconds = 30,
                responseTimeoutSeconds = 600,
                contextWindowTokens = 131072,
                contextWindowModelId = "example/model",
                imageCapabilityByModel = "{}",
                toolCapabilityByModel = "{}",
                reasoningCapabilityByModel = "{}",
                reasoningRejectedLevelsByModel = "{}",
                providerDiscoveryPath = "/models/{model}/endpoints",
                identity = "openrouter",
                rejectedTtsVoices = listOf("voice-old")
            )
        ),
        favorites = listOf(
            linkedMapOf(
                "modelId" to "example/model",
                "endpointId" to "ep-00000000-0000-4000-8000-000000000001",
                "routingType" to "preferred",
                "selectedProvider" to "Provider A",
                "allowFallbacks" to "true",
                "providerOrder" to "[\"Provider A\",\"Provider B\"]",
                "ignoredProviders" to "[\"Provider C\"]",
                "temperature" to "0.6"
            )
        )
    )
}
