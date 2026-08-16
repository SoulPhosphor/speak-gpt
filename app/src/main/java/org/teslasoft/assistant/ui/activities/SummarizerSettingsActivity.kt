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
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
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
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.assistant.util.summarizer.SummarizerPrompts
import java.util.Locale

/**
 * Summarizer Settings (conversation-summary-plan.md decision 2): the Summary
 * Model endpoint/model pickers (Memory Assistant interaction shape), the
 * Complete Messages default, the new-chats toggle, Summary Length, and the
 * five renameable prompt presets with the empty-prompt exit guard (decision 7).
 * Model, toggle, and number values save as they are changed. The prompt box is
 * a draft: it persists only through the Save button, Revert restores the
 * shipped prompt on presets one and two and the last saved text on presets
 * three to five, and leaving or switching presets with unsaved changes asks
 * before discarding (owner ruling, July 29 2026).
 */
class SummarizerSettingsActivity : FragmentActivity() {

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
    private var fieldSummaryLength: TextInputEditText? = null
    private var textPromptSlot: TextView? = null
    private var btnRenameSlot: MaterialButton? = null
    private var fieldPrompt: TextInputEditText? = null
    private var btnRevertPrompt: MaterialButton? = null
    private var btnSavePrompt: MaterialButton? = null
    private var fieldImageSummaryPrompt: TextInputEditText? = null
    private var btnSaveImageSummaryPrompt: MaterialButton? = null

    private var suppressWatchers = false
    private var selectedSlot = 0

