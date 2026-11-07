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
import org.teslasoft.assistant.providers.RecentOnlyProvidersStore

/**
 * Which provider routing a chat uses, and where a routing choice is saved.
 *
 * A favorite model keeps its routing on the favorite. A chat keeps its own
 * routing (the "override") when it uses something the favorite does not hold:
 * a model that is not a favorite, or a provider tried on a favorite that is
 * not one of the model's favorite providers. Nothing here ever makes a model
 * a favorite from a chat. Every chat routing choice is also recorded on the
 * model's Recent entry, the picker's trial area.
 */
object ChatModelRouting {

    private val gson = Gson()

    /** The chat's own routing for this exact model, or null. */
    fun overrideFor(chat: Preferences, endpointId: String, modelId: String): FavoriteModelObject? {
        val json = chat.getChatRoutingOverride()
        if (json.isBlank()) return null
        val stored = decode(json) ?: return null
        return stored.takeIf { it.endpointId == endpointId && it.modelId == modelId }
    }

    /** The routing the chat actually uses for this model: its own routing
     *  when it has one, otherwise the favorite's. Null when neither exists
     *  (the provider chooses automatically). Other favorite values, such as
     *  reasoning, always come from the favorite. */
    fun effective(context: Context, chat: Preferences?, endpointId: String, modelId: String): FavoriteModelObject? {
        if (endpointId.isBlank() || modelId.isBlank()) return null
        val favorite = FavoriteModelsPreferences.getPreferences(context).getFavorite(modelId, endpointId)
        val own = chat?.let { overrideFor(it, endpointId, modelId) } ?: return favorite
        val result = favorite?.copy() ?: FavoriteModelObject(modelId, endpointId)
        own.copyRoutingTo(result)
        return result
    }

    /** The chat goes back to the favorite's routing (or Automatic). */
    fun clear(chat: Preferences) {
        chat.setChatRoutingOverride("")
    }

    /** The chat uses [routing] without changing any favorite. */
    fun use(chat: Preferences, routing: FavoriteModelObject) {
        chat.setChatRoutingOverride(encode(routing))
    }

    /** The raw stored override, for carrying into a new chat. */
    fun storedOverride(chat: Preferences): String = chat.getChatRoutingOverride()

    /** Carries a stored override (from [storedOverride]) into [chat]. */
    fun restoreOverride(chat: Preferences, json: String) {
        chat.setChatRoutingOverride(if (json.isNotBlank() && decode(json) != null) json else "")
    }

    /** True when [routing] tries a provider the favorite does not hold. */
    fun isTrial(favorite: FavoriteModelObject, routing: FavoriteModelObject): Boolean =
        routing.routingType == FavoriteModelObject.ROUTING_ONLY &&
            routing.selectedProvider.isNotBlank() &&
            !routing.selectedProvider.equals(favorite.selectedProvider, ignoreCase = true) &&
            !favorite.isFavoriteProvider(routing.selectedProvider)

    /** True when the two hold the same routing choices. */
    fun sameRouting(a: FavoriteModelObject, b: FavoriteModelObject): Boolean =
        a.routingType == b.routingType &&
            a.selectedProvider.equals(b.selectedProvider, ignoreCase = true) &&
            a.allowFallbacks == b.allowFallbacks &&
            a.providerOrder == b.providerOrder &&
            a.ignoredProviders == b.ignoredProviders

    /**
     * Saves a routing choice for [routing]'s model. In a chat, a favorite
     * takes it unless it is a trial; a trial, or any choice for a model that
     * is not a favorite, belongs to [chat] alone and its Recent entry.
     * Without a chat (Memory Assistant and Summarizer settings, which read
     * their routing from the favorite) the favorite stores it, as it always
     * has for those screens.
     */
    fun save(context: Context, chat: Preferences?, routing: FavoriteModelObject) {
        val favorites = FavoriteModelsPreferences.getPreferences(context)
        val favorite = favorites.getFavorite(routing.modelId, routing.endpointId)
        if (chat == null) {
            val target = favorite ?: FavoriteModelObject(routing.modelId, routing.endpointId)
            routing.copyRoutingTo(target)
            favorites.addFavoriteModel(target)
            return
        }
        if (favorite != null && !isTrial(favorite, routing)) {
            routing.copyRoutingTo(favorite)
            favorites.addFavoriteModel(favorite)
            if (overrideFor(chat, routing.endpointId, routing.modelId) != null) clear(chat)
        } else {
            use(chat, routing)
            if (routing.routingType == FavoriteModelObject.ROUTING_ONLY) {
                RecentOnlyProvidersStore.get(context)
                    .record(routing.endpointId, routing.modelId, routing.selectedProvider)
            }
        }
        recordRecent(context, routing)
    }

    /** Records the routing a model was used with on its Recent entry. */
    fun recordRecent(context: Context, routing: FavoriteModelObject) {
        val entry = FavoriteModelObject(routing.modelId, routing.endpointId)
        routing.copyRoutingTo(entry)
        runCatching { RecentModelsPreferences(context).recordRouting(entry) }
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
