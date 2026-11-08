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
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.providers.RecentOnlyProvidersStore

/**
 * A background job's selected model and its provider routing (the Summarizer
 * or the Memory Scanner), kept apart from the chat's and from each other.
 *
 * It follows the chat's rules with the job's own lists: a favorite keeps its
 * routing on the favorite; a model that is not one of the job's favorites, or
 * a provider tried that is not one of the favorite's favorite providers, is
 * kept as the job's own routing for its selected model and on that model's
 * Recent entry. Nothing here ever makes a model a favorite, and nothing here
 * reads or changes a chat favorite.
 */
class DedicatedModelRouting private constructor(val scope: ModelPickerScope) {

    companion object {
        val SUMMARIZER = DedicatedModelRouting(ModelPickerScope.SUMMARIZER)
        val MEMORY_SCANNER = DedicatedModelRouting(ModelPickerScope.MEMORY_SCANNER)

        fun of(scope: ModelPickerScope): DedicatedModelRouting = when (scope) {
            ModelPickerScope.SUMMARIZER -> SUMMARIZER
            ModelPickerScope.MEMORY_SCANNER -> MEMORY_SCANNER
            ModelPickerScope.CHAT -> throw IllegalArgumentException("Chat routing belongs to ChatModelRouting")
        }

        private val gson = Gson()
    }

    private fun prefs(context: Context) = Preferences.getPreferences(context, "")

    private fun favorites(context: Context) = FavoriteModelsPreferences.getPreferences(context, scope)

    private fun recents(context: Context) = RecentModelsPreferences(context, scope)

    /* ---------------------------- The selected model ---------------------------- */

    fun endpointId(context: Context): String = prefs(context).let {
        if (scope == ModelPickerScope.SUMMARIZER) it.getSummarizerEndpointId() else it.getArchivistEndpointId()
    }

    fun modelId(context: Context): String = prefs(context).let {
        if (scope == ModelPickerScope.SUMMARIZER) it.getSummarizerModel() else it.getArchivistModel()
    }

    /** Makes [modelId] on [endpointId] the job's model. Routing is set
     *  separately (see [clear], [use]). */
    fun select(context: Context, endpointId: String, modelId: String) {
        val prefs = prefs(context)
        if (scope == ModelPickerScope.SUMMARIZER) {
            prefs.setSummarizerEndpointId(endpointId)
            prefs.setSummarizerModel(modelId)
        } else {
            prefs.setArchivistEndpointId(endpointId)
            prefs.setArchivistModel(modelId)
        }
    }

    private fun storedOverride(prefs: Preferences): String =
        if (scope == ModelPickerScope.SUMMARIZER) prefs.getSummarizerRoutingOverride()
        else prefs.getMemoryScannerRoutingOverride()

    private fun migrated(prefs: Preferences): Boolean =
        if (scope == ModelPickerScope.SUMMARIZER) prefs.getSummarizerRoutingMigrated()
        else prefs.getMemoryScannerRoutingMigrated()

    private fun markMigrated(prefs: Preferences) {
        if (scope == ModelPickerScope.SUMMARIZER) prefs.setSummarizerRoutingMigrated()
        else prefs.setMemoryScannerRoutingMigrated()
    }

    private fun storeOverride(prefs: Preferences, json: String) {
        if (scope == ModelPickerScope.SUMMARIZER) prefs.setSummarizerRoutingOverride(json)
        else prefs.setMemoryScannerRoutingOverride(json)
    }

    private fun legacyRoutingType(prefs: Preferences): String =
        if (scope == ModelPickerScope.SUMMARIZER) prefs.getSummarizerRoutingType()
        else prefs.getArchivistRoutingType()

    /* ---------------------------- Routing ---------------------------- */

    /** The job's own routing for this exact model, or null. */
    fun overrideFor(context: Context, endpointId: String, modelId: String): FavoriteModelObject? {
        migrate(context)
        val json = storedOverride(prefs(context))
        if (json.isBlank()) return null
        return decode(json)?.takeIf { it.endpointId == endpointId && it.modelId == modelId }
    }

    /** The routing the job uses for this model: its own routing when it has
     *  one, otherwise its favorite's. Null when neither exists (the provider
     *  chooses automatically). */
    fun effective(context: Context, endpointId: String, modelId: String): FavoriteModelObject? {
        if (endpointId.isBlank() || modelId.isBlank()) return null
        val favorite = favorites(context).getFavorite(modelId, endpointId)
        val own = overrideFor(context, endpointId, modelId) ?: return favorite
        val result = favorite?.copy() ?: FavoriteModelObject(modelId, endpointId)
        own.copyRoutingTo(result)
        return result
    }

    /** The routing to show when editing this model's providers: what the job
     *  uses, else what the model last used from its Recent entry. */
    fun forEditing(context: Context, endpointId: String, modelId: String): FavoriteModelObject? {
        effective(context, endpointId, modelId)?.let { return it }
        if (endpointId.isBlank() || modelId.isBlank()) return null
        val recent = recents(context).entry(ModelIdentity(endpointId, modelId)) ?: return null
        return FavoriteModelObject(modelId, endpointId).also { recent.copyRoutingTo(it) }
    }

