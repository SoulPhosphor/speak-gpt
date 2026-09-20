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

import android.content.Context
import android.content.SharedPreferences
import org.teslasoft.assistant.imagegen.ToolCapabilityStore
import org.teslasoft.assistant.preferences.backup.portable.ModelEndpointPortableCodec
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.includes.ImageCapabilityStore
import org.teslasoft.assistant.reasoning.ReasoningCapabilityStore
import org.teslasoft.assistant.reasoning.RejectedReasoningLevelStore
import org.teslasoft.assistant.util.StableId

class ApiEndpointPreferences private constructor(
    private val state: ModelEndpointStateGenerationStore,
    private val secrets: SecretStore
) {
    companion object {
        private var apiEndpointPreferences: ApiEndpointPreferences? = null

        fun getApiEndpointPreferences(context: Context): ApiEndpointPreferences {
            if (apiEndpointPreferences == null) {
                apiEndpointPreferences = ApiEndpointPreferences(
                    ModelEndpointStateGenerationStore.get(context),
                    EncryptedSecretStore(context.applicationContext)
                )
            }
            return apiEndpointPreferences!!
        }

        /** Test seam: inject in-memory definition/favorite preferences and secrets. */
        internal fun createForTest(
            preferences: SharedPreferences,
            secrets: SecretStore,
            legacyFavoritePreferences: SharedPreferences = preferences
        ): ApiEndpointPreferences = ApiEndpointPreferences(
            ModelEndpointStateGenerationStore.createForTest(preferences, legacyFavoritePreferences),
            secrets
        )
    }

    /** API keys remain encrypted and keyed only by the endpoint's stable id. */
    interface SecretStore {
        fun get(key: String): String
        fun set(key: String, value: String)
    }

    private class EncryptedSecretStore(private val appContext: Context) : SecretStore {
        override fun get(key: String): String =
            EncryptedPreferences.getEncryptedPreference(appContext, "api_endpoint", key)
        override fun set(key: String, value: String) =
            EncryptedPreferences.setEncryptedPreference(appContext, "api_endpoint", key, value)
    }

    private var listeners: ArrayList<OnApiEndpointChangeListener> = ArrayList()

    fun getRejectedTtsVoices(endpointId: String): Set<String> =
        state.read()?.endpoints?.firstOrNull { it.id == endpointId }
            ?.rejectedTtsVoices?.toSet().orEmpty()

    fun rejectTtsVoice(endpointId: String, voiceId: String) {
        if (voiceId.isBlank()) return
        val updated = state.update { current ->
            current.copy(endpoints = current.endpoints.map { endpoint ->
                if (endpoint.id == endpointId) endpoint.copy(
                    rejectedTtsVoices = (endpoint.rejectedTtsVoices + voiceId).distinct().sorted()
                ) else endpoint
            })
        }
        if (updated) notifyChanged()
    }

    fun getApiEndpoint(context: Context, id: String): ApiEndpointObject = getApiEndpoint(id)

    internal fun getApiEndpoint(id: String): ApiEndpointObject {
        val endpoint = state.read()?.endpoints?.firstOrNull { it.id == id }
        return endpoint?.toObject(secrets.get(id + "_api_key"))
            ?: ApiEndpointObject("", "", secrets.get(id + "_api_key"), id = id)
    }

    fun deleteApiEndpoint(context: Context, id: String) = deleteApiEndpoint(id)

    internal fun deleteApiEndpoint(id: String) = deleteApiEndpoint(id, deleteCredential = true)

    /** Portable restore removes only the non-secret definition. */
    internal fun deleteApiEndpointDefinition(id: String) =
        deleteApiEndpoint(id, deleteCredential = false)

    private fun deleteApiEndpoint(id: String, deleteCredential: Boolean) {
        check(state.update { current ->
            current.copy(
                endpoints = current.endpoints.filterNot { it.id == id },
                favorites = current.favorites.filterNot { it["endpointId"] == id }
            )
        }) { "Unable to publish endpoint deletion" }
        if (deleteCredential) secrets.set(id + "_api_key", "null")
        notifyChanged()
    }

    fun setApiEndpoint(context: Context, endpoint: ApiEndpointObject): String = setApiEndpoint(endpoint)

    /** Save the complete non-secret definition atomically, then update its credential. */
    internal fun setApiEndpoint(endpoint: ApiEndpointObject): String =
        setApiEndpoint(endpoint, writeCredential = true, exactPortableIdentity = false)

    /** Portable writes never read, overwrite, move, or delete a credential. */
    internal fun setApiEndpointDefinition(endpoint: ApiEndpointObject): String =
        setApiEndpoint(endpoint, writeCredential = false, exactPortableIdentity = true)

    private fun setApiEndpoint(
        endpoint: ApiEndpointObject,
        writeCredential: Boolean,
        exactPortableIdentity: Boolean
    ): String {
        val id = StableId.resolve(endpoint.id, "ep-")
        endpoint.id = id
        check(state.update { current ->
            val previous = current.endpoints.firstOrNull { it.id == id }
            val identity = if (exactPortableIdentity) {
                endpoint.identity
            } else if (previous?.identity == ApiEndpointObject.IDENTITY_OPENROUTER ||
                ApiEndpointObject.isRecognizedOpenRouterUrl(endpoint.host)
            ) {
                ApiEndpointObject.IDENTITY_OPENROUTER
            } else {
                ApiEndpointObject.IDENTITY_GENERIC
            }
            endpoint.identity = identity
            val replacement = endpoint.toPortable(identity, previous?.rejectedTtsVoices.orEmpty())
            val endpoints = current.endpoints.toMutableList()
            val index = endpoints.indexOfFirst { it.id == id }
            if (index >= 0) endpoints[index] = replacement else endpoints.add(replacement)
            current.copy(endpoints = endpoints)
        }) { "Unable to publish endpoint definition" }
        if (writeCredential) secrets.set(id + "_api_key", endpoint.apiKey)
        notifyChanged()
        return id
    }

    fun editEndpoint(context: Context, label: String, endpoint: ApiEndpointObject) {
        setApiEndpoint(endpoint)
    }

    fun migrateFromLegacyEndpoint(context: Context) {
        if (getApiEndpointsList().isEmpty()) {
            val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val host = sp.getString("custom_host", "https://api.openai.com/v1/")!!
            val apiKey = EncryptedPreferences.getEncryptedPreference(context, "api", "api_key")
            setApiEndpoint(
                ApiEndpointObject(
                    "Default", host, apiKey, id = ApiEndpointObject.DEFAULT_ENDPOINT_ID
                )
            )
        }
    }

    fun getApiEndpointsList(context: Context): ArrayList<ApiEndpointObject> = getApiEndpointsList()

    internal fun getApiEndpointsList(): ArrayList<ApiEndpointObject> = ArrayList(
        state.read()?.endpoints.orEmpty().map { endpoint ->
            endpoint.toObject(secrets.get(endpoint.id + "_api_key"))
        }
    )

    fun getImageCapabilityByModel(id: String): String =
        state.read()?.endpoints?.firstOrNull { it.id == id }?.imageCapabilityByModel.orEmpty()

    internal fun setRejectedTtsVoices(id: String, voiceIds: Set<String>) {
        val updated = state.update { current ->
            current.copy(endpoints = current.endpoints.map { endpoint ->
                if (endpoint.id == id) endpoint.copy(
                    rejectedTtsVoices = voiceIds.filter(String::isNotBlank).distinct().sorted()
                ) else endpoint
            })
        }
        if (updated) notifyChanged()
    }

    fun setImageCapabilityByModel(id: String, capabilityJson: String) {
        val normalized = capabilityJson.takeUnless {
            it.isBlank() || it == ImageCapabilityStore.EMPTY
        }.orEmpty()
        val updated = state.update { current ->
            current.copy(endpoints = current.endpoints.map { endpoint ->
                if (endpoint.id == id) endpoint.copy(imageCapabilityByModel = normalized) else endpoint
            })
        }
        if (updated) notifyChanged()
    }

    fun getApiEndpointByUrlOrNull(context: Context, url: String): ApiEndpointObject? =
        getApiEndpointsList().firstOrNull { it.host == url }

    private fun notifyChanged() {
        listeners.forEach { it.onApiEndpointChange() }
    }

    fun interface OnApiEndpointChangeListener {
        fun onApiEndpointChange()
    }

    private fun ModelEndpointPortableCodec.Endpoint.toObject(apiKey: String) = ApiEndpointObject(
        label = label,
        host = host,
        apiKey = apiKey,
        chatEndpoint = chatEndpoint,
        authType = authType,
        model = model,
        temperature = temperature.toFloat(),
        topP = topP.toFloat(),
        frequencyPenalty = frequencyPenalty.toFloat(),
        presencePenalty = presencePenalty.toFloat(),
        maxTokens = maxTokens,
        endSeparator = endSeparator,
        prefix = prefix,
        provider = provider,
        connectTimeoutSeconds = connectTimeoutSeconds,
        responseTimeoutSeconds = responseTimeoutSeconds,
        id = id,
        contextWindowTokens = contextWindowTokens,
        contextWindowModelId = contextWindowModelId,
        imageCapabilityByModel = imageCapabilityByModel,
        toolCapabilityByModel = toolCapabilityByModel,
        providerDiscoveryPath = providerDiscoveryPath,
        identity = identity,
        reasoningCapabilityByModel = reasoningCapabilityByModel,
        reasoningRejectedLevelsByModel = reasoningRejectedLevelsByModel,
        speechEndpoint = speechEndpoint
    )

    private fun ApiEndpointObject.toPortable(
        storedIdentity: String,
        rejectedVoices: List<String>
    ): ModelEndpointPortableCodec.Endpoint {
        val contextWindow = contextWindowTokens?.takeIf {
            it > 0 && contextWindowModelId == model && model.isNotBlank()
        }
        return ModelEndpointPortableCodec.Endpoint(
            id = id,
            label = label,
            host = host,
            chatEndpoint = chatEndpoint,
            speechEndpoint = speechEndpoint,
            authType = authType,
            model = model,
            temperature = temperature.toDouble(),
            topP = topP.toDouble(),
            frequencyPenalty = frequencyPenalty.toDouble(),
            presencePenalty = presencePenalty.toDouble(),
            maxTokens = maxTokens,
            endSeparator = endSeparator,
            prefix = prefix,
            provider = provider,
            connectTimeoutSeconds = ApiEndpointObject.coerceConnectTimeoutSeconds(connectTimeoutSeconds),
            responseTimeoutSeconds = ApiEndpointObject.coerceResponseTimeoutSeconds(responseTimeoutSeconds),
            contextWindowTokens = contextWindow,
            contextWindowModelId = if (contextWindow == null) "" else model,
            imageCapabilityByModel = imageCapabilityByModel.takeUnless {
                it.isBlank() || it == ImageCapabilityStore.EMPTY
            }.orEmpty(),
            toolCapabilityByModel = toolCapabilityByModel.takeUnless {
                it.isBlank() || it == ToolCapabilityStore.EMPTY
            }.orEmpty(),
            reasoningCapabilityByModel = reasoningCapabilityByModel.takeUnless {
                it.isBlank() || it == ReasoningCapabilityStore.EMPTY
            }.orEmpty(),
            reasoningRejectedLevelsByModel = reasoningRejectedLevelsByModel.takeUnless {
                it.isBlank() || it == RejectedReasoningLevelStore.EMPTY
            }.orEmpty(),
            providerDiscoveryPath = providerDiscoveryPath,
            identity = storedIdentity,
            rejectedTtsVoices = rejectedVoices
        )
    }
}
