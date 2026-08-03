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

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.imagegen.ImageProviderAdapters
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.ui.activities.ChooseProviderActivity

/**
 * Shared actions for the favorite-model list wherever it is shown — the
 * Favorite AI Models dialog and the All Models picker's favorites section — so
 * both behave identically (owner ruling: the routing gear and the remove
 * confirmation apply anywhere favorites appear in the menus).
 */
object FavoriteRoutingActions {

    /**
     * Confirm before removing a favorite, since removal also clears its
     * provider-routing preferences. Uses the shared themed dialog and the
     * standard two-action layout with "Cancel" first (dismiss) and "Okay"
     * second (proceed) per the owner's ordering; [onConfirm] runs only on Okay.
     */
    fun confirmRemove(context: Context, onConfirm: () -> Unit) {
        val actionsView = LayoutInflater.from(context).inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.favorite_remove_title)
            .setMessage(R.string.favorite_remove_message)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        dialog.show()
    }

    /**
     * Build the Choose Provider intent for a favorite's routing gear, loaded
     * with that model's stored routing, or null when the endpoint is not an
     * OpenRouter endpoint (no gear is shown there). Connection details come
     * from the favorite's own endpoint profile so the provider chart can fetch;
     * the screen writes the favorite directly on Save (EXTRA_PERSIST_DIRECTLY).
     */
    fun buildRoutingIntent(
        context: Context,
        apiEndpointPreferences: ApiEndpointPreferences,
        favoriteModelsPreferences: FavoriteModelsPreferences,
        modelId: String,
        endpointId: String,
        /** When non-null, preselect this routing type on the screen instead of
         *  the model's stored one (used by the Quick Settings Provider Mode
         *  dropdown, which opens the screen on the mode the user just picked). */
        routingTypeOverride: String? = null
    ): Intent? {
        val endpoint = apiEndpointPreferences.getApiEndpoint(context, endpointId)
        if (!ImageProviderAdapters.isOpenRouter(endpoint)) return null

        val routingType = routingTypeOverride ?: favoriteModelsPreferences.getRoutingType(modelId, endpointId)

        return Intent(context, ChooseProviderActivity::class.java)
            .putExtra(ChooseProviderActivity.EXTRA_PERSIST_DIRECTLY, true)
            .putExtra(ChooseProviderActivity.EXTRA_ENDPOINT_ID, endpointId)
            .putExtra(ChooseProviderActivity.EXTRA_MODEL, modelId)
            .putExtra(ChooseProviderActivity.EXTRA_ROUTING_TYPE, routingType)
            .putExtra(ChooseProviderActivity.EXTRA_HOST, endpoint.host)
            .putExtra(ChooseProviderActivity.EXTRA_API_KEY, endpoint.apiKey)
            .putExtra(ChooseProviderActivity.EXTRA_AUTH_TYPE, endpoint.authType)
            .putExtra(
                ChooseProviderActivity.EXTRA_DISCOVERY_PATH,
                endpoint.providerDiscoveryPath.ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
            )
    }
}
