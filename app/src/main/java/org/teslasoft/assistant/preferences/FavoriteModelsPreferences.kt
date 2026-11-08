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
import com.google.gson.reflect.TypeToken

class FavoriteModelsPreferences private constructor(
    private val state: FavoriteListBackend,
    private val recentOnlyProviders: RecentOnlyProvidersStore? = null,
    /** A model's Recent entry, whose settings carry onto the model when it
     *  becomes a favorite. */
    private val recentEntry: ((ModelIdentity) -> FavoriteModelObject?)? = null
) {
    companion object {
        private val SAMPLING_KEYS = setOf("streaming", "temperature", "topP", "frequencyPenalty", "presencePenalty")
        private var instance: FavoriteModelsPreferences? = null
        private var summarizerInstance: FavoriteModelsPreferences? = null

        /** The chat's favorites (also used by every screen that is not the
         *  Summarizer). */
        fun getPreferences(context: Context): FavoriteModelsPreferences {
            if (instance == null) {
                val recents = RecentModelsPreferences(context)
                instance = FavoriteModelsPreferences(
                    GenerationFavoriteBackend(ModelEndpointStateGenerationStore.get(context)),
                    RecentOnlyProvidersStore.get(context),
                    recents::entry
                )
            }
            return instance!!
        }

        /** The favorites of [scope]'s model picker. */
        fun getPreferences(context: Context, scope: ModelPickerScope): FavoriteModelsPreferences =
            when (scope) {
                ModelPickerScope.CHAT -> getPreferences(context)
                ModelPickerScope.SUMMARIZER -> getSummarizerPreferences(context)
            }

        /** The Summarizer's own favorites, kept apart from the chat's. */
        @Synchronized
        fun getSummarizerPreferences(context: Context): FavoriteModelsPreferences {
            if (summarizerInstance == null) {
                val app = context.applicationContext
                val recents = RecentModelsPreferences(app, ModelPickerScope.SUMMARIZER)
                val state = ModelEndpointStateGenerationStore.get(app)
                summarizerInstance = FavoriteModelsPreferences(
                    SeparateFavoriteBackend(
                        app.getSharedPreferences(SUMMARIZER_FILE, Context.MODE_PRIVATE)
                    ) { state.read()?.endpoints?.mapTo(HashSet()) { it.id } },
                    RecentOnlyProvidersStore.get(app, ModelPickerScope.SUMMARIZER),
                    recents::entry
                )
            }
            return summarizerInstance!!
        }

        private const val SUMMARIZER_FILE = "summarizer_favorite_models"

        internal fun createForTest(
            statePreferences: SharedPreferences,
            legacyFavoritePreferences: SharedPreferences = statePreferences,
            recentEntry: ((ModelIdentity) -> FavoriteModelObject?)? = null
        ): FavoriteModelsPreferences = FavoriteModelsPreferences(
            GenerationFavoriteBackend(
                ModelEndpointStateGenerationStore.createForTest(
                    statePreferences,
                    legacyFavoritePreferences
                )
            ),
            recentEntry = recentEntry
        )

        /** A store kept in its own file, like the Summarizer's. */
        internal fun createSeparateForTest(
            preferences: SharedPreferences,
            endpointIds: () -> Set<String>?,
            recentEntry: ((ModelIdentity) -> FavoriteModelObject?)? = null
        ): FavoriteModelsPreferences = FavoriteModelsPreferences(
            SeparateFavoriteBackend(preferences, endpointIds),
            recentEntry = recentEntry
        )
    }

    fun setFavoriteModels(models: ArrayList<Map<String, String>>) {
        state.edit { endpointIds, _ ->
            models
                .filter { it["endpointId"].orEmpty() in endpointIds }
                .distinctBy { it["endpointId"].orEmpty() + "\u0000" + it["modelId"].orEmpty() }
                .map { LinkedHashMap(it) }
        }
    }

    fun getFavoriteModels(): ArrayList<Map<String, String>> {
        return ArrayList(state.favorites().map { LinkedHashMap(it) })
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
            state.edit { _, favorites ->
                favorites.map { entry ->
                    if (entry["modelId"] == model.modelId && entry["endpointId"] == model.endpointId) {
                        entry - "pinned"
                    } else entry
                }
            }
        }
    }

    /** [carryRecentRouting] false for an explicit routing save, whose routing
     *  (Automatic included) must be kept exactly as given. */
    fun addFavoriteModel(model: FavoriteModelObject, carryRecentRouting: Boolean = true) =
        saveFavoriteModel(model, carryRecentRouting = carryRecentRouting)

    /** Saving from a picker menu must leave an existing model's settings and pin intact. */
    fun addFavoriteIfAbsent(model: FavoriteModelObject) = saveFavoriteModel(model, preserveExisting = true)

    private fun saveFavoriteModel(
        model: FavoriteModelObject,
        pin: Boolean = false,
        preserveExisting: Boolean = false,
        carryRecentRouting: Boolean = true
    ) {
        var previous: Map<String, String>? = null
        val saved = state.edit { endpointIds, current ->
            if (model.endpointId !in endpointIds) return@edit current
            val models = current.toMutableList()
            val existingIndex = models.indexOfFirst { it["modelId"] == model.modelId && it["endpointId"] == model.endpointId }
            val existing = models.getOrNull(existingIndex)
            previous = existing
            if (existing != null && (pin || preserveExisting)) {
                if (!pin) return@edit current
                models[existingIndex] = existing + ("pinned" to "true")
                return@edit models
            }

            // A model that becomes a favorite brings its Recent entry's settings
            // with it (from any path, including View All), unless the caller
            // supplies its own.
            if (existing == null) {
                recentEntry?.invoke(ModelIdentity(model.endpointId, model.modelId))?.let { recent ->
                    if (!model.hasSamplingParameters()) recent.copySamplingTo(model)
                    if (model.favoriteProviders.isEmpty()) model.favoriteProviders = recent.favoriteProviders
                    if (carryRecentRouting && isDefaultRouting(model)) recent.copyRoutingTo(model)
                }
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
            models
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

    private fun isDefaultRouting(model: FavoriteModelObject): Boolean =
        model.routingType == FavoriteModelObject.ROUTING_AUTOMATIC && model.selectedProvider.isBlank() &&
            model.allowFallbacks && model.providerOrder.isEmpty() && model.ignoredProviders.isEmpty()

    /** Replaces the favorite providers of an existing favorite. Does nothing
     *  when the model is not a favorite: nothing is ever favorited implicitly. */
    fun setFavoriteProviders(modelId: String, endpointId: String, providers: List<String>): Boolean {
        val cleaned = providers.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        var found = false
        val saved = state.edit { _, current ->
            val updated = current.map { entry ->
                if (entry["modelId"] == modelId && entry["endpointId"] == endpointId) {
                    found = true
                    if (cleaned.isEmpty()) entry - "favoriteProviders"
                    else entry + ("favoriteProviders" to Gson().toJson(cleaned))
                } else entry
            }
            if (found) updated else current
        }
        return saved && found
    }

    /** Removes a favorite's saved sampling parameters, so the model goes back
     *  to its API profile's defaults. */
    fun clearSamplingParameters(modelId: String, endpointId: String): Boolean =
        state.edit { _, favorites ->
            favorites.map { entry ->
                if (entry["modelId"] == modelId && entry["endpointId"] == endpointId) {
                    entry - SAMPLING_KEYS
                } else entry
            }
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
        val saved = state.edit { _, current ->
            val kept = current.filterNot { item ->
                ModelIdentity(item["endpointId"].orEmpty(), item["modelId"].orEmpty()) in targets
            }
            removed = current.size - kept.size
            kept
        }
        if (!saved) return 0
        return removed
    }
}

/** Where a favorites list is kept. */
internal interface FavoriteListBackend {
    fun favorites(): List<Map<String, String>>

    /** Replaces the list with [transform]'s result, which receives the ids of
     *  the API profiles that exist and the current list. False when nothing
     *  could be saved. */
    fun edit(transform: (Set<String>, List<Map<String, String>>) -> List<Map<String, String>>): Boolean
}

/** The chat's favorites, kept inside the model endpoint settings generation. */
internal class GenerationFavoriteBackend(
    private val state: ModelEndpointStateGenerationStore
) : FavoriteListBackend {
    override fun favorites(): List<Map<String, String>> = state.read()?.favorites.orEmpty()

    override fun edit(
        transform: (Set<String>, List<Map<String, String>>) -> List<Map<String, String>>
    ): Boolean = state.update { current ->
        current.copy(favorites = transform(current.endpoints.mapTo(HashSet()) { it.id }, current.favorites))
    }
}

/**
 * A favorites list in its own file (the Summarizer's). Entries whose API
 * profile no longer exists are left out when read and dropped on the next
 * write, as the chat's are.
 */
internal class SeparateFavoriteBackend(
    private val preferences: SharedPreferences,
    private val endpointIds: () -> Set<String>?
) : FavoriteListBackend {
    private val gson = Gson()
    private val type = object : TypeToken<List<Map<String, String>>>() {}.type

    override fun favorites(): List<Map<String, String>> = synchronized(this) {
        val ids = endpointIds() ?: return emptyList()
        stored().filter { it["endpointId"].orEmpty() in ids }
    }

    override fun edit(
        transform: (Set<String>, List<Map<String, String>>) -> List<Map<String, String>>
    ): Boolean = synchronized(this) {
        val ids = endpointIds() ?: return false
        val updated = try {
            transform(ids, stored().filter { it["endpointId"].orEmpty() in ids })
        } catch (_: Exception) {
            return false
        }
        val canonical = updated
            .filter { it["endpointId"].orEmpty() in ids && it["modelId"].orEmpty().isNotBlank() }
            .distinctBy { it["endpointId"].orEmpty() + "\u0000" + it["modelId"].orEmpty() }
            .map { LinkedHashMap(it) }
            .sortedWith(compareBy({ it["endpointId"].orEmpty() }, { it["modelId"].orEmpty() }))
        preferences.edit().putString(KEY, gson.toJson(canonical)).commit()
    }

    private fun stored(): List<Map<String, String>> = try {
        gson.fromJson<List<Map<String, String>>>(preferences.getString(KEY, null), type).orEmpty()
            .filter { it["endpointId"] != null && it["modelId"] != null }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val KEY = "favorites"
    }
}
