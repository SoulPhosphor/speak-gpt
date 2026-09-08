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

import org.json.JSONArray
import org.json.JSONObject

/**
 * Credential-free logical contract for Model & Endpoint Settings. The
 * endpoint schema deliberately has no API-key field. Parsing rejects unknown
 * fields so a crafted artifact cannot smuggle credentials through this
 * category under an unrecognized name.
 */
object ModelEndpointPortableCodec {
    const val FORMAT = "model-endpoint-settings-v1"
    const val SCHEMA_VERSION = 1
    const val MAX_ARTIFACT_BYTES = 8L * 1024L * 1024L

    data class Endpoint(
        val id: String,
        val label: String,
        val host: String,
        val chatEndpoint: String,
        val speechEndpoint: String,
        val authType: String,
        val model: String,
        val temperature: Double,
        val topP: Double,
        val frequencyPenalty: Double,
        val presencePenalty: Double,
        val maxTokens: Int,
        val endSeparator: String,
        val prefix: String,
        val provider: String,
        val connectTimeoutSeconds: Int,
        val responseTimeoutSeconds: Int,
        val contextWindowTokens: Int?,
        val contextWindowModelId: String,
        val imageCapabilityByModel: String,
        val toolCapabilityByModel: String,
        val reasoningCapabilityByModel: String,
        val reasoningRejectedLevelsByModel: String,
        val providerDiscoveryPath: String,
        val identity: String,
        val rejectedTtsVoices: List<String>
    )

    data class Data(
        val endpoints: List<Endpoint>,
        val favorites: List<Map<String, String>>
    )

    sealed class Result {
        data class Ok(val data: Data) : Result()
        data class Rejected(val detail: String) : Result()
    }

