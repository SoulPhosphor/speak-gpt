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
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.ListPopupWindow
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
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistPrompt
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedFavoriteModelSelectorDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
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

    private var rowArchivistEndpoint: LinearLayout? = null
    private var textArchivistEndpointValue: TextView? = null
    private var rowArchivistModel: LinearLayout? = null
    private var textArchivistModelValue: TextView? = null

    private var sliderTemperature: Slider? = null
    private var textTemperatureValue: TextView? = null
    private var btnResetTemperature: MaterialButton? = null
    private var rowMinImportance: LinearLayout? = null
    private var textMinImportanceValue: TextView? = null
    private var fieldExtractionPrompt: TextInputEditText? = null
    private var btnResetPrompt: MaterialButton? = null
    private var fieldLorebookPrompt: TextInputEditText? = null
    private var btnResetLorebookPrompt: MaterialButton? = null
    private var btnSave: MaterialButton? = null

    /** Guards the suggestion field's TextWatcher while we set text programmatically. */
    private var suppressSuggestionWatcher = false

    /** Held until Save (spec §2 has an explicit Save button). */
    private var selectedImportance = 1

    // Opens the full endpoint list (same screen the main chat uses to pick its
    // own endpoint); picking a profile there makes it the Memory Assistant's
    // endpoint.
    private val archivistEndpointLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getStringExtra("apiEndpointId")
            if (id != null) preferences?.setArchivistEndpointId(id)
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
        rowArchivistEndpoint = findViewById(R.id.row_archivist_endpoint)
        textArchivistEndpointValue = findViewById(R.id.text_archivist_endpoint_value)
        rowArchivistModel = findViewById(R.id.row_archivist_model)
        textArchivistModelValue = findViewById(R.id.text_archivist_model_value)
        sliderTemperature = findViewById(R.id.slider_temperature)
        textTemperatureValue = findViewById(R.id.text_temperature_value)
        btnResetTemperature = findViewById(R.id.btn_reset_temperature)
        rowMinImportance = findViewById(R.id.row_min_importance)
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

        /* ---- Memory Assistant Endpoint & Model ---- */
        refreshArchivistRows()
        // The Dropdown.Value style makes the value clickable, so it consumes
        // taps instead of passing them to the row — it needs its own listener.
        rowArchivistEndpoint?.setOnClickListener { openArchivistEndpointPicker() }
        textArchivistEndpointValue?.setOnClickListener { openArchivistEndpointPicker() }
        rowArchivistModel?.setOnClickListener { openArchivistModelChooser() }
        textArchivistModelValue?.setOnClickListener { openArchivistModelChooser() }

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
        selectedImportance = (preferences?.getArchivistMinImportance() ?: 1).coerceIn(1, 5)
        updateImportanceLabel()
        rowMinImportance?.setOnClickListener { showImportancePicker() }
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

    /* ------------------------------ Memory Assistant Endpoint & Model ------------------------------ */

    private fun refreshArchivistRows() {
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        textArchivistEndpointValue?.text = if (endpointId.isEmpty()) {
            getString(R.string.label_endpoint_none)
        } else {
            val endpoints = apiEndpointPreferences?.getApiEndpointsList(this) ?: arrayListOf()
            val label = endpoints.firstOrNull { it.id == endpointId }?.label
            if (!label.isNullOrEmpty()) label else getString(R.string.label_endpoint_none)
        }

        val model = preferences?.getArchivistModel().orEmpty()
        textArchivistModelValue?.text = model.ifEmpty { getString(R.string.label_archivist_model_none) }
    }

    /** Opens the same full endpoint list the main chat uses to pick its own
     *  endpoint. Picking a profile there makes it the Memory Assistant's. */
    private fun openArchivistEndpointPicker() {
        archivistEndpointLauncher.launch(Intent(this, ApiEndpointsListActivity::class.java))
    }

    /** Opens the same model picker the main chat's Quick Settings uses: your
     *  favorited models first (with a "All models" fallback to search), or
     *  straight to the live searchable list if you have no favorites yet.
     *  Either way it fetches from the Memory Assistant's own endpoint. If no
     *  endpoint has been chosen yet, there's nothing to fetch models from —
     *  send the user to pick one first instead. */
    private fun openArchivistModelChooser() {
        val endpointId = preferences?.getArchivistEndpointId().orEmpty()
        if (endpointId.isEmpty()) {
            openArchivistEndpointPicker()
            return
        }

        val current = preferences?.getArchivistModel().orEmpty()
        // One full-screen selector, scoped to the Archivist's own endpoint: it
        // opens on that endpoint's favorites and offers "View all".
        val dialog = AdvancedModelSelectorDialogFragment.newInstance(current, chatId, endpointId)
        dialog.setModelSelectedListener { model ->
            preferences?.setArchivistModel(model)
            refreshArchivistRows()
        }
        dialog.show(supportFragmentManager, "ArchivistModelSelector")
    }

    /* ------------------------------ Temperature ------------------------------ */

    private fun updateTemperatureLabel(value: Float) {
        textTemperatureValue?.text = String.format(Locale.getDefault(), "%.1f", value)
    }

    private fun roundToStep(value: Float): Float = (Math.round(value * 10f) / 10f).coerceIn(0.0f, 2.0f)

    /* ------------------------------ Minimum Importance ------------------------------ */

    private fun importanceLabel(level: Int): String = when (level) {
        2 -> getString(R.string.mem_importance_2)
        3 -> getString(R.string.mem_importance_3)
        4 -> getString(R.string.mem_importance_4)
        5 -> getString(R.string.mem_importance_5)
        else -> getString(R.string.mem_importance_1)
    }

    private fun updateImportanceLabel() {
        textMinImportanceValue?.text = importanceLabel(selectedImportance)
    }

    private fun showImportancePicker() {
        val anchor = textMinImportanceValue ?: return
        val labels = listOf(
            getString(R.string.mem_importance_1),
            getString(R.string.mem_importance_2),
            getString(R.string.mem_importance_3),
            getString(R.string.mem_importance_4),
            getString(R.string.mem_importance_5)
        )

        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            selectedImportance = (position + 1).coerceIn(1, 5)
            updateImportanceLabel()
        }
        popup.show()
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
