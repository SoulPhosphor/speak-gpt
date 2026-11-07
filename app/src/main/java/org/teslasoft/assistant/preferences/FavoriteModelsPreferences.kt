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
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.providers.RecentOnlyProvidersStore
import com.google.gson.Gson

class FavoriteModelsPreferences private constructor(
    private val state: ModelEndpointStateGenerationStore,
    private val recentOnlyProviders: RecentOnlyProvidersStore? = null,
    /** The settings saved on a model's Recent entry, carried onto the model
     *  when it becomes a favorite. */
    private val recentSavedParameters: ((ModelIdentity) -> FavoriteModelObject?)? = null
) {
    companion object {
        private val SAMPLING_KEYS = setOf("streaming", "temperature", "topP", "frequencyPenalty", "presencePenalty")
        private var instance: FavoriteModelsPreferences? = null

        fun getPreferences(context: Context): FavoriteModelsPreferences {
            if (instance == null) {
                val recents = RecentModelsPreferences(context)
                instance = FavoriteModelsPreferences(
                    ModelEndpointStateGenerationStore.get(context),
                    RecentOnlyProvidersStore.get(context),
                    recents::savedParameters
                )
            }
            return instance!!
        }

        internal fun createForTest(
            statePreferences: SharedPreferences,
            legacyFavoritePreferences: SharedPreferences = statePreferences,
            recentSavedParameters: ((ModelIdentity) -> FavoriteModelObject?)? = null
        ): FavoriteModelsPreferences = FavoriteModelsPreferences(
            ModelEndpointStateGenerationStore.createForTest(
                statePreferences,
                legacyFavoritePreferences
            ),
            recentSavedParameters = recentSavedParameters
        )
    }

    fun setFavoriteModels(models: ArrayList<Map<String, String>>) {
        state.update { current ->
            val endpointIds = current.endpoints.mapTo(HashSet()) { it.id }
            current.copy(
                favorites = models
                    .filter { it["endpointId"].orEmpty() in endpointIds }
                    .distinctBy { it["endpointId"].orEmpty() + "\u0000" + it["modelId"].orEmpty() }
                    .map { LinkedHashMap(it) }
            )
        }
    }

    fun getFavoriteModels(): ArrayList<Map<String, String>> {
        return ArrayList(state.read()?.favorites.orEmpty().map { LinkedHashMap(it) })
    }

    /**
     * Favorites are per-endpoint: a favorite starred while a given API endpoint
     * profile was active belongs to that profile only and must never appear
     * when a different profile is active. Returns only the favorites whose
     * stored endpointId matches [endpointId]. The no-argument overload returns
     * the whole store and exists only for the mutators below, which must see
     * every profile's entries so removing one never drops the others.
     */
    fun getFavoriteModels(endpointId: String): ArrayList<Map<String, String>> {
        return ArrayList(getFavoriteModels().filter { it["endpointId"] == endpointId })
    }

    fun setPinned(model: FavoriteModelObject, pinned: Boolean) {
        if (pinned) {
            saveFavoriteModel(model, pin = true)
        } else {
            state.update { current ->
                current.copy(favorites = current.favorites.map { entry ->
                    if (entry["modelId"] == model.modelId && entry["endpointId"] == model.endpointId) {
                        entry - "pinned"
                    } else entry
                })
            }
        }
    }

    fun addFavoriteModel(model: FavoriteModelObject) = saveFavoriteModel(model)

    /** Saving from a picker menu must leave an existing model's settings and pin intact. */
    fun addFavoriteIfAbsent(model: FavoriteModelObject) = saveFavoriteModel(model, preserveExisting = true)

    private fun saveFavoriteModel(
        model: FavoriteModelObject,
        pin: Boolean = false,
        preserveExisting: Boolean = false
    ) {
        var previous: Map<String, String>? = null
        val saved = state.update { current ->
            if (current.endpoints.none { it.id == model.endpointId }) return@update current
            val models = current.favorites.toMutableList()
            val existingIndex = models.indexOfFirst { it["modelId"] == model.modelId && it["endpointId"] == model.endpointId }
            val existing = models.getOrNull(existingIndex)
            previous = existing
            if (existing != null && (pin || preserveExisting)) {
                if (!pin) return@update current
                models[existingIndex] = existing + ("pinned" to "true")
                return@update current.copy(favorites = models)
            }

            // A model that becomes a favorite brings the settings saved on its
            // Recent entry with it, unless the caller supplies its own.
            if (existing == null && !model.hasSamplingParameters()) {
                recentSavedParameters?.invoke(ModelIdentity(model.endpointId, model.modelId))
                    ?.copySamplingTo(model)
            }
            val entry = hashMapOf(
                "modelId" to model.modelId,
                "endpointId" to model.endpointId,
                "routingType" to model.routingType,
                "selectedProvider" to model.selectedProvider,
                "allowFallbacks" to model.allowFallbacks.toString(),
                "providerOrder" to Gson().toJson(model.providerOrder),
                "ignoredProviders" to Gson().toJson(model.ignoredProviders),
                // Reasoning defaults ride on the favorite, independent of routing
                // (§7.4). Missing keys on an older entry read back as the §7.9
                // defaults (Auto / Show Reasoning On) in getFavorite below.
                "reasoningEffort" to model.reasoningEffort,
                "showReasoning" to model.showReasoning.toString()
            )
            // Sampling parameters ride on the favorite (Model Parameters screen).
            // A write that does not carry them (e.g. saving routing from the gear,
            // or reasoning from its screen) preserves whatever was saved before, so
            // those unrelated saves never erase a model's parameters. Only the
            // Model Parameters screen supplies non-null values, which overwrite.
            // A brand-new favorite has neither, so the keys stay absent and read
            // back all-null (see getFavorite).
            (model.streaming?.toString() ?: existing?.get("streaming"))?.let { entry["streaming"] = it }
            (model.temperature?.toString() ?: existing?.get("temperature"))?.let { entry["temperature"] = it }
            (model.topP?.toString() ?: existing?.get("topP"))?.let { entry["topP"] = it }
            (model.frequencyPenalty?.toString() ?: existing?.get("frequencyPenalty"))?.let { entry["frequencyPenalty"] = it }
            (model.presencePenalty?.toString() ?: existing?.get("presencePenalty"))?.let { entry["presencePenalty"] = it }
            // Favorite providers change only through setFavoriteProviders, so a
            // routing or settings save never drops them. A brand-new favorite
            // takes whatever the caller carries (normally none).
            val providers = existing?.get("favoriteProviders")
                ?: model.favoriteProviders.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it) }
            providers?.let { entry["favoriteProviders"] = it }
            if (pin || existing?.get("pinned") == "true") entry["pinned"] = "true"
            if (existingIndex >= 0) {
                models[existingIndex] = entry
            } else {
                models.add(entry)
            }
            current.copy(favorites = models)
        }
        if (!saved || ((pin || preserveExisting) && previous != null)) return
        val existing = previous
        // Every Only-mode save, from any screen, records its provider for the
        // Quick Settings Provider row.
        // The provider being replaced was the one in use, so it is recorded
        // first; this also captures a provider chosen before this list existed.
        if (model.routingType == FavoriteModelObject.ROUTING_ONLY) {
            if (existing?.get("routingType") == FavoriteModelObject.ROUTING_ONLY) {
                recentOnlyProviders?.record(model.endpointId, model.modelId, existing?.get("selectedProvider").orEmpty())
            }
            recentOnlyProviders?.record(model.endpointId, model.modelId, model.selectedProvider)
        }
    }

    /**
     * The stored provider-routing type for a favorited model, or
     * [FavoriteModelObject.ROUTING_AUTOMATIC] when the model is not a favorite
     * (or is an older favorite saved before routing memory existed). Automatic
     * is the safe default everywhere: it means "let the provider choose".
     */
    fun getRoutingType(modelId: String, endpointId: String): String {
        val match = getFavoriteModels().firstOrNull { it["modelId"] == modelId && it["endpointId"] == endpointId }
        return match?.get("routingType")?.takeIf { it.isNotBlank() }
            ?: FavoriteModelObject.ROUTING_AUTOMATIC
    }

    /** True when [modelId] is a favorite under [endpointId]. */
    fun isFavorite(modelId: String, endpointId: String): Boolean {
        return getFavoriteModels().any { it["modelId"] == modelId && it["endpointId"] == endpointId }
    }

    /**
     * The full stored favorite for [modelId] under [endpointId], or null when
     * the model is not a favorite. Reconstructs the provider memory (routing
     * type, Only-mode provider, preferred order, fallbacks flag, ignore list)
     * with safe defaults for entries saved before those fields existed.
     */
    fun getFavorite(modelId: String, endpointId: String): FavoriteModelObject? {
        val entry = getFavoriteModels().firstOrNull {
            it["modelId"] == modelId && it["endpointId"] == endpointId
        } ?: return null
        return decodeFavorite(entry)
    }

    internal fun decodeFavorite(entry: Map<String, String>): FavoriteModelObject =
        FavoriteModelObject(
            modelId = entry.getValue("modelId"),
            endpointId = entry.getValue("endpointId"),
            routingType = entry["routingType"]?.takeIf { it.isNotBlank() }
                ?: FavoriteModelObject.ROUTING_AUTOMATIC,
            selectedProvider = entry["selectedProvider"] ?: "",
            allowFallbacks = entry["allowFallbacks"] != "false",
            providerOrder = parseStringList(entry["providerOrder"]),
            ignoredProviders = parseStringList(entry["ignoredProviders"]),
            // Older favorites have no reasoning keys: default to Auto and Show
            // Reasoning On (§7.9). A blank stored effort also falls to Auto.
            reasoningEffort = entry["reasoningEffort"]?.takeIf { it.isNotBlank() }
                ?: FavoriteModelObject.REASONING_AUTO,
            showReasoning = entry["showReasoning"] != "false",
            // Sampling parameters are null when the key is absent (favorite
            // saved before this feature, or the user never saved parameters
            // for it) — that null is what tells a model selection to leave the
            // chat's own values alone.
            streaming = entry["streaming"]?.toBooleanStrictOrNull(),
            temperature = entry["temperature"]?.toFloatOrNull(),
            topP = entry["topP"]?.toFloatOrNull(),
            frequencyPenalty = entry["frequencyPenalty"]?.toFloatOrNull(),
            presencePenalty = entry["presencePenalty"]?.toFloatOrNull(),
            favoriteProviders = parseStringList(entry["favoriteProviders"])
        )

    /** Replaces the favorite providers of an existing favorite. Does nothing
     *  when the model is not a favorite: nothing is ever favorited implicitly. */
    fun setFavoriteProviders(modelId: String, endpointId: String, providers: List<String>): Boolean {
        val cleaned = providers.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        var found = false
        val saved = state.update { current ->
            val updated = current.favorites.map { entry ->
                if (entry["modelId"] == modelId && entry["endpointId"] == endpointId) {
                    found = true
                    if (cleaned.isEmpty()) entry - "favoriteProviders"
                    else entry + ("favoriteProviders" to Gson().toJson(cleaned))
                } else entry
            }
            if (found) current.copy(favorites = updated) else current
        }
        return saved && found
    }

    /** Removes a favorite's saved sampling parameters, so the model goes back
     *  to its API profile's defaults. */
    fun clearSamplingParameters(modelId: String, endpointId: String): Boolean =
        state.update { current ->
            current.copy(favorites = current.favorites.map { entry ->
                if (entry["modelId"] == modelId && entry["endpointId"] == endpointId) {
                    entry - SAMPLING_KEYS
                } else entry
            })
        }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            Gson().fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Removes a single favorite from the whole store, matched by both modelId
     * and endpointId. Written against the FULL list (not a per-endpoint view)
     * so unfavoriting one model can never wipe another profile's favorites.
     */
    fun removeFavoriteModel(modelId: String, endpointId: String) {
        removeFavoriteModels(setOf(ModelIdentity(endpointId, modelId)))
    }

    /** Remove a cleanup selection in one write while preserving every other favorite. */
    fun removeFavoriteModels(targets: Set<ModelIdentity>): Int {
        if (targets.isEmpty()) return 0
        var removed = 0
        val saved = state.update { current ->
            val kept = current.favorites.filterNot { item ->
                ModelIdentity(item["endpointId"].orEmpty(), item["modelId"].orEmpty()) in targets
            }
            removed = current.favorites.size - kept.size
            current.copy(favorites = kept)
        }
        if (!saved) return 0
        return removed
    }
}
