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
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.RecentModelsPreferences
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.RecentOnlyProviders
import org.teslasoft.assistant.providers.RecentOnlyProvidersStore

/**
 * The model picker's Recent list inside the model settings backup: each
 * model with its provider routing, saved parameters and favorite providers,
 * the list's size limit, the last providers used per model, and the
 * providers' display names.
 */
object RecentModelsBackup {

    data class Snapshot(
        val limit: Int,
        val models: List<FavoriteModelObject>,
        val providers: List<Triple<String, String, List<String>>>,
        val names: Map<String, String>
    )

    private const val MAX_LIMIT = 99

    fun capture(context: Context, scope: ModelPickerScope = ModelPickerScope.CHAT): Snapshot {
        val recents = RecentModelsPreferences(context, scope)
        val providerStore = RecentOnlyProvidersStore.get(context, scope)
        return Snapshot(recents.limit(), recents.models(), providerStore.exportRecents(), providerStore.exportNames())
    }

    fun apply(context: Context, snapshot: Snapshot, scope: ModelPickerScope = ModelPickerScope.CHAT) {
        RecentModelsPreferences(context, scope).replaceAll(snapshot.limit, snapshot.models)
        RecentOnlyProvidersStore.get(context, scope).replaceAll(snapshot.providers, snapshot.names)
    }

    /** Only entries whose API profile is part of the same data. */
    fun forEndpoints(snapshot: Snapshot, endpointIds: Set<String>): Snapshot = snapshot.copy(
        models = snapshot.models.filter { it.endpointId in endpointIds },
        providers = snapshot.providers.filter { it.first in endpointIds }
    )

    /** A merge restore keeps this device's Recent list first, adds the
     *  backup's other models after it, and joins the provider histories. */
    fun merge(current: Snapshot?, incoming: Snapshot?): Snapshot? {
        if (current == null) return incoming
        if (incoming == null) return current
        val models = (current.models + incoming.models)
            .distinctBy { it.endpointId + "\u0000" + it.modelId }
            .take(current.limit)
        val byModel = LinkedHashMap<Pair<String, String>, List<String>>()
        (current.providers + incoming.providers).forEach { (endpointId, modelId, slugs) ->
            val id = endpointId to modelId
            byModel[id] = (byModel[id].orEmpty() + slugs).distinctBy { it.lowercase() }.take(RecentOnlyProviders.LIMIT)
        }
        return Snapshot(
            current.limit,
            models,
            byModel.map { (id, slugs) -> Triple(id.first, id.second, slugs) },
            incoming.names + current.names
        )
    }

    fun encode(snapshot: Snapshot): JSONObject = JSONObject()
        .put("limit", snapshot.limit)
        .put("models", JSONArray().apply { snapshot.models.forEach { put(encodeModel(it)) } })
        .put("providers", JSONArray().apply {
            snapshot.providers.forEach { (endpointId, modelId, slugs) ->
                put(JSONObject().put("endpointId", endpointId).put("modelId", modelId).put("slugs", JSONArray(slugs)))
            }
        })
        .put("names", JSONObject().apply { snapshot.names.forEach { (slug, name) -> put(slug, name) } })

    /** Null when the section is malformed. */
    fun parse(json: JSONObject): Snapshot? = try {
        require(json.keys().asSequence().toSet() == setOf("limit", "models", "providers", "names"))
        val limit = json.getInt("limit").also { require(it in 0..MAX_LIMIT) }
        val modelsJson = json.getJSONArray("models")
        val models = (0 until modelsJson.length()).map { decodeModel(modelsJson.getJSONObject(it)) }
        val providersJson = json.getJSONArray("providers")
        val providers = (0 until providersJson.length()).map { index ->
            val item = providersJson.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("endpointId", "modelId", "slugs"))
            val slugs = item.getJSONArray("slugs")
            Triple(
                item.getString("endpointId").also(::requireText),
                item.getString("modelId").also(::requireText),
                (0 until slugs.length()).map { slugs.getString(it).also(::requireText) }
            )
        }
        val namesJson = json.getJSONObject("names")
        val names = namesJson.keys().asSequence().associateWith { namesJson.getString(it).also(::requireText) }
        Snapshot(limit, models, providers, names)
    } catch (_: Exception) {
        null
    }

    private fun encodeModel(model: FavoriteModelObject): JSONObject = JSONObject()
        .put("modelId", model.modelId)
        .put("endpointId", model.endpointId)
        .put("routingType", model.routingType)
        .put("selectedProvider", model.selectedProvider)
        .put("allowFallbacks", model.allowFallbacks)
        .put("providerOrder", JSONArray(model.providerOrder))
        .put("ignoredProviders", JSONArray(model.ignoredProviders))
        .put("reasoningEffort", model.reasoningEffort)
        .put("showReasoning", model.showReasoning)
        .put("favoriteProviders", JSONArray(model.favoriteProviders))
        .apply {
            model.streaming?.let { put("streaming", it) }
            model.temperature?.let { put("temperature", it.toDouble()) }
            model.topP?.let { put("topP", it.toDouble()) }
            model.frequencyPenalty?.let { put("frequencyPenalty", it.toDouble()) }
            model.presencePenalty?.let { put("presencePenalty", it.toDouble()) }
        }

    private val MODEL_KEYS = setOf(
        "modelId", "endpointId", "routingType", "selectedProvider", "allowFallbacks", "providerOrder",
        "ignoredProviders", "reasoningEffort", "showReasoning", "favoriteProviders",
        "streaming", "temperature", "topP", "frequencyPenalty", "presencePenalty"
    )

    private fun decodeModel(item: JSONObject): FavoriteModelObject {
        require(item.keys().asSequence().all { it in MODEL_KEYS })
        fun list(key: String): List<String> {
            val array = item.getJSONArray(key)
            return (0 until array.length()).map { array.getString(it).also(::requireText) }
        }
        fun float(key: String): Float? = if (item.has(key)) item.getDouble(key).toFloat() else null
        return FavoriteModelObject(
            modelId = item.getString("modelId").also(::requireText).also { require(it.isNotBlank()) },
            endpointId = item.getString("endpointId").also(::requireText).also { require(it.isNotBlank()) },
            routingType = item.getString("routingType").also {
                require(it in setOf(FavoriteModelObject.ROUTING_AUTOMATIC, FavoriteModelObject.ROUTING_PREFERRED, FavoriteModelObject.ROUTING_ONLY))
            },
            selectedProvider = item.getString("selectedProvider").also(::requireText),
            allowFallbacks = item.getBoolean("allowFallbacks"),
            providerOrder = list("providerOrder"),
            ignoredProviders = list("ignoredProviders"),
            reasoningEffort = item.getString("reasoningEffort").also(::requireText),
            showReasoning = item.getBoolean("showReasoning"),
            streaming = if (item.has("streaming")) item.getBoolean("streaming") else null,
            temperature = float("temperature"),
            topP = float("topP"),
            frequencyPenalty = float("frequencyPenalty"),
            presencePenalty = float("presencePenalty"),
            favoriteProviders = list("favoriteProviders")
        )
    }

    private fun requireText(value: String) {
        require(value.length <= 65_536 && value.none { it == '\u0000' })
    }
}
