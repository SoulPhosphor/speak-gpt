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

import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity

/**
 * A model's AI parameters (streaming + the four sampling sliders).
 *
 * With Enable Model Settings on, a model starts with the parameters saved for
 * it: on its favorite when it is a favorite, otherwise on its Recent entry.
 * A model with none saved starts from its API profile's defaults, never from
 * whatever another model last used. With the feature off nothing is saved per
 * model and selecting a model leaves the chat's parameters as they are.
 * Changes made in Quick Settings stay in that chat unless the user saves them.
 */
object FavoriteModelParameters {

    fun isEnabled(context: Context): Boolean =
        Preferences.getPreferences(context, "").getModelSettingsEnabled()

    /** True when this model's parameters are saved on a favorite (otherwise
     *  they belong to its Recent entry). */
    fun savesToFavorite(context: Context, modelId: String, endpointId: String): Boolean =
        FavoriteModelsPreferences.getPreferences(context).isFavorite(modelId, endpointId)

    /** The parameters saved for this model, or null when none are. */
    fun saved(context: Context, modelId: String, endpointId: String): FavoriteModelObject? {
        val favorite = FavoriteModelsPreferences.getPreferences(context).getFavorite(modelId, endpointId)
        val saved = if (favorite != null) favorite
            else RecentModelsPreferences(context).savedParameters(ModelIdentity(endpointId, modelId))
        return saved?.takeIf { it.hasSamplingParameters() }
    }

    /** The API profile's default parameters for [endpointId]. Streaming has no
     *  per-profile value, so it comes from the app's new-chat default. */
    fun apiDefaults(context: Context, modelId: String, endpointId: String): FavoriteModelObject {
        val profile = ApiEndpointPreferences.getApiEndpointPreferences(context).getApiEndpoint(context, endpointId)
        return FavoriteModelObject(modelId, endpointId).apply {
            streaming = Preferences.getPreferences(context, "").getStreaming()
            temperature = profile.temperature
            topP = profile.topP
            frequencyPenalty = profile.frequencyPenalty
            presencePenalty = profile.presencePenalty
        }
    }

    /** What the model starts with: its saved parameters, else API defaults. */
    fun startingParameters(context: Context, modelId: String, endpointId: String): FavoriteModelObject =
        saved(context, modelId, endpointId) ?: apiDefaults(context, modelId, endpointId)

    /** Gives the chat the parameters this model starts with. Does nothing
     *  while Enable Model Settings is off. */
    fun applyToChat(context: Context, preferences: Preferences, modelId: String, endpointId: String) {
        if (modelId.isBlank() || endpointId.isBlank() || !isEnabled(context)) return
        val parameters = startingParameters(context, modelId, endpointId)
        val defaults = apiDefaults(context, modelId, endpointId)
        preferences.setStreaming(parameters.streaming ?: defaults.streaming ?: true)
        preferences.setTemperature(parameters.temperature ?: defaults.temperature!!)
        preferences.setTopP(parameters.topP ?: defaults.topP!!)
        preferences.setFrequencyPenalty(parameters.frequencyPenalty ?: defaults.frequencyPenalty!!)
        preferences.setPresencePenalty(parameters.presencePenalty ?: defaults.presencePenalty!!)
    }

    /** True when this model's parameters have somewhere to be saved: its
     *  favorite, or a Recent list that keeps at least one model. */
    fun canSave(context: Context, modelId: String, endpointId: String): Boolean =
        savesToFavorite(context, modelId, endpointId) || RecentModelsPreferences(context).limit() > 0

    /** Saves [values]' five parameters for this model: on its favorite when it
     *  is one, otherwise on its Recent entry. False when nothing was saved. */
    fun save(context: Context, modelId: String, endpointId: String, values: FavoriteModelObject): Boolean {
        val favorites = FavoriteModelsPreferences.getPreferences(context)
        val favorite = favorites.getFavorite(modelId, endpointId)
        if (favorite != null) {
            values.copySamplingTo(favorite)
            favorites.addFavoriteModel(favorite)
            return true
        }
        val recents = RecentModelsPreferences(context)
        val target = ModelIdentity(endpointId, modelId)
        recents.setSavedParameters(target, values)
        return recents.savedParameters(target) != null
    }

    /** Removes this model's saved parameters, so it returns to its API
     *  profile's defaults. */
    fun restoreDefaults(context: Context, modelId: String, endpointId: String) {
        val favorites = FavoriteModelsPreferences.getPreferences(context)
        if (favorites.isFavorite(modelId, endpointId)) {
            favorites.clearSamplingParameters(modelId, endpointId)
        } else {
            RecentModelsPreferences(context).setSavedParameters(ModelIdentity(endpointId, modelId), null)
        }
    }

    /** The chat's current five parameters. */
    fun fromChat(preferences: Preferences, modelId: String, endpointId: String): FavoriteModelObject =
        FavoriteModelObject(modelId, endpointId).apply {
            streaming = preferences.getStreaming()
            temperature = preferences.getTemperature()
            topP = preferences.getTopP()
            frequencyPenalty = preferences.getFrequencyPenalty()
            presencePenalty = preferences.getPresencePenalty()
        }
}
