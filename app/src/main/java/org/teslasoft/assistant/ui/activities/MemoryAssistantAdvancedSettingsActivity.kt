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

import org.teslasoft.assistant.ui.util.ScreenChrome

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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistPrompt
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.dedicated.DedicatedModelRows
import org.teslasoft.assistant.ui.dedicated.DedicatedParametersSection
import org.teslasoft.assistant.ui.widgets.AppDropdown

/**
 * "Advanced Memory Assistant Settings" — suggestion cap, endpoint/model
 * selection, and AI extraction tuning. Reached from the Memory Manager hub.
 *
 * The Memory Scanner's parameters (DedicatedParametersSection), Minimum
 * Importance, and the two analysis-type prompts (the Associative Memory Prompt
 * and the Lorebook Memory Prompt) are persisted only on Save (spec §2).
 * Maximum Suggestions, endpoint, model and routing save immediately on change
 * (DedicatedModelRows: the Memory Scanner's own lists and routing).
 *
 * User-facing name is "Memory Assistant"; the `Preferences.getArchivist*`
 * accessors keep the internal code name.
 */
class MemoryAssistantAdvancedSettingsActivity : SettingsPageActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchMaxSuggestions: MaterialSwitch? = null
    private var fieldMaxSuggestions: TextInputEditText? = null
    private var fieldScannerAverageTokens: TextInputEditText? = null
    private var fieldScannerMaximumTokens: TextInputEditText? = null

    private var textArchivistEndpointValue: TextView? = null
    private var btnEditArchivistEndpoint: ImageButton? = null
    private var textArchivistModelValue: TextView? = null
    private var btnViewAllArchivistModels: MaterialButton? = null
    private var sectionArchivistRouting: View? = null
    private var textArchivistRoutingValue: TextView? = null

    private var btnResetParameters: MaterialButton? = null
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

    private var modelRows: DedicatedModelRows? = null
    private var parametersSection: DedicatedParametersSection? = null

    private val archivistEndpointEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> modelRows?.onEndpointEditorResult(result) }

    private val archivistRoutingSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> modelRows?.onRoutingSetupResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_assistant_advanced_settings)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        modelRows?.refresh()
        parametersSection?.syncIfUnedited()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        switchMaxSuggestions = findViewById(R.id.switch_max_suggestions)
        fieldMaxSuggestions = findViewById(R.id.field_max_suggestions)
        fieldScannerAverageTokens = findViewById(R.id.field_scanner_average_tokens)
        fieldScannerMaximumTokens = findViewById(R.id.field_scanner_maximum_tokens)
        textArchivistEndpointValue = findViewById(R.id.text_archivist_endpoint_value)
        btnEditArchivistEndpoint = findViewById(R.id.btn_edit_archivist_endpoint)
        textArchivistModelValue = findViewById(R.id.text_archivist_model_value)
        btnViewAllArchivistModels = findViewById(R.id.btn_view_all_archivist_models)
        sectionArchivistRouting = findViewById(R.id.section_archivist_routing)
        textArchivistRoutingValue = findViewById(R.id.text_archivist_routing_value)
        btnResetParameters = findViewById(R.id.btn_reset_model_parameters)
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
        btnBack?.setOnClickListener { attemptExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { attemptExit() }
        })

        /* ---- Maximum Suggestions Per Conversation ---- */
        setupMaxSuggestions()

        fieldScannerAverageTokens?.setText(preferences?.getMemoryScannerAverageTokens()?.toString())
        fieldScannerMaximumTokens?.setText(preferences?.getMemoryScannerMaximumTokens()?.toString())

        /* ---- Memory Scanner model and parameters ---- */
        // The parameters are saved with this screen's Save; choosing a model
        // (which saves at once, like Quick Settings) shows its parameters.
        val section = DedicatedParametersSection(
            findViewById(R.id.root), ModelPickerScope.MEMORY_SCANNER, autoSave = false
        )
        parametersSection = section
        modelRows = DedicatedModelRows(
            this, ModelPickerScope.MEMORY_SCANNER,
            textArchivistEndpointValue!!, btnEditArchivistEndpoint!!, textArchivistModelValue!!,
            btnViewAllArchivistModels!!, sectionArchivistRouting!!, textArchivistRoutingValue!!,
            archivistEndpointEditorLauncher, archivistRoutingSetupLauncher
        ) { section.onModelSelected() }
        btnResetParameters?.setOnClickListener { section.showRecommended() }

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

    /**
     * Leaving with Memory Scanner parameter changes that were not saved asks
     * first (owner ruling, Nov 8 2026): Cancel stays on the screen, Save &
     * Exit saves and leaves (staying when something cannot be saved), and
     * Okay leaves without saving.
     */
    private fun attemptExit() {
        if (parametersSection?.hasUnsavedChanges() != true) {
            finish()
            return
        }
        val actions = layoutInflater.inflate(R.layout.dialog_three_actions_row, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_parameters_discard_title)
            .setMessage(R.string.memory_parameters_discard_message)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_cancel_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_middle_action).apply {
            setText(R.string.btn_save_and_exit)
            setOnClickListener {
                dialog.dismiss()
                if (save()) finish()
            }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_final_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }

    /** False when something on screen could not be saved (the user is
     *  shown why and stays on the screen). */
    private fun save(): Boolean {
        val average = fieldScannerAverageTokens?.text?.toString()?.trim()?.toIntOrNull()
        val maximum = fieldScannerMaximumTokens?.text?.toString()?.trim()?.toIntOrNull()
        if (average == null || average <= 0 || maximum == null) {
            (if (average == null || average <= 0) fieldScannerAverageTokens else fieldScannerMaximumTokens)?.apply {
                requestFocus()
                selectAll()
            }
            return false
        }
        if (maximum < average) {
            val actions = layoutInflater.inflate(R.layout.dialog_single_action, null)
            val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.summarizer_compaction_maximum_error_title)
                .setMessage(R.string.summarizer_compaction_maximum_error_message)
                .setView(actions)
                .create()
            actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
                setText(R.string.okay)
                setOnClickListener { dialog.dismiss() }
            }
            dialog.show()
            return false
        }
        preferences?.setMemoryScannerTokenAmounts(average, maximum)

        parametersSection?.commit()
        preferences?.setArchivistMinImportance(selectedImportance)

        // Each prompt saves independently. Storing "" when the field still holds
        // the built-in default keeps the "empty = built-in" contract, so a later
        // change to the default text is picked up rather than frozen in.
        val text = fieldExtractionPrompt?.text?.toString()?.trim().orEmpty()
        preferences?.setArchivistCustomPrompt(if (text.isEmpty() || text == ArchivistPrompt.SYSTEM.trim()) "" else text)

        val loreText = fieldLorebookPrompt?.text?.toString()?.trim().orEmpty()
        preferences?.setArchivistLorebookPrompt(if (loreText.isEmpty() || loreText == ArchivistPrompt.LOREBOOK_SYSTEM.trim()) "" else loreText)

        Toast.makeText(this, R.string.memory_assistant_adv_saved, Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ScreenChrome.fitSystemBarsAndKeyboard(
            findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0),
            actionBar,
            findViewById<android.view.View>(R.id.scroll)
        )
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
        private const val DEFAULT_MAX_SUGGESTIONS = 10
    }
}
