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
import org.json.JSONArray
import org.teslasoft.assistant.preferences.backup.portable.ModelEndpointPortableCodec
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.Hash

/**
 * The authoritative, credential-free model endpoint state.
 *
 * A generation record is committed and decoded successfully before the one
 * active pointer is changed. SharedPreferences publishes each commit
 * atomically, so a process interruption exposes either the complete previous
 * generation or the complete replacement generation. API credentials never
 * enter this store; they remain in the encrypted id-keyed secret store.
 */
internal class ModelEndpointStateGenerationStore private constructor(
    private val statePreferences: SharedPreferences,
    private val legacyFavoritePreferences: SharedPreferences
) {
    fun read(): ModelEndpointPortableCodec.Data? = synchronized(LOCK) {
        readActiveLocked()
    }

    fun activeGenerationId(): String? = synchronized(LOCK) {
        readActiveLocked() ?: return@synchronized null
        statePreferences.getString(ACTIVE_GENERATION_KEY, null)
    }

    /** Writes and verifies an inactive generation without changing readers. */
    fun stage(data: ModelEndpointPortableCodec.Data): String? = synchronized(LOCK) {
        stageLocked(data)
    }

    /** Atomically publishes an already complete, verified generation. */
    fun activate(generationId: String): Boolean = synchronized(LOCK) {
        activateLocked(generationId)
    }

    fun install(data: ModelEndpointPortableCodec.Data): Boolean = synchronized(LOCK) {
        val generationId = stageLocked(data) ?: return@synchronized false
        activateLocked(generationId)
    }

    fun update(
        transform: (ModelEndpointPortableCodec.Data) -> ModelEndpointPortableCodec.Data
    ): Boolean = synchronized(LOCK) {
        val current = readActiveLocked() ?: return@synchronized false
        val desired = try {
            transform(current)
        } catch (_: Exception) {
            return@synchronized false
        }
        val generationId = stageLocked(desired) ?: return@synchronized false
        activateLocked(generationId)
    }

    fun readGeneration(generationId: String): ModelEndpointPortableCodec.Data? = synchronized(LOCK) {
        readGenerationLocked(generationId)
    }

    private fun readActiveLocked(): ModelEndpointPortableCodec.Data? {
        val pointer = statePreferences.getString(ACTIVE_GENERATION_KEY, null)
        if (pointer != null) return readGenerationLocked(pointer)

        // One-time migration. The legacy definition fields and favorite file
        // remain untouched; after the pointer is published they are no longer
        // authoritative, and encrypted credentials were never read or moved.
        val migrated = readLegacyState()
        val generationId = stageLocked(migrated) ?: return null
        if (!activateLocked(generationId)) return null
        return readGenerationLocked(generationId)
    }

    private fun stageLocked(data: ModelEndpointPortableCodec.Data): String? {
        val canonical = canonical(data)
        val encoded = try {
            ModelEndpointPortableCodec.encode(canonical)
        } catch (_: Exception) {
            return null
        }
        val decoded = decode(encoded) ?: return null
        if (decoded != canonical) return null

        val generationId = Hash.hash(encoded.toByteArray(Charsets.UTF_8))
        val key = generationKey(generationId)
        val existing = statePreferences.getString(key, null)
        if (existing != null) {
            return generationId.takeIf { existing == encoded && decode(existing) == canonical }
        }

        // Do not trust commit() alone: read the exact bytes back and decode
        // them before this generation can ever become active.
        statePreferences.edit().putString(key, encoded).commit()
        return generationId.takeIf {
            statePreferences.getString(key, null) == encoded && readGenerationLocked(it) == canonical
        }
    }

    private fun activateLocked(generationId: String): Boolean {
        if (readGenerationLocked(generationId) == null) return false
        statePreferences.edit().putString(ACTIVE_GENERATION_KEY, generationId).commit()
        return statePreferences.getString(ACTIVE_GENERATION_KEY, null) == generationId &&
            readGenerationLocked(generationId) != null
    }

    private fun readGenerationLocked(generationId: String): ModelEndpointPortableCodec.Data? {
        if (!generationId.matches(GENERATION_ID)) return null
        val encoded = statePreferences.getString(generationKey(generationId), null) ?: return null
        if (Hash.hash(encoded.toByteArray(Charsets.UTF_8)) != generationId) return null
        return decode(encoded)
    }

    private fun decode(encoded: String): ModelEndpointPortableCodec.Data? =
        (ModelEndpointPortableCodec.parse(encoded) as? ModelEndpointPortableCodec.Result.Ok)?.data

    private fun readLegacyState(): ModelEndpointPortableCodec.Data {
        val endpoints = statePreferences.all.keys
            .asSequence()
            .filter { it.endsWith("_label") }
            .map { it.removeSuffix("_label") }
            .distinct()
            .mapNotNull(::readLegacyEndpoint)
            .sortedBy { it.id }
            .toList()
        val endpointIds = endpoints.mapTo(HashSet()) { it.id }
        val favorites = readLegacyFavorites()
            .filter { it["endpointId"].orEmpty() in endpointIds }
            .distinctBy(::favoriteIdentity)
            .sortedWith(compareBy({ it["endpointId"].orEmpty() }, { it["modelId"].orEmpty() }))
        return ModelEndpointPortableCodec.Data(endpoints, favorites)
    }

    private fun readLegacyEndpoint(id: String): ModelEndpointPortableCodec.Endpoint? = try {
        fun value(suffix: String, default: String = ""): String =
            statePreferences.getString(id + suffix, default) ?: default

        val host = value("_host")
        val model = value("_model", ApiEndpointObject.DEFAULT_MODEL)
        val contextModel = value("_context_window_model")
        val identity = value("_identity").ifBlank {
            if (ApiEndpointObject.isRecognizedOpenRouterUrl(host)) {
                ApiEndpointObject.IDENTITY_OPENROUTER
            } else {
                ApiEndpointObject.IDENTITY_GENERIC
            }
        }
        ModelEndpointPortableCodec.Endpoint(
            id = id,
            label = value("_label"),
            host = host,
            chatEndpoint = value("_chat_endpoint", ApiEndpointObject.DEFAULT_CHAT_ENDPOINT),
            speechEndpoint = value("_speech_endpoint", ApiEndpointObject.DEFAULT_SPEECH_ENDPOINT),
            authType = value("_auth_type", ApiEndpointObject.AUTH_BEARER),
            model = model,
            temperature = value("_temperature", ApiEndpointObject.DEFAULT_TEMPERATURE.toString())
                .toDoubleOrNull() ?: ApiEndpointObject.DEFAULT_TEMPERATURE.toDouble(),
            topP = value("_top_p", ApiEndpointObject.DEFAULT_TOP_P.toString())
                .toDoubleOrNull() ?: ApiEndpointObject.DEFAULT_TOP_P.toDouble(),
            frequencyPenalty = value(
                "_frequency_penalty", ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY.toString()
            ).toDoubleOrNull() ?: ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY.toDouble(),
            presencePenalty = value(
                "_presence_penalty", ApiEndpointObject.DEFAULT_PRESENCE_PENALTY.toString()
            ).toDoubleOrNull() ?: ApiEndpointObject.DEFAULT_PRESENCE_PENALTY.toDouble(),
            maxTokens = value("_max_tokens", ApiEndpointObject.DEFAULT_MAX_TOKENS.toString())
                .toIntOrNull() ?: ApiEndpointObject.DEFAULT_MAX_TOKENS,
            endSeparator = value("_end_separator"),
            prefix = value("_prefix"),
            provider = value("_provider"),
            connectTimeoutSeconds = ApiEndpointObject.coerceConnectTimeoutSeconds(
                value("_timeout", ApiEndpointObject.DEFAULT_CONNECT_TIMEOUT_SECONDS.toString())
                    .toIntOrNull() ?: ApiEndpointObject.DEFAULT_CONNECT_TIMEOUT_SECONDS
            ),
            responseTimeoutSeconds = ApiEndpointObject.coerceResponseTimeoutSeconds(
                value("_response_timeout", ApiEndpointObject.DEFAULT_RESPONSE_TIMEOUT_SECONDS.toString())
                    .toIntOrNull() ?: ApiEndpointObject.DEFAULT_RESPONSE_TIMEOUT_SECONDS
            ),
            contextWindowTokens = value("_context_window_tokens").toIntOrNull()
                ?.takeIf { it > 0 && contextModel == model },
            contextWindowModelId = contextModel.takeIf { it == model }.orEmpty(),
            imageCapabilityByModel = value("_image_capability_by_model"),
            toolCapabilityByModel = value("_tool_capability_by_model"),
            reasoningCapabilityByModel = value("_reasoning_capability_by_model"),
            reasoningRejectedLevelsByModel = value("_reasoning_rejected_levels_by_model"),
            providerDiscoveryPath = value("_provider_discovery_path"),
            identity = identity,
            rejectedTtsVoices = parseStringArray(value("_tts_rejected_voices", "[]"))
        ).takeIf { endpoint ->
            runCatching {
                ModelEndpointPortableCodec.encode(
                    ModelEndpointPortableCodec.Data(listOf(endpoint), emptyList())
                )
            }.isSuccess
        }
    } catch (_: Exception) {
        null
    }

    private fun readLegacyFavorites(): List<Map<String, String>> = try {
        val source = legacyFavoritePreferences.getString(LEGACY_FAVORITES_KEY, "[]") ?: "[]"
        val array = JSONArray(source)
        buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val favorite = LinkedHashMap<String, String>()
                item.keys().forEach { key ->
                    if (key in FAVORITE_KEYS && !item.isNull(key)) favorite[key] = item.getString(key)
                }
                if (favorite["endpointId"].orEmpty().isNotBlank() &&
                    favorite["modelId"].orEmpty().isNotBlank()
                ) add(favorite)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseStringArray(source: String): List<String> = try {
        val values = JSONArray(source)
        buildList {
            repeat(values.length()) { index ->
                values.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }

    private fun canonical(
        data: ModelEndpointPortableCodec.Data
    ): ModelEndpointPortableCodec.Data = data.copy(
        endpoints = data.endpoints
            .map { it.copy(rejectedTtsVoices = it.rejectedTtsVoices.distinct().sorted()) }
            .sortedBy { it.id },
        favorites = data.favorites
            .map { LinkedHashMap(it) }
            .sortedWith(compareBy({ it["endpointId"].orEmpty() }, { it["modelId"].orEmpty() }))
    )

    companion object {
        /** TTS and other long-running readers invalidate on this one switch. */
        const val ACTIVE_GENERATION_KEY = "model_endpoint_active_generation_v1"
        private const val GENERATION_PREFIX = "model_endpoint_generation_v1_"
        private const val LEGACY_FAVORITES_KEY = "favorite_models"
        private val GENERATION_ID = Regex("[0-9a-f]{64}")
        private val LOCK = Any()
        private val FAVORITE_KEYS = setOf(
            "modelId", "endpointId", "routingType", "selectedProvider", "allowFallbacks",
            "providerOrder", "ignoredProviders", "reasoningEffort", "showReasoning", "streaming",
            "temperature", "topP", "frequencyPenalty", "presencePenalty"
        )

        fun get(context: Context): ModelEndpointStateGenerationStore {
            val app = context.applicationContext
            return ModelEndpointStateGenerationStore(
                app.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE),
                app.getSharedPreferences("favorite_models", Context.MODE_PRIVATE)
            )
        }

        fun createForTest(
            statePreferences: SharedPreferences,
            legacyFavoritePreferences: SharedPreferences = statePreferences
        ): ModelEndpointStateGenerationStore =
            ModelEndpointStateGenerationStore(statePreferences, legacyFavoritePreferences)

        private fun generationKey(generationId: String): String = GENERATION_PREFIX + generationId

        private fun favoriteIdentity(favorite: Map<String, String>): String =
            favorite["endpointId"].orEmpty() + "\u0000" + favorite["modelId"].orEmpty()
    }
}
