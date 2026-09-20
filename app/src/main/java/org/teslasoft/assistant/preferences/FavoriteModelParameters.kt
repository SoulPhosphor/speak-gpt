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

/**
 * Applies a favorite model's saved sampling parameters (streaming + the four
 * sliders) to a chat's live settings.
 *
 * The parameters belong to the model, so this runs whenever that model becomes
 * a chat's active model — regardless of the provider routing in effect. A
 * favorite with no saved parameters (never opened in Model Parameters, or saved
 * before the feature existed) reads back all-null and is left completely alone:
 * selecting such a model never overwrites whatever the chat already had. Only
 * fields the user actually saved are written, so this can never silently reset
 * a value the user did not choose.
 */
object FavoriteModelParameters {
    fun applyToChat(context: Context, preferences: Preferences, modelId: String, endpointId: String) {
        if (modelId.isBlank() || endpointId.isBlank()) return
        val favorite = FavoriteModelsPreferences.getPreferences(context)
            .getFavorite(modelId, endpointId) ?: return
        if (!favorite.hasSamplingParameters()) return
        favorite.streaming?.let { preferences.setStreaming(it) }
        favorite.temperature?.let { preferences.setTemperature(it) }
        favorite.topP?.let { preferences.setTopP(it) }
        favorite.frequencyPenalty?.let { preferences.setFrequencyPenalty(it) }
        favorite.presencePenalty?.let { preferences.setPresencePenalty(it) }
    }
}
