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
 *
 * The Summarizer works the same way with its own favorites and Recent list,
 * except that its defaults are the ones set on Summarizer Settings, never an
 * API profile's.
 */
object FavoriteModelParameters {

    fun isEnabled(context: Context): Boolean =
        Preferences.getPreferences(context, "").getModelSettingsEnabled()

    /** True when this model's parameters are saved on a favorite (otherwise
     *  they belong to its Recent entry). */
    fun savesToFavorite(
        context: Context, modelId: String, endpointId: String,
        scope: ModelPickerScope = ModelPickerScope.CHAT
    ): Boolean = FavoriteModelsPreferences.getPreferences(context, scope).isFavorite(modelId, endpointId)

    /** The parameters saved for this model, or null when none are. */
    fun saved(
        context: Context, modelId: String, endpointId: String,
        scope: ModelPickerScope = ModelPickerScope.CHAT
    ): FavoriteModelObject? {
        val favorite = FavoriteModelsPreferences.getPreferences(context, scope).getFavorite(modelId, endpointId)
        val saved = if (favorite != null) favorite
            else RecentModelsPreferences(context, scope).savedParameters(ModelIdentity(endpointId, modelId))
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

    /** The Summarizer's default parameters, set on Summarizer Settings. They
     *  never come from an API profile. */
    fun summarizerDefaults(context: Context, modelId: String, endpointId: String): FavoriteModelObject {
        val prefs = Preferences.getPreferences(context, "")
        return FavoriteModelObject(modelId, endpointId).apply {
            streaming = prefs.getSummarizerStreaming()
            temperature = prefs.getSummarizerTemperature()
            topP = prefs.getSummarizerTopP()
            frequencyPenalty = prefs.getSummarizerFrequencyPenalty()
            presencePenalty = prefs.getSummarizerPresencePenalty()
        }
    }

    /** The defaults a model without saved parameters uses in [scope]. */
    fun defaults(context: Context, modelId: String, endpointId: String, scope: ModelPickerScope): FavoriteModelObject =
        when (scope) {
            ModelPickerScope.CHAT -> apiDefaults(context, modelId, endpointId)
            ModelPickerScope.SUMMARIZER -> summarizerDefaults(context, modelId, endpointId)
        }

    /** What the model starts with: its saved parameters, else the defaults. */
    fun startingParameters(
        context: Context, modelId: String, endpointId: String,
        scope: ModelPickerScope = ModelPickerScope.CHAT
    ): FavoriteModelObject =
        saved(context, modelId, endpointId, scope) ?: defaults(context, modelId, endpointId, scope)

    /**
     * The five parameters a Summarizer request uses: the model's own saved
     * settings while Enable Model Settings is on, otherwise (or for any value
     * it has none of) the Summarizer's defaults. Every field is filled.
     */
    fun forSummarizerRequest(context: Context, modelId: String, endpointId: String): FavoriteModelObject {
        val defaults = summarizerDefaults(context, modelId, endpointId)
        val own = if (isEnabled(context)) saved(context, modelId, endpointId, ModelPickerScope.SUMMARIZER) else null
        return FavoriteModelObject(modelId, endpointId).apply {
            streaming = own?.streaming ?: defaults.streaming
            temperature = own?.temperature ?: defaults.temperature
            topP = own?.topP ?: defaults.topP
            frequencyPenalty = own?.frequencyPenalty ?: defaults.frequencyPenalty
            presencePenalty = own?.presencePenalty ?: defaults.presencePenalty
        }
    }

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
    fun canSave(
        context: Context, modelId: String, endpointId: String,
        scope: ModelPickerScope = ModelPickerScope.CHAT
    ): Boolean =
        savesToFavorite(context, modelId, endpointId, scope) || RecentModelsPreferences(context, scope).limit() > 0

    /** Saves [values]' five parameters for this model: on its favorite when it
     *  is one, otherwise on its Recent entry. False when nothing was saved. */
    fun save(
        context: Context, modelId: String, endpointId: String, values: FavoriteModelObject,
        scope: ModelPickerScope = ModelPickerScope.CHAT
    ): Boolean {
        val favorites = FavoriteModelsPreferences.getPreferences(context, scope)
        val favorite = favorites.getFavorite(modelId, endpointId)
        if (favorite != null) {
            values.copySamplingTo(favorite)
            favorites.addFavoriteModel(favorite)
            return true
        }
        val recents = RecentModelsPreferences(context, scope)
        val target = ModelIdentity(endpointId, modelId)
        recents.setSavedParameters(target, values)
        return recents.savedParameters(target) != null
    }

    /** Removes this model's saved parameters, so it returns to its defaults. */
    fun restoreDefaults(
        context: Context, modelId: String, endpointId: String,
        scope: ModelPickerScope = ModelPickerScope.CHAT
    ) {
        val favorites = FavoriteModelsPreferences.getPreferences(context, scope)
        if (favorites.isFavorite(modelId, endpointId)) {
            favorites.clearSamplingParameters(modelId, endpointId)
        } else {
            RecentModelsPreferences(context, scope).setSavedParameters(ModelIdentity(endpointId, modelId), null)
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
