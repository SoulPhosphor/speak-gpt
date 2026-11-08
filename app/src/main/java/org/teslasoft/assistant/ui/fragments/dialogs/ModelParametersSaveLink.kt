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

package org.teslasoft.assistant.ui.fragments.dialogs

import android.view.View
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.util.SaveIconFlash

/**
 * Quick Settings' Save Parameters link above Streaming. Shown only while
 * Enable Model Settings is on. It saves the chat's current parameters for the
 * chat's model: on its favorite when it is one ("…to Favorite Model
 * Settings"), otherwise on its Recent entry ("…to Recent Model Settings").
 */
class ModelParametersSaveLink(
    private val button: MaterialButton,
    private val chat: () -> Preferences?
) {
    init {
        button.setOnClickListener { save() }
    }

    fun refresh() {
        val context = button.context
        val prefs = chat()
        val model = prefs?.getModel().orEmpty()
        val endpointId = prefs?.getApiEndpointId().orEmpty()
        // Hidden when there is nowhere to save: not a favorite, and the
        // Recent list is set to keep no models.
        val show = FavoriteModelParameters.isEnabled(context) && model.isNotBlank() && endpointId.isNotBlank() &&
            FavoriteModelParameters.canSave(context, model, endpointId)
        button.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        button.setText(
            if (FavoriteModelParameters.savesToFavorite(context, model, endpointId)) R.string.quick_save_parameters_favorite
            else R.string.quick_save_parameters_recent
        )
    }

    private fun save() {
        val context = button.context
        val prefs = chat() ?: return
        val model = prefs.getModel()
        val endpointId = prefs.getApiEndpointId()
        if (model.isBlank() || endpointId.isBlank() || !FavoriteModelParameters.isEnabled(context)) return
        val saved = FavoriteModelParameters.save(
            context, model, endpointId, FavoriteModelParameters.fromChat(prefs, model, endpointId)
        )
        refresh()
        if (!saved) return
        SaveIconFlash.flashText(button)
        Toast.makeText(context, context.getString(R.string.quick_save_parameters_saved, model), Toast.LENGTH_SHORT).show()
    }
}
