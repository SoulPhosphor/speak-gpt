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
 * The Summarizer's provider routing, kept apart from the chat's.
 *
 * It follows the chat's rules with the Summarizer's own lists: a Summarizer
 * favorite keeps its routing on the favorite; a model that is not a Summarizer
 * favorite, or a provider tried that is not one of the favorite's favorite
 * providers, is kept as the Summarizer's own routing for its selected model
 * and on that model's Summarizer Recent entry. Nothing here ever makes a
 * model a favorite, and nothing here reads or changes a chat favorite.
 */
object SummarizerModelRouting {

    private val gson = Gson()

    private fun favorites(context: Context) =
        FavoriteModelsPreferences.getPreferences(context, ModelPickerScope.SUMMARIZER)

    private fun recents(context: Context) = RecentModelsPreferences(context, ModelPickerScope.SUMMARIZER)

    private fun prefs(context: Context) = Preferences.getPreferences(context, "")

    /** The Summarizer's own routing for this exact model, or null. */
    fun overrideFor(context: Context, endpointId: String, modelId: String): FavoriteModelObject? {
        migrate(context)
        val json = prefs(context).getSummarizerRoutingOverride()
        if (json.isBlank()) return null
        return decode(json)?.takeIf { it.endpointId == endpointId && it.modelId == modelId }
    }

    /** The routing the Summarizer uses for this model: its own routing when
     *  it has one, otherwise the Summarizer favorite's. Null when neither
     *  exists (the provider chooses automatically). */
    fun effective(context: Context, endpointId: String, modelId: String): FavoriteModelObject? {
        if (endpointId.isBlank() || modelId.isBlank()) return null
        val favorite = favorites(context).getFavorite(modelId, endpointId)
        val own = overrideFor(context, endpointId, modelId) ?: return favorite
        val result = favorite?.copy() ?: FavoriteModelObject(modelId, endpointId)
        own.copyRoutingTo(result)
        return result
    }

    /** The routing to show when editing this model's providers: what the
     *  Summarizer uses, else what the model last used from its Recent entry. */
    fun forEditing(context: Context, endpointId: String, modelId: String): FavoriteModelObject? {
        effective(context, endpointId, modelId)?.let { return it }
        if (endpointId.isBlank() || modelId.isBlank()) return null
        val recent = recents(context).entry(ModelIdentity(endpointId, modelId)) ?: return null
        return FavoriteModelObject(modelId, endpointId).also { recent.copyRoutingTo(it) }
    }

    /** The providers the user marked Favorite for this model: on its
     *  Summarizer favorite when it is one, otherwise on its Recent entry. */
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

    /** The Summarizer goes back to the favorite's routing (or Automatic). */
    fun clear(context: Context) {
        migrate(context)
        prefs(context).setSummarizerRoutingOverride("")
    }

    /** The Summarizer uses [routing] without changing any favorite. */
    fun use(context: Context, routing: FavoriteModelObject) {
        migrate(context)
        prefs(context).setSummarizerRoutingOverride(encode(routing))
    }

    /** Saves a routing choice: on the Summarizer favorite unless it is a
     *  trial; otherwise as the Summarizer's own routing and on the Recent
     *  entry. */
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
                RecentOnlyProvidersStore.get(context, ModelPickerScope.SUMMARIZER)
                    .record(routing.endpointId, routing.modelId, routing.selectedProvider)
            }
        }
        recordRecent(context, routing)
    }

    /** Records the routing a model was used with on its Summarizer Recent entry. */
    fun recordRecent(context: Context, routing: FavoriteModelObject) {
        val entry = FavoriteModelObject(routing.modelId, routing.endpointId)
        routing.copyRoutingTo(entry)
        runCatching { recents(context).recordRouting(entry) }
    }

    /** Records a successful Summarizer request with the routing it used. */
    fun recordUse(context: Context, endpointId: String, modelId: String) {
        if (endpointId.isBlank() || modelId.isBlank()) return
        val routing = effective(context, endpointId, modelId) ?: FavoriteModelObject(modelId, endpointId)
        recordRecent(context, routing)
    }

    /**
     * One-time carry-over from the older Summarizer setting, which kept only
     * a routing type and borrowed provider details from the chat's favorite.
     * The selected model keeps exactly the routing it was using, now as the
     * Summarizer's own, and becomes its first Recent entry. Runs whenever the
     * own-routing setting has never been written, so an older backup restored
     * later is carried over too.
     */
    fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.hasSummarizerRoutingOverride()) return
        synchronized(this) {
            if (prefs.hasSummarizerRoutingOverride()) return
            val endpointId = prefs.getSummarizerEndpointId()
            val modelId = prefs.getSummarizerModel()
            if (endpointId.isBlank() || modelId.isBlank()) {
                prefs.setSummarizerRoutingOverride("")
                return
            }
            val chatFavorite = runCatching {
                FavoriteModelsPreferences.getPreferences(context).getFavorite(modelId, endpointId)
            }.getOrNull()
            val routing = DedicatedModelRoutingPolicy.favoriteForRequest(
                modelId, endpointId, prefs.getSummarizerRoutingType(), chatFavorite
            )
            // Plain Automatic needs no routing of its own.
            prefs.setSummarizerRoutingOverride(routing?.let(::encode).orEmpty())
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
