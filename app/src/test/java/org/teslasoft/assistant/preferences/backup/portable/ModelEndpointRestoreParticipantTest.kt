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

import android.app.Application
import android.content.Context
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.ModelEndpointStateGenerationStore

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModelEndpointRestoreParticipantTest {
    @Test
    fun applyAndRollbackSwitchTheStagedGenerationsIdempotently() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("favorite_models", Context.MODE_PRIVATE).edit().clear().commit()
        val state = ModelEndpointStateGenerationStore.get(context)
        val current = data("Old", "old/model")
        val desired = data("New", "new/model")
        assertTrue(state.install(current))
        val root = Files.createTempDirectory("endpoint-generation-restore").toFile()
        val participant = ModelEndpointRestoreParticipant(
            context,
            ModelEndpointRestoreParticipant.PreparedPlan(
                current = current,
                incoming = desired,
                desired = desired,
                report = null
            ),
            root
        )

        assertTrue(participant.validate())
        assertTrue(participant.stage())
        assertEquals(current, state.read())

        assertTrue(participant.apply())
        assertTrue(participant.apply())
        assertEquals(desired, state.read())

        assertTrue(participant.rollback())
        assertTrue(participant.rollback())
        assertEquals(current, state.read())
        participant.cleanup()
    }

    private fun data(label: String, model: String): ModelEndpointPortableCodec.Data {
        val endpointId = "ep-1"
        return ModelEndpointPortableCodec.Data(
            endpoints = listOf(
                ModelEndpointPortableCodec.Endpoint(
                    id = endpointId,
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
                    rejectedTtsVoices = listOf("voice-1")
                )
            ),
            favorites = listOf(
                linkedMapOf(
                    "endpointId" to endpointId,
                    "modelId" to model,
                    "routingType" to "automatic"
                )
            )
        )
    }
}
