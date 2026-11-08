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

package org.teslasoft.assistant.ui.dedicated

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.DedicatedModelRouting
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.ui.activities.ApiEndpointEditorActivity
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.FavoriteRoutingActions
import org.teslasoft.assistant.ui.widgets.AppDropdown
import java.util.Locale

/**
 * A background job's model rows on its settings screen (the API profile row
 * with its gear, the model row, View All Models and the routing row), working
 * on the job's own lists and routing (DedicatedModelRouting). Like Quick
 * Settings, the rows show what the job uses now; choosing a model gives the job
 * that model's parameters ([onModelChanged] lets the screen show them).
 *
 * The activity registers the two result launchers and passes their results to
 * [onEndpointEditorResult] and [onRoutingSetupResult].
 */
class DedicatedModelRows(
    private val activity: FragmentActivity,
    private val scope: ModelPickerScope,
    private val endpointValue: TextView,
    private val endpointGear: ImageButton,
    private val modelValue: TextView,
    private val viewAll: MaterialButton,
    private val routingSection: View,
    private val routingValue: TextView,
    private val endpointEditorLauncher: ActivityResultLauncher<Intent>,
    private val routingSetupLauncher: ActivityResultLauncher<Intent>,
    private val onModelChanged: () -> Unit
) {
    private val routing = DedicatedModelRouting.of(scope)
    private val endpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(activity)
    private val favorites = FavoriteModelsPreferences.getPreferences(activity, scope)
    private val disabledAlpha = ResourcesCompat.getFloat(activity.resources, R.dimen.disabled_control_alpha)

    init {
        endpointValue.setOnClickListener { showEndpointDropdown() }
        endpointGear.setOnClickListener { openEndpointEditor() }
        modelValue.setOnClickListener { showModelDropdown() }
        viewAll.setOnClickListener { openPicker() }
        routingValue.setOnClickListener { showRoutingDropdown() }
        activity.supportFragmentManager.setFragmentResultListener(
            AdvancedModelSelectorDialogFragment.DEDICATED_SELECTION_RESULT, activity
        ) { _, _ ->
            refresh()
            onModelChanged()
        }
        refresh()
    }

    private fun endpointProfiles(): List<ApiEndpointObject> =
        endpointPreferences.getApiEndpointsList(activity)
            .filter { it.id.isNotBlank() && it.label.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }

    fun refresh() {
        val endpointId = routing.endpointId(activity)
        val endpoint = endpointProfiles().firstOrNull { it.id == endpointId }
        endpointValue.text = endpoint?.label ?: activity.getString(R.string.dropdown_select)
        endpointGear.isEnabled = endpoint != null
        endpointGear.alpha = if (endpoint != null) 1f else disabledAlpha

        val model = routing.modelId(activity)
        modelValue.text = model.ifEmpty { activity.getString(R.string.dropdown_select) }
        viewAll.isEnabled = endpoint != null
        viewAll.alpha = if (endpoint != null) 1f else disabledAlpha

        val supportsRouting = endpoint?.isOpenRouterRouting() == true
        routingSection.visibility = if (supportsRouting) View.VISIBLE else View.GONE
        val routingEnabled = supportsRouting && model.isNotBlank()
        routingValue.isEnabled = routingEnabled
        routingValue.alpha = if (routingEnabled) 1f else disabledAlpha
        val type = if (routingEnabled) currentRoutingType(endpointId, model) else FavoriteModelObject.ROUTING_AUTOMATIC
        routingValue.text = routingLabel(type)
    }

    private fun currentRoutingType(endpointId: String, model: String): String =
        DedicatedModelRoutingPolicy.normalize(
            routing.effective(activity, endpointId, model)?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC
        )

    /** A deleted profile clears its now-invalid model and own routing. */
    fun onEndpointEditorResult(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra("deleted", false) == true) {
            routing.select(activity, "", "")
            routing.clear(activity)
        }
        refresh()
    }

    fun onRoutingSetupResult(@Suppress("UNUSED_PARAMETER") result: ActivityResult) {
        refresh()
    }

    private fun showEndpointDropdown() {
        val endpoints = endpointProfiles()
        val currentId = routing.endpointId(activity)
        AppDropdown.show(endpointValue, endpoints.map { it.label }, endpoints.indexOfFirst { it.id == currentId }) { position ->
            val pickedId = endpoints[position].id
            if (pickedId != currentId) {
                // A model belongs to its profile; never carry it to another.
                routing.select(activity, pickedId, "")
                routing.clear(activity)
                onModelChanged()
            }
            refresh()
        }
    }

    private fun openEndpointEditor() {
        val endpointId = routing.endpointId(activity)
        val position = endpointProfiles().indexOfFirst { it.id == endpointId }
        if (position < 0) return
        endpointEditorLauncher.launch(
            Intent(activity, ApiEndpointEditorActivity::class.java)
                .putExtra("position", position)
                .putExtra("id", endpointId)
        )
    }

    /** Quick selection from the job's favorites on the selected profile. */
    private fun showModelDropdown() {
        val endpointId = routing.endpointId(activity)
        if (endpointId.isEmpty()) return
        val models = favorites.getFavoriteModels(endpointId)
            .mapNotNull { it["modelId"]?.takeIf { id -> id.isNotBlank() } }
            .distinct()
        val current = routing.modelId(activity)
        AppDropdown.show(modelValue, models, models.indexOf(current)) { position ->
            val model = models[position]
            // A favorite brings its own routing, as when picked in the picker.
            routing.clear(activity)
            routing.select(activity, endpointId, model)
            FavoriteModelParameters.applyToDedicated(activity, scope, model, endpointId)
            refresh()
            onModelChanged()
        }
    }

    /** View All Models opens Select Summarizer / Memory Scanner Model on its
     *  View All tab. */
    private fun openPicker() {
        val endpointId = routing.endpointId(activity)
        if (endpointId.isEmpty()) return
        AdvancedModelSelectorDialogFragment.newDedicatedModelPickerInstance(scope, endpointId, startOnViewAll = true)
            .show(activity.supportFragmentManager, "DedicatedModelPicker")
    }

    private fun routingLabel(type: String): String = when (type) {
        FavoriteModelObject.ROUTING_PREFERRED -> activity.getString(R.string.choose_provider_routing_preferred)
        FavoriteModelObject.ROUTING_ONLY -> activity.getString(R.string.choose_provider_routing_only)
        else -> activity.getString(R.string.choose_provider_routing_automatic)
    }

    private fun showRoutingDropdown() {
        val endpointId = routing.endpointId(activity)
        val model = routing.modelId(activity)
        if (endpointId.isBlank() || model.isBlank()) return
        val types = DedicatedModelRoutingPolicy.routingTypes
        val current = types.indexOf(currentRoutingType(endpointId, model)).coerceAtLeast(0)
        AppDropdown.show(routingValue, types.map(::routingLabel), current) { position ->
            val picked = types[position]
            val choice = (routing.forEditing(activity, endpointId, model)?.copy()
                ?: FavoriteModelObject(model, endpointId)).apply { routingType = picked }
            if (DedicatedModelRoutingPolicy.needsSetup(picked, choice)) {
                showRoutingSetupDialog(endpointId, model, picked)
            } else {
                routing.save(activity, choice)
                refresh()
            }
        }
    }

    /** Preferred/Only without provider details opens Choose Provider; nothing
     *  changes unless it saves. */
    private fun showRoutingSetupDialog(endpointId: String, model: String, mode: String) {
        MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.provider_mode_setup_title)
            .setMessage(R.string.provider_mode_setup_message)
            .setPositiveButton(R.string.provider_mode_setup_confirm) { _, _ ->
                FavoriteRoutingActions.buildRoutingIntent(
                    activity, endpointPreferences, favorites, model, endpointId, mode, scope = scope
                )?.let { routingSetupLauncher.launch(it) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

}