    /** The providers the user marked Favorite for this model: on the job's
     *  favorite when it is one, otherwise on its Recent entry. */
    fun favoriteProviders(context: Context, endpointId: String, modelId: String): List<String> {
        val favorite = favorites(context).getFavorite(modelId, endpointId)
        return favorite?.favoriteProviders
            ?: recents(context).favoriteProviders(ModelIdentity(endpointId, modelId))
    }

    fun setFavoriteProviders(context: Context, endpointId: String, modelId: String, providers: List<String>): Boolean {
        val favorites = favorites(context)
        return if (favorites.isFavorite(modelId, endpointId)) {
            favorites.setFavoriteProviders(modelId, endpointId, providers)
        } else {
            recents(context).setFavoriteProviders(ModelIdentity(endpointId, modelId), providers)
        }
    }

    /** The job goes back to the favorite's routing (or Automatic). */
    fun clear(context: Context) {
        migrate(context)
        storeOverride(prefs(context), "")
    }

    /** The job uses [routing] without changing any favorite. */
    fun use(context: Context, routing: FavoriteModelObject) {
        migrate(context)
        storeOverride(prefs(context), encode(routing))
    }

    /** Saves a routing choice: on the job's favorite unless it is a trial;
     *  otherwise as the job's own routing and on the Recent entry. */
    fun save(context: Context, routing: FavoriteModelObject) {
        val favorites = favorites(context)
        val favorite = favorites.getFavorite(routing.modelId, routing.endpointId)
        if (favorite != null && !ChatModelRouting.isTrial(favorite, routing)) {
            routing.copyRoutingTo(favorite)
            favorites.addFavoriteModel(favorite, carryRecentRouting = false)
            if (overrideFor(context, routing.endpointId, routing.modelId) != null) clear(context)
        } else {
            use(context, routing)
            if (routing.routingType == FavoriteModelObject.ROUTING_ONLY) {
                RecentOnlyProvidersStore.get(context, scope)
                    .record(routing.endpointId, routing.modelId, routing.selectedProvider)
            }
        }
        recordRecent(context, routing)
    }

    /** Records the routing a model was used with on the job's Recent entry. */
    fun recordRecent(context: Context, routing: FavoriteModelObject) {
        val entry = FavoriteModelObject(routing.modelId, routing.endpointId)
        routing.copyRoutingTo(entry)
        runCatching { recents(context).recordRouting(entry) }
    }

    /** Records a successful request with the routing it used. */
    fun recordUse(context: Context, endpointId: String, modelId: String) {
        if (endpointId.isBlank() || modelId.isBlank()) return
        val routing = effective(context, endpointId, modelId) ?: FavoriteModelObject(modelId, endpointId)
        recordRecent(context, routing)
    }

    /**
     * One-time carry-over from the older setting, which kept only a routing
     * type and borrowed provider details from the chat's favorite. The
     * selected model keeps exactly the routing it was using, now as the job's
     * own, and becomes its first Recent entry. The marker that it ran is an app
     * setting, so an older backup restored later (which has none) is carried
     * over too.
     */
    fun migrate(context: Context) {
        val prefs = prefs(context)
        if (migrated(prefs)) return
        synchronized(this) {
            if (migrated(prefs)) return
            markMigrated(prefs)
            val endpointId = endpointId(context)
            val modelId = modelId(context)
            if (endpointId.isBlank() || modelId.isBlank()) return
            val chatFavorite = runCatching {
                FavoriteModelsPreferences.getPreferences(context).getFavorite(modelId, endpointId)
            }.getOrNull()
            val routing = DedicatedModelRoutingPolicy.favoriteForRequest(
                modelId, endpointId, legacyRoutingType(prefs), chatFavorite
            )
            // Plain Automatic needs no routing of its own.
            storeOverride(prefs, routing?.let(::encode).orEmpty())
            recordRecent(context, routing ?: FavoriteModelObject(modelId, endpointId))
        }
    }

    private fun encode(routing: FavoriteModelObject): String {
        val stored = FavoriteModelObject(routing.modelId, routing.endpointId)
        routing.copyRoutingTo(stored)
        return gson.toJson(stored)
    }

    @Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
    private fun decode(json: String): FavoriteModelObject? = try {
        gson.fromJson(json, FavoriteModelObject::class.java)?.takeIf {
            (it.modelId as String?) != null && (it.endpointId as String?) != null
        }?.also {
            if ((it.routingType as String?) == null) it.routingType = FavoriteModelObject.ROUTING_AUTOMATIC
            if ((it.selectedProvider as String?) == null) it.selectedProvider = ""
            if ((it.providerOrder as List<String>?) == null) it.providerOrder = emptyList()
            if ((it.ignoredProviders as List<String>?) == null) it.ignoredProviders = emptyList()
            if ((it.favoriteProviders as List<String>?) == null) it.favoriteProviders = emptyList()
            if ((it.reasoningEffort as String?) == null) it.reasoningEffort = FavoriteModelObject.REASONING_AUTO
        }
    } catch (_: Exception) {
        null
    }
}
