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
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/** Applies an already validated credential-free model settings plan. */
object ModelEndpointPortableRestore {
    fun apply(context: Context, data: ModelEndpointPortableCodec.Data): Boolean = try {
        val appContext = context.applicationContext
        val endpoints = ApiEndpointPreferences.getApiEndpointPreferences(appContext)
        val desiredIds = data.endpoints.mapTo(HashSet()) { it.id }
        endpoints.getApiEndpointsList(appContext)
            .map { it.id }
            .filter { it !in desiredIds }
            .forEach(endpoints::deleteApiEndpointDefinition)

        data.endpoints.forEach { source ->
            endpoints.setApiEndpointDefinition(
                ApiEndpointObject(
                    label = source.label,
                    host = source.host,
                    apiKey = "",
                    chatEndpoint = source.chatEndpoint,
                    authType = source.authType,
                    model = source.model,
                    temperature = source.temperature.toFloat(),
                    topP = source.topP.toFloat(),
                    frequencyPenalty = source.frequencyPenalty.toFloat(),
                    presencePenalty = source.presencePenalty.toFloat(),
                    maxTokens = source.maxTokens,
                    endSeparator = source.endSeparator,
                    prefix = source.prefix,
                    provider = source.provider,
                    connectTimeoutSeconds = source.connectTimeoutSeconds,
                    responseTimeoutSeconds = source.responseTimeoutSeconds,
                    id = source.id,
                    contextWindowTokens = source.contextWindowTokens,
                    contextWindowModelId = source.contextWindowModelId,
                    imageCapabilityByModel = source.imageCapabilityByModel,
                    toolCapabilityByModel = source.toolCapabilityByModel,
                    providerDiscoveryPath = source.providerDiscoveryPath,
                    identity = source.identity,
                    reasoningCapabilityByModel = source.reasoningCapabilityByModel,
                    reasoningRejectedLevelsByModel = source.reasoningRejectedLevelsByModel,
                    speechEndpoint = source.speechEndpoint
                )
            )
            endpoints.setRejectedTtsVoices(source.id, source.rejectedTtsVoices.toSet())
        }
        FavoriteModelsPreferences.getPreferences(appContext)
            .setFavoriteModels(ArrayList(data.favorites.map { LinkedHashMap(it) }))
        true
    } catch (_: Exception) {
        false
    }
}
