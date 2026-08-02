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
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import androidx.core.content.edit

class FavoriteModelsPreferences private constructor(private val sharedPreferences: SharedPreferences) {
    companion object {
        private const val KEY_FAVORITE_MODELS = "favorite_models"

        private lateinit var sharedPreferences: SharedPreferences

        private var instance: FavoriteModelsPreferences? = null

        fun getPreferences(context: Context): FavoriteModelsPreferences {
            sharedPreferences = context.getSharedPreferences("favorite_models", Context.MODE_PRIVATE)

            if (instance == null) {
                instance = FavoriteModelsPreferences(sharedPreferences)
            }

            return instance!!
        }
    }

    fun setFavoriteModels(models: ArrayList<Map<String, String>>) {
        sharedPreferences.edit { putString(KEY_FAVORITE_MODELS, Gson().toJson(models)) }
    }

    fun getFavoriteModels(): ArrayList<Map<String, String>> {
        val models = sharedPreferences.getString(KEY_FAVORITE_MODELS, "[]")

        var list = try {
            Gson().fromJson(models, ArrayList<Map<String, String>>()::class.java)
        } catch (_: Exception) {
            arrayListOf()
        }

        if (list == null) list = arrayListOf()

        return list
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

    fun addFavoriteModel(model: FavoriteModelObject) {
        var models = getFavoriteModels()

        if (models == null) models = arrayListOf()

        // Upsert: a model already favorited keeps its single entry, but its
        // stored routing/provider memory is refreshed to the latest choice. A
        // new model is appended. The provider memory lives on the favorite so
        // removing the favorite removes it too (see removeFavoriteModel).
        val existingIndex = models.indexOfFirst { m -> m["modelId"] == model.modelId && m["endpointId"] == model.endpointId }
        val entry = hashMapOf(
            "modelId" to model.modelId,
            "endpointId" to model.endpointId,
            "routingType" to model.routingType,
            "selectedProvider" to model.selectedProvider,
            "allowFallbacks" to model.allowFallbacks.toString(),
            "providerOrder" to Gson().toJson(model.providerOrder),
            "ignoredProviders" to Gson().toJson(model.ignoredProviders)
        )
        if (existingIndex >= 0) {
            models[existingIndex] = entry
        } else {
            models.add(entry)
        }
        setFavoriteModels(models)
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
        return FavoriteModelObject(
            modelId = modelId,
            endpointId = endpointId,
            routingType = entry["routingType"]?.takeIf { it.isNotBlank() }
                ?: FavoriteModelObject.ROUTING_AUTOMATIC,
            selectedProvider = entry["selectedProvider"] ?: "",
            allowFallbacks = entry["allowFallbacks"] != "false",
            providerOrder = parseStringList(entry["providerOrder"]),
            ignoredProviders = parseStringList(entry["ignoredProviders"])
        )
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
        val models = getFavoriteModels()
        val kept = ArrayList(models.filterNot { it["modelId"] == modelId && it["endpointId"] == endpointId })
        if (kept.size != models.size) setFavoriteModels(kept)
    }
}
