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
 * The Summarizer and the Memory Scanner work the same way with their own
 * favorites and Recent lists. Their settings screens act as their Quick
 * Settings, and a model with nothing saved starts from the recommended
 * parameters (RecommendedModelParameters), never an API profile's.
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

    /** The defaults a model without saved parameters uses in [scope]. */
    fun defaults(context: Context, modelId: String, endpointId: String, scope: ModelPickerScope): FavoriteModelObject =
        if (scope == ModelPickerScope.CHAT) apiDefaults(context, modelId, endpointId)
        else RecommendedModelParameters.forScope(scope, modelId, endpointId)

    /** What the model starts with: its saved parameters, else the defaults. */
    fun startingParameters(
        context: Context, modelId: String, endpointId: String,
        scope: ModelPickerScope = ModelPickerScope.CHAT
    ): FavoriteModelObject =
        saved(context, modelId, endpointId, scope) ?: defaults(context, modelId, endpointId, scope)

    /** A background job's current parameters: what its requests send, as
     *  shown on its settings screen. */
    fun live(context: Context, scope: ModelPickerScope): FavoriteModelObject {
        val prefs = Preferences.getPreferences(context, "")
        return FavoriteModelObject("", "").apply {
            if (scope == ModelPickerScope.SUMMARIZER) {
                streaming = prefs.getSummarizerStreaming()
                temperature = prefs.getSummarizerTemperature()
                topP = prefs.getSummarizerTopP()
                frequencyPenalty = prefs.getSummarizerFrequencyPenalty()
                presencePenalty = prefs.getSummarizerPresencePenalty()
            } else {
                streaming = prefs.getMemoryScannerStreaming()
                temperature = prefs.getArchivistTemperature()
                topP = prefs.getMemoryScannerTopP()
                frequencyPenalty = prefs.getMemoryScannerFrequencyPenalty()
                presencePenalty = prefs.getMemoryScannerPresencePenalty()
            }
        }
    }

    /** Replaces a background job's current parameters with [values]' five. */
    fun setLive(context: Context, scope: ModelPickerScope, values: FavoriteModelObject) {
        val prefs = Preferences.getPreferences(context, "")
        val recommended = RecommendedModelParameters.forScope(scope)
        val streaming = values.streaming ?: recommended.streaming!!
        val temperature = values.temperature ?: recommended.temperature!!
        val topP = values.topP ?: recommended.topP!!
        val frequency = values.frequencyPenalty ?: recommended.frequencyPenalty!!
        val presence = values.presencePenalty ?: recommended.presencePenalty!!
        if (scope == ModelPickerScope.SUMMARIZER) {
            prefs.setSummarizerStreaming(streaming)
            prefs.setSummarizerTemperature(temperature)
            prefs.setSummarizerTopP(topP)
            prefs.setSummarizerFrequencyPenalty(frequency)
            prefs.setSummarizerPresencePenalty(presence)
        } else {
            prefs.setMemoryScannerStreaming(streaming)
            prefs.setArchivistTemperature(temperature)
            prefs.setMemoryScannerTopP(topP)
            prefs.setMemoryScannerFrequencyPenalty(frequency)
            prefs.setMemoryScannerPresencePenalty(presence)
        }
    }

    /**
     * Like a chat's Quick Settings: selecting a model for a background job
     * gives the job that model's saved parameters, or the recommended ones
     * when it has none. Does nothing while Enable Model Settings is off, so
     * the job keeps the parameters it has.
     */
    fun applyToDedicated(context: Context, scope: ModelPickerScope, modelId: String, endpointId: String) {
        if (modelId.isBlank() || endpointId.isBlank() || !isEnabled(context)) return
        setLive(context, scope, startingParameters(context, modelId, endpointId, scope))
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