    fun encode(data: Data): String {
        require(validate(data) == null) { validate(data) }
        val endpoints = JSONArray()
        data.endpoints.forEach { endpoint ->
            endpoints.put(
                JSONObject()
                    .put("id", endpoint.id)
                    .put("label", endpoint.label)
                    .put("host", endpoint.host)
                    .put("chat_endpoint", endpoint.chatEndpoint)
                    .put("speech_endpoint", endpoint.speechEndpoint)
                    .put("auth_type", endpoint.authType)
                    .put("model", endpoint.model)
                    .put("temperature", endpoint.temperature)
                    .put("top_p", endpoint.topP)
                    .put("frequency_penalty", endpoint.frequencyPenalty)
                    .put("presence_penalty", endpoint.presencePenalty)
                    .put("max_tokens", endpoint.maxTokens)
                    .put("end_separator", endpoint.endSeparator)
                    .put("prefix", endpoint.prefix)
                    .put("provider", endpoint.provider)
                    .put("connect_timeout_seconds", endpoint.connectTimeoutSeconds)
                    .put("response_timeout_seconds", endpoint.responseTimeoutSeconds)
                    .put("context_window_tokens", endpoint.contextWindowTokens ?: JSONObject.NULL)
                    .put("context_window_model_id", endpoint.contextWindowModelId)
                    .put("image_capability_by_model", endpoint.imageCapabilityByModel)
                    .put("tool_capability_by_model", endpoint.toolCapabilityByModel)
                    .put("reasoning_capability_by_model", endpoint.reasoningCapabilityByModel)
                    .put("reasoning_rejected_levels_by_model", endpoint.reasoningRejectedLevelsByModel)
                    .put("provider_discovery_path", endpoint.providerDiscoveryPath)
                    .put("identity", endpoint.identity)
                    .put("rejected_tts_voices", JSONArray(endpoint.rejectedTtsVoices))
            )
        }
        val favorites = JSONArray()
        data.favorites.forEach { favorite ->
            favorites.put(JSONObject().apply {
                FAVORITE_KEYS.forEach { key -> favorite[key]?.let { put(key, it) } }
            })
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("complete", true)
            .put("endpoints", endpoints)
            .put("favorites", favorites)
            .toString(2)
    }

    fun parse(json: String): Result = try {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_ARTIFACT_BYTES) {
            return Result.Rejected("artifact is too large")
        }
        val root = JSONObject(json)
        if (!hasExactKeys(root, ROOT_KEYS)) return Result.Rejected("unknown or missing top-level field")
        if (root.getString("format") != FORMAT) return Result.Rejected("unsupported format")
        if (!root.getBoolean("complete")) return Result.Rejected("artifact is incomplete")

        val endpointsJson = root.getJSONArray("endpoints")
        val endpoints = ArrayList<Endpoint>(endpointsJson.length())
        repeat(endpointsJson.length()) { index ->
            val item = endpointsJson.getJSONObject(index)
            if (!hasExactKeys(item, ENDPOINT_KEYS)) {
                return Result.Rejected("endpoint $index has unknown or missing fields")
            }
            endpoints.add(
                Endpoint(
                    id = item.getString("id"),
                    label = item.getString("label"),
                    host = item.getString("host"),
                    chatEndpoint = item.getString("chat_endpoint"),
                    speechEndpoint = item.getString("speech_endpoint"),
                    authType = item.getString("auth_type"),
                    model = item.getString("model"),
                    temperature = item.getDouble("temperature"),
                    topP = item.getDouble("top_p"),
                    frequencyPenalty = item.getDouble("frequency_penalty"),
                    presencePenalty = item.getDouble("presence_penalty"),
                    maxTokens = item.getInt("max_tokens"),
                    endSeparator = item.getString("end_separator"),
                    prefix = item.getString("prefix"),
                    provider = item.getString("provider"),
                    connectTimeoutSeconds = item.getInt("connect_timeout_seconds"),
                    responseTimeoutSeconds = item.getInt("response_timeout_seconds"),
                    contextWindowTokens = if (item.isNull("context_window_tokens")) null
                    else item.getInt("context_window_tokens"),
                    contextWindowModelId = item.getString("context_window_model_id"),
                    imageCapabilityByModel = item.getString("image_capability_by_model"),
                    toolCapabilityByModel = item.getString("tool_capability_by_model"),
                    reasoningCapabilityByModel = item.getString("reasoning_capability_by_model"),
                    reasoningRejectedLevelsByModel = item.getString("reasoning_rejected_levels_by_model"),
                    providerDiscoveryPath = item.getString("provider_discovery_path"),
                    identity = item.getString("identity"),
                    rejectedTtsVoices = strings(item.getJSONArray("rejected_tts_voices"))
                )
            )
        }

        val favoritesJson = root.getJSONArray("favorites")
        val favorites = ArrayList<Map<String, String>>(favoritesJson.length())
        repeat(favoritesJson.length()) { index ->
            val item = favoritesJson.getJSONObject(index)
            if (item.keys().asSequence().any { it !in FAVORITE_KEYS }) {
                return Result.Rejected("favorite $index has an unknown field")
            }
            val favorite = LinkedHashMap<String, String>()
            item.keys().forEach { key -> favorite[key] = item.getString(key) }
            favorites.add(favorite)
        }
        val data = Data(endpoints, favorites)
        validate(data)?.let { Result.Rejected(it) } ?: Result.Ok(data)
    } catch (_: Exception) {
        Result.Rejected("artifact is malformed")
    }

