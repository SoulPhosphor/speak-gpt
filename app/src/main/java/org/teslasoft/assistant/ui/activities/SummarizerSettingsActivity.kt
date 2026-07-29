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
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedFavoriteModelSelectorDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.util.summarizer.SummarizerPrompts

/**
 * Summarizer Settings (conversation-summary-plan.md decision 2): the Summary
 * Model endpoint/model pickers (Memory Assistant interaction shape), the
 * Complete Messages default, the new-chats toggle, Summary Length, and the
 * five renameable prompt slots with the empty-prompt exit guard (decision 7).
 * Values save as they are changed; the prompt text saves as typed into the
 * selected slot.
 */
class SummarizerSettingsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var rowEndpoint: LinearLayout? = null
    private var textEndpointValue: TextView? = null
    private var rowModel: LinearLayout? = null
    private var textModelValue: TextView? = null
    private var fieldCompleteMessages: TextInputEditText? = null
    private var switchNewChats: MaterialSwitch? = null
    private var fieldSummaryLength: TextInputEditText? = null
    private var textPromptSlot: TextView? = null
    private var btnRenameSlot: MaterialButton? = null
    private var fieldPrompt: TextInputEditText? = null
    private var btnRevertPrompt: MaterialButton? = null

    private var suppressWatchers = false
    private var selectedSlot = 0

    private val endpointLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getStringExtra("apiEndpointId")
            if (id != null) preferences?.setSummarizerEndpointId(id)
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

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        rowEndpoint = findViewById(R.id.row_summarizer_endpoint)
        textEndpointValue = findViewById(R.id.text_summarizer_endpoint_value)
        rowModel = findViewById(R.id.row_summarizer_model)
        textModelValue = findViewById(R.id.text_summarizer_model_value)
        fieldCompleteMessages = findViewById(R.id.field_complete_messages)
        switchNewChats = findViewById(R.id.switch_summarizer_new_chats)
        fieldSummaryLength = findViewById(R.id.field_summary_length)
        textPromptSlot = findViewById(R.id.text_prompt_slot)
        btnRenameSlot = findViewById(R.id.btn_rename_slot)
        fieldPrompt = findViewById(R.id.field_prompt)
        btnRevertPrompt = findViewById(R.id.btn_revert_prompt)
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
        // The Dropdown.Value style makes the value clickable, so it consumes
        // taps instead of passing them to the row — it needs its own listener.
        rowEndpoint?.setOnClickListener { openEndpointPicker() }
        textEndpointValue?.setOnClickListener { openEndpointPicker() }
        rowModel?.setOnClickListener { openModelChooser() }
        textModelValue?.setOnClickListener { openModelChooser() }

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
        btnRevertPrompt?.setOnClickListener { showRevertDialog() }

        fieldPrompt?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatchers) return
                preferences?.setSummarizerSlotPrompt(selectedSlot, s?.toString() ?: "")
            }
        })
    }

    /* ------------------------------ Summary Model ------------------------------ */

    private fun refreshModelRows() {
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        textEndpointValue?.text = if (endpointId.isEmpty()) {
            getString(R.string.label_endpoint_none)
        } else {
            val endpoints = apiEndpointPreferences?.getApiEndpointsList(this) ?: arrayListOf()
            val label = endpoints.firstOrNull { it.id == endpointId }?.label
            if (!label.isNullOrEmpty()) label else getString(R.string.label_endpoint_none)
        }

        val model = preferences?.getSummarizerModel().orEmpty()
        textModelValue?.text = model.ifEmpty { getString(R.string.summarizer_unknown_model) }
    }

    private fun openEndpointPicker() {
        endpointLauncher.launch(Intent(this, ApiEndpointsListActivity::class.java))
    }

    /** The same model picker the chat and the Memory Assistant use, fetching
     *  from the summarizer's own endpoint (decision 2). */
    private fun openModelChooser() {
        val endpointId = preferences?.getSummarizerEndpointId().orEmpty()
        if (endpointId.isEmpty()) {
            openEndpointPicker()
            return
        }

        val current = preferences?.getSummarizerModel().orEmpty()
        val favorites = favoriteModelsPreferences?.getFavoriteModels() ?: arrayListOf()

        if (favorites.isEmpty()) {
            val dialog = AdvancedModelSelectorDialogFragment.newInstance(current, "", endpointId)
            dialog.setModelSelectedListener { model ->
                preferences?.setSummarizerModel(model)
                refreshModelRows()
            }
            dialog.show(supportFragmentManager, "SummarizerModelSelector")
        } else {
            val dialog = AdvancedFavoriteModelSelectorDialogFragment.newInstance(current, "", endpointId)
            dialog.setModelSelectedListener { model, pickedEndpointId ->
                preferences?.setSummarizerEndpointId(pickedEndpointId)
                preferences?.setSummarizerModel(model)
                refreshModelRows()
            }
            dialog.show(supportFragmentManager, "SummarizerFavoriteModelSelector")
        }
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
        suppressWatchers = true
        textPromptSlot?.text = slotName(selectedSlot)
        fieldPrompt?.setText(slotText(selectedSlot))
        suppressWatchers = false
        // Revert exists only under Storyteller and Reporter (decision 6).
        btnRevertPrompt?.visibility =
            if (selectedSlot <= 1) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showSlotDropdown(anchor: android.view.View) {
        val labels = (0 until SummarizerPrompts.SLOT_COUNT).map { slotName(it) }
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            selectSlot(position)
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

    private fun showRevertDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.summarizer_revert_prompt_confirm)
            .setPositiveButton(R.string.summarizer_revert_prompt) { _, _ ->
                preferences?.setSummarizerSlotPrompt(selectedSlot, "")
                loadSlotIntoEditor()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ Empty-prompt exit guard (decision 7) ------------------------------ */

    private fun attemptLeave() {
        // The guard judges what the user sees: an emptied prompt box blocks
        // the exit even on Storyteller/Reporter (their shipped text is only
        // restored through the dialog's Okay, never silently).
        val current = fieldPrompt?.text?.toString()?.trim().orEmpty()
        if (current.isNotBlank()) {
            finish()
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
