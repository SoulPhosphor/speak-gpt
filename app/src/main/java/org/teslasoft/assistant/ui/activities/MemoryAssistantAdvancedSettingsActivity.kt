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
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistPrompt
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistRequestBudget
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.FavoriteRoutingActions
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.widgets.AppDropdown
import java.util.Locale

/**
 * "Advanced Memory Assistant Settings" — suggestion cap, endpoint/model
 * selection, and AI extraction tuning. Reached from the Memory Manager hub.
 *
 * Temperature, Minimum Importance, and the two analysis-type prompts (the
 * Associative Memory Prompt and the Lorebook Memory Prompt) are persisted only
 * on Save (spec §2). Maximum Suggestions, endpoint, and model save immediately
 * on change (consistent with their prior behavior in Memory Controls).
 *
 * User-facing name is "Memory Assistant"; the `Preferences.getArchivist*`
 * accessors keep the internal code name.
 */
class MemoryAssistantAdvancedSettingsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchMaxSuggestions: MaterialSwitch? = null
    private var fieldMaxSuggestions: TextInputEditText? = null
    private var textConversationAmountValue: TextView? = null
    private var sectionCustomConversationTokens: View? = null
    private var fieldCustomConversationTokens: TextInputEditText? = null

    private var textArchivistEndpointValue: TextView? = null
    private var btnEditArchivistEndpoint: ImageButton? = null
    private var textArchivistModelValue: TextView? = null
    private var btnViewAllArchivistModels: MaterialButton? = null
    private var sectionArchivistRouting: View? = null
    private var textArchivistRoutingValue: TextView? = null

    private var sliderTemperature: Slider? = null
    private var textTemperatureValue: TextView? = null
    private var btnResetTemperature: MaterialButton? = null
    private var textMinImportanceValue: TextView? = null
    private var fieldExtractionPrompt: TextInputEditText? = null
    private var btnResetPrompt: MaterialButton? = null
    private var fieldLorebookPrompt: TextInputEditText? = null
    private var btnResetLorebookPrompt: MaterialButton? = null
    private var btnSave: MaterialButton? = null

    /** Guards the suggestion field's TextWatcher while we set text programmatically. */
    private var suppressSuggestionWatcher = false

    /** Held until Save (spec §2 has an explicit Save button). 0 = No Minimum. */
    private var selectedImportance = 0
    private var selectedConversationAmount = ArchivistRequestBudget.CHOICE_AUTO

    // The endpoint gear edits only the currently selected stable-id profile.
    // Selection itself stays in this screen's dropdown and never opens a new
    // activity. A deleted selected profile clears its now-invalid model too.
    private val archivistEndpointEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.getBooleanExtra("deleted", false) == true) {
            preferences?.setArchivistEndpointId("")
            preferences?.setArchivistModel("")
            preferences?.setArchivistRoutingType(FavoriteModelObject.ROUTING_AUTOMATIC)
        }
        refreshArchivistRows()
    }

    /** Provider setup writes to the favorite only after Choose Provider saves.
     * On return, adopt the saved mode when the currently selected Memory
     * Assistant model now has complete setup; cancelling changes nothing. */
    private val archivistRoutingSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val endpointId = preferences?.getArchivistEndpointId().orEmpty()
            val model = preferences?.getArchivistModel().orEmpty()
            val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
            val mode = favorite?.routingType ?: FavoriteModelObject.ROUTING_AUTOMATIC
            if (!DedicatedModelRoutingPolicy.needsSetup(mode, favorite)) {
                preferences?.setArchivistRoutingType(mode)
            }
        }
        refreshArchivistRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_assistant_advanced_settings)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(this)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        refreshArchivistRows()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        switchMaxSuggestions = findViewById(R.id.switch_max_suggestions)
        fieldMaxSuggestions = findViewById(R.id.field_max_suggestions)
        textConversationAmountValue = findViewById(R.id.text_conversation_amount_value)
        sectionCustomConversationTokens = findViewById(R.id.section_custom_conversation_tokens)
        fieldCustomConversationTokens = findViewById(R.id.field_custom_conversation_tokens)
        textArchivistEndpointValue = findViewById(R.id.text_archivist_endpoint_value)
        btnEditArchivistEndpoint = findViewById(R.id.btn_edit_archivist_endpoint)
        textArchivistModelValue = findViewById(R.id.text_archivist_model_value)
        btnViewAllArchivistModels = findViewById(R.id.btn_view_all_archivist_models)
        sectionArchivistRouting = findViewById(R.id.section_archivist_routing)
        textArchivistRoutingValue = findViewById(R.id.text_archivist_routing_value)
        sliderTemperature = findViewById(R.id.slider_temperature)
        textTemperatureValue = findViewById(R.id.text_temperature_value)
        btnResetTemperature = findViewById(R.id.btn_reset_temperature)
        textMinImportanceValue = findViewById(R.id.text_min_importance_value)
        fieldExtractionPrompt = findViewById(R.id.field_extraction_prompt)
        btnResetPrompt = findViewById(R.id.btn_reset_prompt)
        fieldLorebookPrompt = findViewById(R.id.field_lorebook_prompt)
        btnResetLorebookPrompt = findViewById(R.id.btn_reset_lorebook_prompt)
        btnSave = findViewById(R.id.btn_save)
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true)

        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        /* ---- Maximum Suggestions Per Conversation ---- */
        setupMaxSuggestions()

        /* ---- Conversation Amount Per Request ---- */
        selectedConversationAmount = preferences?.getArchivistConversationAmount()
            ?: ArchivistRequestBudget.CHOICE_AUTO
        fieldCustomConversationTokens?.setText(
            (preferences?.getArchivistCustomConversationTokens()
                ?: ArchivistRequestBudget.CUSTOM_SUGGESTED_TOKENS).toString()
        )
        updateConversationAmount()
        textConversationAmountValue?.setOnClickListener { showConversationAmountPicker() }

        /* ---- Memory Assistant Endpoint & Model ---- */
        refreshArchivistRows()
        textArchivistEndpointValue?.setOnClickListener { showArchivistEndpointDropdown() }
        btnEditArchivistEndpoint?.setOnClickListener { openSelectedArchivistEndpointEditor() }
        textArchivistModelValue?.setOnClickListener { showArchivistModelDropdown() }
        btnViewAllArchivistModels?.setOnClickListener { openAllArchivistModels() }
        textArchivistRoutingValue?.setOnClickListener { showArchivistRoutingDropdown() }

        /* ---- Temperature ---- */
        val temperature = (preferences?.getArchivistTemperature() ?: RECOMMENDED_TEMPERATURE)
            .coerceIn(0.0f, 2.0f)
        sliderTemperature?.value = roundToStep(temperature)
        updateTemperatureLabel(sliderTemperature?.value ?: temperature)
        sliderTemperature?.addOnChangeListener { _, value, _ -> updateTemperatureLabel(value) }
        btnResetTemperature?.setOnClickListener {
            sliderTemperature?.value = RECOMMENDED_TEMPERATURE
            updateTemperatureLabel(RECOMMENDED_TEMPERATURE)
        }

        /* ---- Minimum Importance ---- */
        selectedImportance = (preferences?.getArchivistMinImportance() ?: 0).coerceIn(0, 5)
        updateImportanceLabel()
        textMinImportanceValue?.setOnClickListener { showImportancePicker() }

        /* ---- Associative Memory Prompt ---- */
        // The field shows exactly what an Associative run will send: the saved
        // prompt, or the built-in default when nothing is saved.
        val storedAssociative = preferences?.getArchivistCustomPrompt().orEmpty()
        fieldExtractionPrompt?.setText(storedAssociative.ifEmpty { ArchivistPrompt.SYSTEM })
        btnResetPrompt?.setOnClickListener { showResetAssociativePromptDialog() }

        /* ---- Lorebook Memory Prompt ---- */
        // Independent slot for the Lorebook analysis type: the field shows
        // exactly what a Lorebook run will send (saved prompt, or its own
        // built-in default). Never the Associative prompt — the schemas differ.
        val storedLorebook = preferences?.getArchivistLorebookPrompt().orEmpty()
        fieldLorebookPrompt?.setText(storedLorebook.ifEmpty { ArchivistPrompt.LOREBOOK_SYSTEM })
        btnResetLorebookPrompt?.setOnClickListener { showResetLorebookPromptDialog() }

        btnSave?.setOnClickListener { save() }
    }

    /* ------------------------------ Maximum Suggestions ------------------------------ */

    private fun setupMaxSuggestions() {
        val current = preferences?.getArchivistMaxSuggestions() ?: 0
        val on = current > 0
        switchMaxSuggestions?.isChecked = on
        fieldMaxSuggestions?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) setSuggestionFieldText(current.toString())

        switchMaxSuggestions?.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val value = (preferences?.getArchivistMaxSuggestions() ?: 0).let { if (it > 0) it else DEFAULT_MAX_SUGGESTIONS }
                preferences?.setArchivistMaxSuggestions(value)
                setSuggestionFieldText(value.toString())
                fieldMaxSuggestions?.visibility = View.VISIBLE
            } else {
                preferences?.setArchivistMaxSuggestions(0)
                fieldMaxSuggestions?.visibility = View.GONE
            }
        }

        fieldMaxSuggestions?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressSuggestionWatcher || switchMaxSuggestions?.isChecked != true) return
                val parsed = s?.toString()?.trim()?.toIntOrNull()
                if (parsed != null && parsed >= 1) preferences?.setArchivistMaxSuggestions(parsed)
            }
        })
    }

    private fun setSuggestionFieldText(text: String) {
        suppressSuggestionWatcher = true
        fieldMaxSuggestions?.setText(text)
        fieldMaxSuggestions?.setSelection(text.length)
        suppressSuggestionWatcher = false
    }

    /* ------------------------------ Conversation Amount ------------------------------ */

    private val conversationAmountChoices: List<String>
        get() = listOf(
            ArchivistRequestBudget.CHOICE_AUTO,
            ArchivistRequestBudget.CHOICE_SMALL,
            ArchivistRequestBudget.CHOICE_STANDARD,
            ArchivistRequestBudget.CHOICE_LARGE,
            ArchivistRequestBudget.CHOICE_CUSTOM
        )

    private fun conversationAmountLabel(choice: String): String = when (choice) {
        ArchivistRequestBudget.CHOICE_SMALL ->
            getString(R.string.memory_assistant_conversation_amount_small)
        ArchivistRequestBudget.CHOICE_STANDARD ->
            getString(R.string.memory_assistant_conversation_amount_standard)
        ArchivistRequestBudget.CHOICE_LARGE ->
            getString(R.string.memory_assistant_conversation_amount_large)
        ArchivistRequestBudget.CHOICE_CUSTOM ->
            getString(R.string.memory_assistant_conversation_amount_custom)
        else -> getString(R.string.memory_assistant_conversation_amount_auto)
    }

    private fun updateConversationAmount() {
        textConversationAmountValue?.text = conversationAmountLabel(selectedConversationAmount)
        sectionCustomConversationTokens?.visibility =
            if (selectedConversationAmount == ArchivistRequestBudget.CHOICE_CUSTOM) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun showConversationAmountPicker() {
        val anchor = textConversationAmountValue ?: return
        val choices = conversationAmountChoices
        val labels = choices.map(::conversationAmountLabel)
        val current = choices.indexOf(selectedConversationAmount).coerceAtLeast(0)
        AppDropdown.show(anchor, labels, current) { position ->
            selectedConversationAmount = choices[position]
            if (selectedConversationAmount == ArchivistRequestBudget.CHOICE_CUSTOM &&
                ArchivistRequestBudget.validateCustomTarget(
                    fieldCustomConversationTokens?.text?.toString()?.trim()?.toIntOrNull()
                ) == null
            ) {
                fieldCustomConversationTokens?.setText(
                    ArchivistRequestBudget.CUSTOM_SUGGESTED_TOKENS.toString()
                )
            }
            updateConversationAmount()
        }
    }

    /* ------------------------------ Memory Assistant Endpoint & Model ------------------------------ */

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

    private fun refreshArchivistRows() {
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        val endpoint = endpointProfiles().firstOrNull { it.id == endpointId }
        textArchivistEndpointValue?.text = endpoint?.label ?: getString(R.string.dropdown_select)
        btnEditArchivistEndpoint?.isEnabled = endpoint != null
        btnEditArchivistEndpoint?.alpha = if (endpoint != null) 1f else 0.38f

        val model = preferences?.getArchivistModel().orEmpty()
        textArchivistModelValue?.text = model.ifEmpty { getString(R.string.dropdown_select) }
        btnViewAllArchivistModels?.isEnabled = endpoint != null
        btnViewAllArchivistModels?.alpha = if (endpoint != null) 1f else 0.38f

        val supportsRouting = endpoint?.isOpenRouterRouting() == true
        sectionArchivistRouting?.visibility = if (supportsRouting) View.VISIBLE else View.GONE
        val routingEnabled = supportsRouting && model.isNotBlank()
        textArchivistRoutingValue?.isEnabled = routingEnabled
        textArchivistRoutingValue?.alpha = if (routingEnabled) 1f else 0.38f
        val selectedRouting = if (routingEnabled) {
            DedicatedModelRoutingPolicy.normalize(preferences?.getArchivistRoutingType().orEmpty())
        } else {
            FavoriteModelObject.ROUTING_AUTOMATIC
        }
        if (!routingEnabled && preferences?.getArchivistRoutingType() != FavoriteModelObject.ROUTING_AUTOMATIC) {
            preferences?.setArchivistRoutingType(FavoriteModelObject.ROUTING_AUTOMATIC)
        }
        textArchivistRoutingValue?.text = routingLabel(selectedRouting)
    }

    /** Select a saved user-defined endpoint in place; no navigation. */
    private fun showArchivistEndpointDropdown() {
        val dropdown = textArchivistEndpointValue ?: return
        val endpoints = endpointProfiles()
        val labels = endpoints.map { it.label }
        val currentId = preferences?.getArchivistEndpointId().orEmpty()
        AppDropdown.show(dropdown, labels, endpoints.indexOfFirst { it.id == currentId }) { position ->
            val pickedId = endpoints[position].id
            if (pickedId != currentId) {
                preferences?.setArchivistEndpointId(pickedId)
                // A model override belongs to its endpoint. Do not silently
                // carry a prior endpoint's model into the newly selected one.
                preferences?.setArchivistModel("")
                preferences?.setArchivistRoutingType(FavoriteModelObject.ROUTING_AUTOMATIC)
            }
            refreshArchivistRows()
        }
    }

    /** The gear is management, not selection: edit the selected profile. */
    private fun openSelectedArchivistEndpointEditor() {
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        val endpoints = endpointProfiles()
        val position = endpoints.indexOfFirst { it.id == endpointId }
        if (position < 0) return
        archivistEndpointEditorLauncher.launch(
            Intent(this, ApiEndpointEditorActivity::class.java)
                .putExtra("position", position)
                .putExtra("id", endpointId)
        )
    }

    /** Quick model selection is limited to favorites for the selected endpoint. */
    private fun showArchivistModelDropdown() {
        val dropdown = textArchivistModelValue ?: return
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        if (endpointId.isEmpty()) return
        val models = endpointFavoriteModels(endpointId)
        val current = preferences?.getArchivistModel().orEmpty()
        AppDropdown.show(dropdown, models, models.indexOf(current)) { position ->
            selectArchivistModel(models[position])
        }
    }

    /** The visible dropdown is the endpoint's favorites quick-pick. This
     * explicit View All path opens directly on the endpoint's live catalog,
     * matching Choose Provider rather than repeating a favorites landing. */
    private fun openAllArchivistModels() {
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        if (endpointId.isEmpty()) return

        val current = preferences?.getArchivistModel().orEmpty()
        val dialog = AdvancedModelSelectorDialogFragment.newAllModelsInstance(current, chatId, endpointId)
        dialog.setModelSelectedListener { model -> selectArchivistModel(model) }
        dialog.show(supportFragmentManager, "ArchivistModelSelector")
    }

    /** A favorite contributes its saved routing default; a catalog-only model
     * has no provider memory and therefore resets this feature to Automatic. */
    private fun selectArchivistModel(model: String) {
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        val endpoint = endpointProfiles().firstOrNull { it.id == endpointId }
        val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
        val routing = DedicatedModelRoutingPolicy.modeForSelectedModel(
            endpoint?.isOpenRouterRouting() == true,
            favorite
        )
        preferences?.setArchivistModel(model)
        preferences?.setArchivistRoutingType(routing)
        refreshArchivistRows()
    }

    private fun routingLabel(type: String): String = when (type) {
        FavoriteModelObject.ROUTING_PREFERRED -> getString(R.string.choose_provider_routing_preferred)
        FavoriteModelObject.ROUTING_ONLY -> getString(R.string.choose_provider_routing_only)
        else -> getString(R.string.choose_provider_routing_automatic)
    }

    private fun showArchivistRoutingDropdown() {
        val dropdown = textArchivistRoutingValue ?: return
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        val model = preferences?.getArchivistModel().orEmpty()
        if (endpointId.isBlank() || model.isBlank()) return
        val labels = DedicatedModelRoutingPolicy.routingTypes.map { routingLabel(it) }
        val current = DedicatedModelRoutingPolicy.routingTypes
            .indexOf(preferences?.getArchivistRoutingType().orEmpty())
            .coerceAtLeast(0)
        AppDropdown.show(dropdown, labels, current) { position ->
            val picked = DedicatedModelRoutingPolicy.routingTypes[position]
            val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
            if (DedicatedModelRoutingPolicy.needsSetup(picked, favorite)) {
                showArchivistRoutingSetupDialog(picked)
            } else {
                preferences?.setArchivistRoutingType(picked)
                refreshArchivistRows()
            }
        }
    }

    /** Preferred/Only without provider details uses the same explicit setup
     * flow as Quick Settings. The prior Memory Assistant choice remains in
     * place unless Choose Provider completes a valid save. */
    private fun showArchivistRoutingSetupDialog(mode: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.provider_mode_setup_title)
            .setMessage(R.string.provider_mode_setup_message)
            .setPositiveButton(R.string.provider_mode_setup_confirm) { _, _ ->
                val endpointId = preferences?.getArchivistEndpointId().orEmpty()
                val model = preferences?.getArchivistModel().orEmpty()
                val endpointPrefs = apiEndpointPreferences ?: return@setPositiveButton
                val favoritePrefs = favoriteModelsPreferences ?: return@setPositiveButton
                FavoriteRoutingActions.buildRoutingIntent(
                    this, endpointPrefs, favoritePrefs, model, endpointId, mode
                )?.let { archivistRoutingSetupLauncher.launch(it) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /* ------------------------------ Temperature ------------------------------ */

    private fun updateTemperatureLabel(value: Float) {
        textTemperatureValue?.text = String.format(Locale.getDefault(), "%.1f", value)
    }

    private fun roundToStep(value: Float): Float = (Math.round(value * 10f) / 10f).coerceIn(0.0f, 2.0f)

    /* ------------------------------ Minimum Importance ------------------------------ */

    private fun importanceLabel(level: Int): String = when (level) {
        0 -> getString(R.string.mem_importance_no_minimum)
        2 -> getString(R.string.mem_importance_2)
        3 -> getString(R.string.mem_importance_3)
        4 -> getString(R.string.mem_importance_4)
        5 -> getString(R.string.mem_importance_5)
        else -> getString(R.string.mem_importance_1)
    }

    private fun updateImportanceLabel() {
        val anchor = textMinImportanceValue ?: return
        val labels = (0..5).map { importanceLabel(it) }
        anchor.text = importanceLabel(selectedImportance)
        AppDropdown.sizeToOptions(anchor, labels) {
            (anchor.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        }
    }

    private fun showImportancePicker() {
        val anchor = textMinImportanceValue ?: return
        // No Minimum (0) is the default and first option; the numeric floors
        // 1–5 follow. Option index equals the stored value across 0..5.
        val labels = (0..5).map { importanceLabel(it) }

        AppDropdown.show(anchor, labels, selectedImportance.coerceIn(0, 5)) { position ->
            selectedImportance = position.coerceIn(0, 5)
            updateImportanceLabel()
        }
    }

    /* ------------------------------ Prompts ------------------------------ */

    /** Reset restores this type's built-in default: clear the saved prompt and
     *  show the built-in text (which is what an Associative run will then send). */
    private fun showResetAssociativePromptDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_assistant_adv_reset_prompt_confirm)
            .setPositiveButton(R.string.memory_assistant_adv_reset_prompt) { _, _ ->
                preferences?.setArchivistCustomPrompt("")
                fieldExtractionPrompt?.setText(ArchivistPrompt.SYSTEM)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /** The Lorebook slot's own Reset: restores the built-in Lorebook default,
     *  independent of the Associative prompt. */
    private fun showResetLorebookPromptDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_assistant_adv_reset_prompt_confirm)
            .setPositiveButton(R.string.memory_assistant_adv_reset_prompt) { _, _ ->
                preferences?.setArchivistLorebookPrompt("")
                fieldLorebookPrompt?.setText(ArchivistPrompt.LOREBOOK_SYSTEM)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ Save ------------------------------ */

    private fun save() {
        val customTokens = fieldCustomConversationTokens?.text
            ?.toString()?.trim()?.toIntOrNull()
        if (selectedConversationAmount == ArchivistRequestBudget.CHOICE_CUSTOM &&
            ArchivistRequestBudget.validateCustomTarget(customTokens) == null
        ) {
            fieldCustomConversationTokens?.requestFocus()
            fieldCustomConversationTokens?.selectAll()
            return
        }
        preferences?.setArchivistConversationAmount(selectedConversationAmount)
        if (customTokens != null) preferences?.setArchivistCustomConversationTokens(customTokens)

        preferences?.setArchivistTemperature(sliderTemperature?.value ?: RECOMMENDED_TEMPERATURE)
        preferences?.setArchivistMinImportance(selectedImportance)

        // Each prompt saves independently. Storing "" when the field still holds
        // the built-in default keeps the "empty = built-in" contract, so a later
        // change to the default text is picked up rather than frozen in.
        val text = fieldExtractionPrompt?.text?.toString()?.trim().orEmpty()
        preferences?.setArchivistCustomPrompt(if (text.isEmpty() || text == ArchivistPrompt.SYSTEM.trim()) "" else text)

        val loreText = fieldLorebookPrompt?.text?.toString()?.trim().orEmpty()
        preferences?.setArchivistLorebookPrompt(if (loreText.isEmpty() || loreText == ArchivistPrompt.LOREBOOK_SYSTEM.trim()) "" else loreText)

        Toast.makeText(this, R.string.memory_assistant_adv_saved, Toast.LENGTH_SHORT).show()
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
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    companion object {
        private const val RECOMMENDED_TEMPERATURE = 0.3f
        private const val DEFAULT_MAX_SUGGESTIONS = 10
    }
}