    /** The selected preset's last saved text — the draft's dirty baseline. */
    private var savedPromptText = ""

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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptLeave()
            }
        })
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
        fieldSummaryLength = findViewById(R.id.field_summary_length)
        textPromptSlot = findViewById(R.id.text_prompt_slot)
        btnRenameSlot = findViewById(R.id.btn_rename_slot)
        fieldPrompt = findViewById(R.id.field_prompt)
        btnRevertPrompt = findViewById(R.id.btn_revert_prompt)
        btnSavePrompt = findViewById(R.id.btn_save_prompt)
        fieldImageSummaryPrompt = findViewById(R.id.field_image_summary_prompt)
        btnSaveImageSummaryPrompt = findViewById(R.id.btn_save_image_summary_prompt)
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
        btnBack?.setOnClickListener { attemptLeave() }

        refreshModelRows()
        textEndpointValue?.setOnClickListener { showEndpointDropdown() }
        btnEditEndpoint?.setOnClickListener { openSelectedEndpointEditor() }
        textModelValue?.setOnClickListener { showModelDropdown() }
        btnViewAllModels?.setOnClickListener { openAllModels() }
        textRoutingValue?.setOnClickListener { showRoutingDropdown() }

        suppressWatchers = true
        fieldCompleteMessages?.setText(preferences?.getSummarizerDefaultWindow()?.toString() ?: "20")
        fieldSummaryLength?.setText(preferences?.getSummarizerLength()?.toString() ?: "300")
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

        fieldSummaryLength?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatchers) return
                val parsed = s?.toString()?.trim()?.toIntOrNull()
                if (parsed != null && parsed >= 10) preferences?.setSummarizerLength(parsed)
            }
        })

        switchNewChats?.isChecked = preferences?.getSummarizerOnForNewChats() ?: false
        switchNewChats?.setOnCheckedChangeListener { _, checked ->
            preferences?.setSummarizerOnForNewChats(checked)
        }

        selectedSlot = preferences?.getSummarizerSelectedSlot() ?: 0
        loadSlotIntoEditor()

        textPromptSlot?.setOnClickListener { showSlotDropdown(it) }
        btnRenameSlot?.setOnClickListener { showRenameDialog() }
        btnRevertPrompt?.setOnClickListener { onRevertPressed() }
        btnSavePrompt?.setOnClickListener { savePrompt() }

        // Image Summary Prompt: a single global prompt, blank showing the
        // shipped default, saved only through its own Save button.
        fieldImageSummaryPrompt?.setText(
            preferences?.getImageSummaryPrompt().orEmpty()
                .ifBlank { SummarizerPrompts.IMAGE_SUMMARY }
        )
        btnSaveImageSummaryPrompt?.setOnClickListener {
            preferences?.setImageSummaryPrompt(
                fieldImageSummaryPrompt?.text?.toString().orEmpty()
            )
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

    /* ------------------------------ Prompt slots ------------------------------ */

    private fun slotName(slot: Int): String {
        val stored = preferences?.getSummarizerSlotName(slot).orEmpty()
        if (stored.isNotEmpty()) return stored
        return when (slot) {
            0 -> SummarizerPrompts.STORYTELLER_NAME
            1 -> SummarizerPrompts.REPORTER_NAME
            else -> getString(R.string.summarizer_slot_default_name, slot + 1)
        }
    }

    /** The slot's effective prompt text: stored, else shipped (slots 1–2). */
    private fun slotText(slot: Int): String =
        preferences?.getSummarizerSlotPrompt(slot).orEmpty()
            .ifBlank { SummarizerPrompts.shippedPrompt(slot) }

    private fun loadSlotIntoEditor() {
        textPromptSlot?.text = slotName(selectedSlot)
        savedPromptText = slotText(selectedSlot)
        fieldPrompt?.setText(savedPromptText)
    }

    private fun isPromptDirty(): Boolean =
        (fieldPrompt?.text?.toString() ?: "") != savedPromptText

    private fun showSlotDropdown(anchor: android.view.View) {
        val labels = (0 until SummarizerPrompts.SLOT_COUNT).map { slotName(it) }
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            if (position != selectedSlot && isPromptDirty()) {
                DiscardChangesDialog.show(this, R.string.discard_summarizer_prompt_changes_q) {
                    selectSlot(position)
                }
            } else {
                selectSlot(position)
            }
        }
        popup.show()
    }

    private fun selectSlot(slot: Int) {
        if (slot == selectedSlot) return
        selectedSlot = slot
        preferences?.setSummarizerSelectedSlot(slot)
        recordSlotUse(slot)
        loadSlotIntoEditor()
    }

    /** Newest-first recency, backing the decision 7 fallback rule. */
    private fun recordSlotUse(slot: Int) {
        val recency = preferences?.getSummarizerSlotRecency().orEmpty()
            .split(",").mapNotNull { it.trim().toIntOrNull() }
        val updated = (listOf(slot) + recency.filter { it != slot })
            .take(SummarizerPrompts.SLOT_COUNT)
        preferences?.setSummarizerSlotRecency(updated.joinToString(","))
    }

    private fun showRenameDialog() {
        val input = TextInputEditText(this)
        input.setText(slotName(selectedSlot))
        input.setSingleLine(true)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, 0, pad, 0)
        container.addView(input)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.summarizer_rename_slot_title)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    preferences?.setSummarizerSlotName(selectedSlot, name)
                    textPromptSlot?.text = name
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /** Saves the draft as the selected preset's prompt. */
    private fun savePrompt() {
        val draft = fieldPrompt?.text?.toString() ?: ""
        preferences?.setSummarizerSlotPrompt(selectedSlot, draft)
        savedPromptText = draft
    }

    /** Presets one and two revert to the shipped prompt (confirmed first);
     *  presets three to five revert the draft to the last saved text. Neither
     *  writes anything — Save still decides what persists. */
    private fun onRevertPressed() {
        if (selectedSlot <= 1) {
            showRevertDialog()
        } else {
            fieldPrompt?.setText(savedPromptText)
        }
    }

    private fun showRevertDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.summarizer_revert_prompt_confirm)
            .setPositiveButton(R.string.summarizer_revert_prompt) { _, _ ->
                fieldPrompt?.setText(SummarizerPrompts.shippedPrompt(selectedSlot))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ Empty-prompt exit guard (decision 7) ------------------------------ */

    private fun attemptLeave() {
        // The guard judges what the user sees: an emptied prompt box blocks
        // the exit even on Storyteller/Reporter (their shipped text is only
        // restored through the dialog's Okay, never silently). A non-empty
        // draft with unsaved changes asks before discarding instead.
        val current = fieldPrompt?.text?.toString().orEmpty()
        if (current.isNotBlank()) {
            if (isPromptDirty()) {
                DiscardChangesDialog.show(this, R.string.discard_summarizer_prompt_changes_q) {
                    finish()
                }
            } else {
                finish()
            }
            return
        }

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.summarizer_empty_prompt_guard)
            .setPositiveButton(R.string.okay) { _, _ ->
                applyEmptyPromptFallback()
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /** Decision 7 fallback: the most recently used slot that still has text;
     *  if none exists, slot one's shipped prompt is restored and selected —
     *  summarizing can never run on empty instructions. */
    private fun applyEmptyPromptFallback() {
        val recency = preferences?.getSummarizerSlotRecency().orEmpty()
            .split(",").mapNotNull { it.trim().toIntOrNull() }
        val candidates = recency + (0 until SummarizerPrompts.SLOT_COUNT)
        for (slot in candidates) {
            if (slot == selectedSlot) continue
            if (slotText(slot).isNotBlank()) {
                selectedSlot = slot
                preferences?.setSummarizerSelectedSlot(slot)
                recordSlotUse(slot)
                return
            }
        }
        preferences?.setSummarizerSlotPrompt(0, "")
        preferences?.setSummarizerSelectedSlot(0)
        recordSlotUse(0)
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