    private fun validate(data: Data): String? {
        if (data.endpoints.map { it.id }.distinct().size != data.endpoints.size) {
            return "duplicate endpoint id"
        }
        data.endpoints.forEach { endpoint ->
            if (!safeText(endpoint.id, 200) || endpoint.id.isBlank()) return "invalid endpoint id"
            if (!safeText(endpoint.label) || !safeText(endpoint.host) ||
                !safeText(endpoint.chatEndpoint) || !safeText(endpoint.speechEndpoint) ||
                !safeText(endpoint.model) || !safeText(endpoint.endSeparator) ||
                !safeText(endpoint.prefix) || !safeText(endpoint.provider) ||
                !safeText(endpoint.contextWindowModelId) || !safeText(endpoint.providerDiscoveryPath)
            ) return "invalid endpoint text"
            if (credentialBearingUrl(endpoint.host)) return "endpoint URL contains a credential"
            if (!endpoint.temperature.isFinite() || !endpoint.topP.isFinite() ||
                !endpoint.frequencyPenalty.isFinite() || !endpoint.presencePenalty.isFinite()
            ) return "invalid endpoint number"
            if (endpoint.connectTimeoutSeconds < 1 || endpoint.responseTimeoutSeconds < 1 ||
                endpoint.contextWindowTokens?.let { it < 1 } == true
            ) return "invalid endpoint limit"
            if (endpoint.authType !in setOf("bearer", "x-api-key", "api-key")) {
                return "invalid endpoint authorization type"
            }
            if (endpoint.identity !in setOf("generic", "openrouter")) return "invalid endpoint identity"
            for (capabilities in listOf(
                endpoint.imageCapabilityByModel,
                endpoint.toolCapabilityByModel,
                endpoint.reasoningCapabilityByModel,
                endpoint.reasoningRejectedLevelsByModel
            )) {
                if (capabilities.isNotBlank() && runCatching { JSONObject(capabilities) }.isFailure) {
                    return "invalid model capability settings"
                }
            }
            if (endpoint.rejectedTtsVoices.any { !safeText(it) || it.isBlank() } ||
                endpoint.rejectedTtsVoices.distinct().size != endpoint.rejectedTtsVoices.size
            ) return "invalid rejected voice list"
        }
        val identities = HashSet<Pair<String, String>>()
        data.favorites.forEach { favorite ->
            if (favorite.keys.any { it !in FAVORITE_KEYS } ||
                favorite.values.any { !safeText(it) }
            ) return "invalid favorite field"
            val endpointId = favorite["endpointId"].orEmpty()
            val modelId = favorite["modelId"].orEmpty()
            if (endpointId.isBlank() || modelId.isBlank() || !identities.add(endpointId to modelId)) {
                return "invalid or duplicate favorite identity"
            }
            for (key in listOf("providerOrder", "ignoredProviders")) {
                favorite[key]?.let { value ->
                    if (runCatching { strings(JSONArray(value)) }.isFailure) {
                        return "invalid favorite provider list"
                    }
                }
            }
        }
        return null
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).map { index ->
            array.getString(index).also { require(safeText(it)) }
        }.also { require(it.distinct().size == it.size) }

    private fun hasExactKeys(value: JSONObject, expected: Set<String>): Boolean =
        value.keys().asSequence().toSet() == expected

    private fun safeText(value: String, max: Int = 65_536): Boolean =
        value.length <= max && value.none { it == '\u0000' }

    private fun credentialBearingUrl(value: String): Boolean {
        val authority = value.substringAfter("://", "").substringBefore('/').substringBefore('?')
        if ('@' in authority) return true
        return Regex(
            "(?i)[?&](api[_-]?key|access[_-]?token|token|password|secret)="
        ).containsMatchIn(value)
    }

    private val ROOT_KEYS = setOf("format", "complete", "endpoints", "favorites")
    private val ENDPOINT_KEYS = setOf(
        "id", "label", "host", "chat_endpoint", "speech_endpoint", "auth_type", "model",
        "temperature", "top_p", "frequency_penalty", "presence_penalty", "max_tokens",
        "end_separator", "prefix", "provider", "connect_timeout_seconds",
        "response_timeout_seconds", "context_window_tokens", "context_window_model_id",
        "image_capability_by_model", "tool_capability_by_model",
        "reasoning_capability_by_model", "reasoning_rejected_levels_by_model",
        "provider_discovery_path", "identity", "rejected_tts_voices"
    )
    private val FAVORITE_KEYS = setOf(
        "modelId", "endpointId", "routingType", "selectedProvider", "allowFallbacks",
        "providerOrder", "ignoredProviders", "reasoningEffort", "showReasoning", "streaming",
        "temperature", "topP", "frequencyPenalty", "presencePenalty"
    )
}
