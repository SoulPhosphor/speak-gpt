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

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences

/** Creates the credential-free Model & Endpoint Settings artifact. */
object ModelEndpointPortableBackup {
    sealed class Result {
        data class Ok(val endpointCount: Int, val favoriteCount: Int) : Result()
        data object Failed : Result()
    }

    fun write(context: Context, out: File): Result = try {
        val appContext = context.applicationContext
        val endpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(appContext)
        val endpoints = endpointPreferences.getApiEndpointsList(appContext)
            .sortedBy { it.id }
            .map { endpoint ->
                ModelEndpointPortableCodec.Endpoint(
                    id = endpoint.id,
                    label = endpoint.label,
                    host = endpoint.host,
                    chatEndpoint = endpoint.chatEndpoint,
                    speechEndpoint = endpoint.speechEndpoint,
                    authType = endpoint.authType,
                    model = endpoint.model,
                    temperature = endpoint.temperature.toDouble(),
                    topP = endpoint.topP.toDouble(),
                    frequencyPenalty = endpoint.frequencyPenalty.toDouble(),
                    presencePenalty = endpoint.presencePenalty.toDouble(),
                    maxTokens = endpoint.maxTokens,
                    endSeparator = endpoint.endSeparator,
                    prefix = endpoint.prefix,
                    provider = endpoint.provider,
                    connectTimeoutSeconds = endpoint.connectTimeoutSeconds,
                    responseTimeoutSeconds = endpoint.responseTimeoutSeconds,
                    contextWindowTokens = endpoint.contextWindowTokens,
                    contextWindowModelId = endpoint.contextWindowModelId,
                    imageCapabilityByModel = endpoint.imageCapabilityByModel,
                    toolCapabilityByModel = endpoint.toolCapabilityByModel,
                    reasoningCapabilityByModel = endpoint.reasoningCapabilityByModel,
                    reasoningRejectedLevelsByModel = endpoint.reasoningRejectedLevelsByModel,
                    providerDiscoveryPath = endpoint.providerDiscoveryPath,
                    identity = endpoint.identity,
                    rejectedTtsVoices = endpointPreferences.getRejectedTtsVoices(endpoint.id)
                        .sorted()
                )
            }
        val favorites = FavoriteModelsPreferences.getPreferences(appContext)
            .getFavoriteModels()
            .map { LinkedHashMap(it) }
            .sortedWith(compareBy({ it["endpointId"].orEmpty() }, { it["modelId"].orEmpty() }))
        val json = ModelEndpointPortableCodec.encode(
            ModelEndpointPortableCodec.Data(endpoints, favorites)
        )
        out.writeText(json, Charsets.UTF_8)
        Result.Ok(endpoints.size, favorites.size)
    } catch (_: Exception) {
        Result.Failed
    }
}
