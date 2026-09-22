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

/**
 * Creates the credential-free Model & Endpoint Settings artifact.
 *
 * This build has no `ModelEndpointStateGenerationStore` (a new-client class);
 * the artifact is assembled directly from the old client's authoritative live
 * stores — [ApiEndpointPreferences] (endpoint definitions and rejected TTS
 * voices) and [FavoriteModelsPreferences] (favorites, routing and provider
 * preferences) — mapped into the shared [ModelEndpointPortableCodec] data model
 * the Beta reader already understands. Stable endpoint and favorite identities
 * are preserved exactly.
 *
 * API keys, bearer tokens and any other credential are NEVER read into the
 * artifact: the codec's endpoint model carries no credential field, so per-
 * endpoint keys are dropped at the mapping boundary.
 *
 * Favorites whose endpoint no longer exists on the device (a stale reference
 * left by a since-deleted endpoint) are dropped rather than failing the whole
 * backup — such a favorite could not be restored against any endpoint anyway.
 */
object ModelEndpointPortableBackup {
    sealed class Result {
        data class Ok(val endpointCount: Int, val favoriteCount: Int) : Result()
        data object Failed : Result()
    }

    fun write(context: Context, out: File): Result = try {
        val data = readLiveStores(context.applicationContext)
        val json = ModelEndpointPortableCodec.encode(data)
        out.writeText(json, Charsets.UTF_8)
        Result.Ok(data.endpoints.size, data.favorites.size)
    } catch (_: Exception) {
        Result.Failed
    }

    private fun readLiveStores(context: Context): ModelEndpointPortableCodec.Data {
        val endpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(context)
        val endpoints = endpointPreferences.getApiEndpointsList(context).map { endpoint ->
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
                rejectedTtsVoices = endpointPreferences.getRejectedTtsVoices(endpoint.id).toList()
            )
        }

        val endpointIds = endpoints.mapTo(HashSet()) { it.id }
        val seenFavorites = HashSet<Pair<String, String>>()
        val favorites = FavoriteModelsPreferences.getPreferences(context).getFavoriteModels()
            .map { favorite -> favorite.filterKeys { it in FAVORITE_KEYS } }
            .filter { favorite ->
                val endpointId = favorite["endpointId"].orEmpty()
                val modelId = favorite["modelId"].orEmpty()
                endpointId.isNotBlank() && modelId.isNotBlank() &&
                    endpointId in endpointIds && seenFavorites.add(endpointId to modelId)
            }

        return ModelEndpointPortableCodec.Data(endpoints, favorites)
    }

    /** The favorite keys the shared codec recognizes; any legacy key outside
     *  this set is dropped so it cannot fail artifact validation. */
    private val FAVORITE_KEYS = setOf(
        "modelId", "endpointId", "routingType", "selectedProvider", "allowFallbacks",
        "providerOrder", "ignoredProviders", "reasoningEffort", "showReasoning", "streaming",
        "temperature", "topP", "frequencyPenalty", "presencePenalty"
    )
}
