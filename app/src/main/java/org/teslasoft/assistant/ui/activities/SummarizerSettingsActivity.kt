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

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.FavoriteRoutingActions
import org.teslasoft.assistant.ui.widgets.AppDropdown
import java.util.Locale

/**
 * Summarizer Settings (conversation-summary-plan.md decision 2): the Summary
 * Model endpoint/model pickers (Memory Assistant interaction shape), the
 * Complete Messages default, the new-chats toggle, and the
 * Customize Prompt Summaries row. Values save as they are changed. Prompts
 * are edited on the Summarizer Prompts screen (owner ruling, Oct 3 2026).
 */
class SummarizerSettingsActivity : SettingsPageActivity() {

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var textEndpointValue: TextView? = null
    private var btnEditEndpoint: ImageButton? = null
    private var textModelValue: TextView? = null
    private var btnViewAllModels: MaterialButton? = null
    private var sectionRouting: View? = null
    private var textRoutingValue: TextView? = null
    private var fieldCompleteMessages: TextInputEditText? = null
    private var switchNewChats: MaterialSwitch? = null

    private var suppressWatchers = false

    /** The endpoint gear edits only the selected profile. Deleting that
     * profile clears its now-invalid model and routing override as one unit. */
    private val endpointEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data?.getBooleanExtra("deleted", false) == true) {
            preferences?.setSummarizerEndpointId("")
            preferences?.setSummarizerModel("")
            preferences?.setSummarizerRoutingType(FavoriteModelObject.ROUTING_AUTOMATIC)
        }
        refreshModelRows()
    }

    /** Choose Provider persists provider details on the favorite. Only a
     * completed, valid save changes the Summarizer's independent mode. */
    private val routingSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
            val model = preferences?.getSummarizerModel().orEmpty()
            val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
            val mode = favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC
            if (!DedicatedModelRoutingPolicy.needsSetup(mode, favorite)) {
                preferences?.setSummarizerRoutingType(mode)
            }
        }
        refreshModelRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_summarizer_settings)

        preferences = Preferences.getPreferences(this, "")
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(this)

        bindViews()
        applyTheme()
        initLogic()

    }

    override fun onResume() {
        super.onResume()
        refreshModelRows()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        textEndpointValue = findViewById(R.id.text_summarizer_endpoint_value)
        btnEditEndpoint = findViewById(R.id.btn_edit_summarizer_endpoint)
        textModelValue = findViewById(R.id.text_summarizer_model_value)
        btnViewAllModels = findViewById(R.id.btn_view_all_summarizer_models)
        sectionRouting = findViewById(R.id.section_summarizer_routing)
        textRoutingValue = findViewById(R.id.text_summarizer_routing_value)
        fieldCompleteMessages = findViewById(R.id.field_complete_messages)
        switchNewChats = findViewById(R.id.switch_summarizer_new_chats)
    }

    private fun applyTheme() {
        window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
        if (Build.VERSION.SDK_INT <= 34) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
            @Suppress("DEPRECATION")
            window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
        }
        actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
        btnBack?.backgroundTintList =
            ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        refreshModelRows()
        textEndpointValue?.setOnClickListener { showEndpointDropdown() }
        btnEditEndpoint?.setOnClickListener { openSelectedEndpointEditor() }
        textModelValue?.setOnClickListener { showModelDropdown() }
        btnViewAllModels?.setOnClickListener { openAllModels() }
        textRoutingValue?.setOnClickListener { showRoutingDropdown() }
        findViewById<View>(R.id.row_summarizer_prompts)?.setOnClickListener {
            startActivity(Intent(this, SummarizerPromptsActivity::class.java))
        }

        suppressWatchers = true
        fieldCompleteMessages?.setText(preferences?.getSummarizerDefaultWindow()?.toString() ?: "20")
        suppressWatchers = false

        fieldCompleteMessages?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatchers) return
                val parsed = s?.toString()?.trim()?.toIntOrNull()
                if (parsed != null && parsed >= 1) preferences?.setSummarizerDefaultWindow(parsed)
            }
        })

        switchNewChats?.isChecked = preferences?.getSummarizerOnForNewChats() ?: false
        switchNewChats?.setOnCheckedChangeListener { _, checked ->
            preferences?.setSummarizerOnForNewChats(checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_summarizer_always_resummarize)?.apply {
            isChecked = preferences?.getSummarizerAlwaysResummarize() ?: true
            setOnCheckedChangeListener { _, checked ->
                preferences?.setSummarizerAlwaysResummarize(checked)
            }
        }
    }

    /* ------------------------------ Summary Model ------------------------------ */

    private fun endpointProfiles(): List<ApiEndpointObject> =
        (apiEndpointPreferences?.getApiEndpointsList(this) ?: arrayListOf())
            .filter { it.id.isNotBlank() && it.label.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }

    private fun endpointFavoriteModels(endpointId: String): List<String> =
        favoriteModelsPreferences?.getFavoriteModels(endpointId)
            ?.mapNotNull { it["modelId"]?.takeIf { modelId -> modelId.isNotBlank() } }
            ?.distinct()
            ?: emptyList()

    private fun refreshModelRows() {
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        val endpoint = endpointProfiles().firstOrNull { it.id == endpointId }
        textEndpointValue?.text = endpoint?.label ?: getString(R.string.dropdown_select)
        btnEditEndpoint?.isEnabled = endpoint != null
        btnEditEndpoint?.alpha = if (endpoint != null) 1f else 0.38f

        val model = preferences?.getSummarizerModel().orEmpty()
        textModelValue?.text = model.ifEmpty { getString(R.string.dropdown_select) }
        btnViewAllModels?.isEnabled = endpoint != null
        btnViewAllModels?.alpha = if (endpoint != null) 1f else 0.38f

        val supportsRouting = endpoint?.isOpenRouterRouting() == true
        sectionRouting?.visibility = if (supportsRouting) View.VISIBLE else View.GONE
        val routingEnabled = supportsRouting && model.isNotBlank()
        textRoutingValue?.isEnabled = routingEnabled
        textRoutingValue?.alpha = if (routingEnabled) 1f else 0.38f
        val selectedRouting = if (routingEnabled) {
            DedicatedModelRoutingPolicy.normalize(preferences?.getSummarizerRoutingType().orEmpty())
        } else {
            FavoriteModelObject.ROUTING_AUTOMATIC
        }
        if (!routingEnabled &&
            preferences?.getSummarizerRoutingType() != FavoriteModelObject.ROUTING_AUTOMATIC
        ) {
            preferences?.setSummarizerRoutingType(FavoriteModelObject.ROUTING_AUTOMATIC)
        }
        textRoutingValue?.text = routingLabel(selectedRouting)
    }

    private fun showEndpointDropdown() {
        val dropdown = textEndpointValue ?: return
        val endpoints = endpointProfiles()
        val labels = endpoints.map { it.label }
        val currentId = preferences?.getSummarizerEndpointId().orEmpty()
        AppDropdown.show(dropdown, labels, endpoints.indexOfFirst { it.id == currentId }) { position ->
            val pickedId = endpoints[position].id
            if (pickedId != currentId) {
                preferences?.setSummarizerEndpointId(pickedId)
                preferences?.setSummarizerModel("")
                preferences?.setSummarizerRoutingType(FavoriteModelObject.ROUTING_AUTOMATIC)
            }
            refreshModelRows()
        }
    }

    private fun openSelectedEndpointEditor() {
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        val endpoints = endpointProfiles()
        val position = endpoints.indexOfFirst { it.id == endpointId }
        if (position < 0) return
        endpointEditorLauncher.launch(
            Intent(this, ApiEndpointEditorActivity::class.java)
                .putExtra("position", position)
                .putExtra("id", endpointId)
        )
    }

    /** Quick model selection is limited to favorites on the selected endpoint. */
    private fun showModelDropdown() {
        val dropdown = textModelValue ?: return
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        if (endpointId.isEmpty()) return
        val models = endpointFavoriteModels(endpointId)
        val current = preferences?.getSummarizerModel().orEmpty()
        AppDropdown.show(dropdown, models, models.indexOf(current)) { position ->
            selectModel(models[position])
        }
    }

    /** View All bypasses the favorites-first landing and opens the endpoint's
     * complete live catalog directly, matching Choose Provider and Memory
     * Assistant. */
    private fun openAllModels() {
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        if (endpointId.isEmpty()) return
        val current = preferences?.getSummarizerModel().orEmpty()
        val dialog = AdvancedModelSelectorDialogFragment.newAllModelsInstance(current, "", endpointId)
        dialog.setModelSelectedListener { model -> selectModel(model) }
        dialog.show(supportFragmentManager, "SummarizerModelSelector")
    }

    private fun selectModel(model: String) {
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        val endpoint = endpointProfiles().firstOrNull { it.id == endpointId }
        val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
        val routing = DedicatedModelRoutingPolicy.modeForSelectedModel(
            endpoint?.isOpenRouterRouting() == true,
            favorite
        )
        preferences?.setSummarizerModel(model)
        preferences?.setSummarizerRoutingType(routing)
        refreshModelRows()
    }

    private fun routingLabel(type: String): String = when (type) {
        FavoriteModelObject.ROUTING_PREFERRED -> getString(R.string.choose_provider_routing_preferred)
        FavoriteModelObject.ROUTING_ONLY -> getString(R.string.choose_provider_routing_only)
        else -> getString(R.string.choose_provider_routing_automatic)
    }

    private fun showRoutingDropdown() {
        val dropdown = textRoutingValue ?: return
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        val model = preferences?.getSummarizerModel().orEmpty()
        if (endpointId.isBlank() || model.isBlank()) return
        val labels = DedicatedModelRoutingPolicy.routingTypes.map { routingLabel(it) }
        val current = DedicatedModelRoutingPolicy.routingTypes
            .indexOf(preferences?.getSummarizerRoutingType().orEmpty())
            .coerceAtLeast(0)
        AppDropdown.show(dropdown, labels, current) { position ->
            val picked = DedicatedModelRoutingPolicy.routingTypes[position]
            val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
            if (DedicatedModelRoutingPolicy.needsSetup(picked, favorite)) {
                showRoutingSetupDialog(picked)
            } else {
                preferences?.setSummarizerRoutingType(picked)
                refreshModelRows()
            }
        }
    }

    private fun showRoutingSetupDialog(mode: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.provider_mode_setup_title)
            .setMessage(R.string.provider_mode_setup_message)
            .setPositiveButton(R.string.provider_mode_setup_confirm) { _, _ ->
                val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
                val model = preferences?.getSummarizerModel().orEmpty()
                val endpointPrefs = apiEndpointPreferences ?: return@setPositiveButton
                val favoritePrefs = favoriteModelsPreferences ?: return@setPositiveButton
                FavoriteRoutingActions.buildRoutingIntent(
                    this, endpointPrefs, favoritePrefs, model, endpointId, mode
                )?.let { routingSetupLauncher.launch(it) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            val scroll = findViewById<ScrollView>(R.id.scroll)
            val density = resources.displayMetrics.density
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom +
                    (24 * density).toInt()
            )
        } catch (_: Exception) { /* unused */ }
    }
}
