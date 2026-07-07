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

package org.teslasoft.assistant.ui.activities.memory

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.ModelRuleRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The full-screen model-rule add/edit form (Stage 4, §11 Revision 5). The rule
 * TEXT is the focus (a large box on top); below it the model strings this rule
 * applies to (removable chips + an Add-model dialog that offers the chat's
 * current model) and the organizing tags (a separate-pool chip input). A live
 * character count gives the honest size readout §11 asks for, warning softly
 * when the rule gets long but never blocking a save.
 *
 * A NEW hand-written rule is active immediately (the user authored it). Editing
 * a DRAFT (a Phase 6 Archivist suggestion) shows an Accept button that saves +
 * activates; plain Save keeps a draft a draft. All store work is off the main
 * thread; failures degrade to a toast.
 */
class ModelRuleEditorActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId: String = ""

    private var ruleId: String? = null
    private var existing: ModelRuleRecord? = null
    private var ready = false

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var titleView: TextView? = null
    private var fieldText: TextInputEditText? = null
    private var textSize: TextView? = null
    private var chipsModels: ChipGroup? = null
    private var btnAddModel: MaterialButton? = null
    private var chipsTags: ChipGroup? = null
    private var fieldTagInput: AutoCompleteTextView? = null
    private var btnSave: MaterialButton? = null
    private var btnAccept: MaterialButton? = null

    private var tagChips: ModelRuleTagChips? = null

    /** Model strings this rule applies to, in add order (deduped case-insensitively). */
    private val models = ArrayList<String>()

    companion object {
        /** Soft warning threshold for one rule's length (§11 — a nudge, never a
         *  block). Rules are short patches; past this they eat real context. */
        private const val SIZE_WARN_CHARS = 600
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_model_rule_editor)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        ruleId = intent.extras?.getString("ruleId")?.takeIf { it.isNotEmpty() }
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        titleView = findViewById(R.id.activity_title)
        fieldText = findViewById(R.id.field_rule_text)
        textSize = findViewById(R.id.text_rule_size)
        chipsModels = findViewById(R.id.chips_models)
        btnAddModel = findViewById(R.id.btn_add_model)
        chipsTags = findViewById(R.id.chips_tags)
        fieldTagInput = findViewById(R.id.field_tag_input)
        btnSave = findViewById(R.id.btn_rule_save)
        btnAccept = findViewById(R.id.btn_rule_accept)

        titleView?.setText(if (ruleId == null) R.string.model_rule_edit_title_new else R.string.model_rule_edit_title_edit)

        applyTheme()

        tagChips = ModelRuleTagChips(this, chipsTags!!, fieldTagInput!!)

        btnBack?.setOnClickListener { finish() }
        btnAddModel?.setOnClickListener { showAddModelDialog() }
        btnSave?.setOnClickListener { save(activate = false) }
        btnAccept?.setOnClickListener { save(activate = true) }

        fieldText?.doAfterTextChanged { refreshSize() }
        refreshSize()
        renderModelChips()

        loadEverything()
    }

    private fun loadEverything() {
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) {
                // A fresh store hasn't been created yet; a new rule provisions it
                // on save. Nothing to load for a new rule.
                if (ruleId == null) { runOnUiThread { ready = true }; return@runOffThread }
                runOnUiThread {
                    Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
                    ready = true
                }
                return@runOffThread
            }
            val store = MemoryStore.getInstance(this)
            val record = ruleId?.let { store.getModelRule(it) }
            val tags = ruleId?.let { store.getTagsForRule(it) } ?: emptyList()
            runOnUiThread {
                existing = record
                if (record != null) {
                    fieldText?.setText(record.text)
                    models.clear()
                    parseModelStrings(record.modelStringsJson).forEach { addModel(it) }
                    tagChips?.setInitial(tags)
                    if (record.status == "draft") btnAccept?.visibility = View.VISIBLE
                    refreshSize()
                }
                ready = true
            }
        }
    }

    /* ------------------------------ model strings ------------------------------ */

    private fun addModel(modelString: String) {
        val trimmed = modelString.trim()
        if (trimmed.isEmpty()) return
        if (models.any { it.equals(trimmed, ignoreCase = true) }) return
        models.add(trimmed)
        renderModelChips()
    }

    private fun renderModelChips() {
        val group = chipsModels ?: return
        group.removeAllViews()
        for (m in models) {
            val chip = Chip(this).apply {
                text = m
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    models.remove(m)
                    renderModelChips()
                }
            }
            group.addView(chip)
        }
    }

    private fun showAddModelDialog() {
        val field = EditText(this).apply {
            hint = getString(R.string.model_rule_edit_add_model_hint)
            // Offer the chat's current model so a one-word rule for "this model"
            // is one tap; the user can clear it and type a family string instead.
            setText(preferences?.getModel().orEmpty())
            setSelection(text.length)
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.model_rule_edit_add_model)
            .setMessage(R.string.model_rule_edit_add_model_msg)
            .setView(container)
            .setPositiveButton(R.string.btn_add) { _, _ -> addModel(field.text.toString()) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun parseModelStrings(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun modelsToJson(): String {
        val arr = JSONArray()
        models.forEach { arr.put(it) }
        return arr.toString()
    }

    /* ------------------------------ size readout ------------------------------ */

    private fun refreshSize() {
        val len = fieldText?.text?.toString()?.length ?: 0
        val tv = textSize ?: return
        if (len >= SIZE_WARN_CHARS) {
            tv.text = getString(R.string.model_rule_size_warn, len)
            tv.setTextColor(ResourcesCompat.getColor(resources, R.color.light_red, theme))
        } else {
            tv.text = getString(R.string.model_rule_size_count, len)
            tv.setTextColor(ResourcesCompat.getColor(resources, R.color.text_subtitle, theme))
        }
    }

    /* ------------------------------ save ------------------------------ */

    private fun save(activate: Boolean) {
        if (!ready) {
            Toast.makeText(this, R.string.mem_edit_still_loading, Toast.LENGTH_SHORT).show()
            return
        }
        // Flush a typed-but-unconfirmed tag so it isn't silently dropped.
        tagChips?.confirmText()

        val text = fieldText?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.model_rule_edit_required, Toast.LENGTH_SHORT).show()
            return
        }
        val modelsJson = modelsToJson()
        val tagIds = tagChips?.selectedTagIds() ?: emptyList()

        runOffThread {
            val store = MemoryStore.getInstance(this)
            val prior = existing ?: ruleId?.let { store.getModelRule(it) }
            val id = prior?.ruleId ?: MemoryStore.newId("mr_")
            val record = if (prior == null) {
                ModelRuleRecord(
                    ruleId = id,
                    text = text,
                    modelStringsJson = modelsJson,
                    status = "active",
                    sourceModelString = null,
                    createdAt = MemoryStore.nowIso(),
                    updatedAt = null
                )
            } else {
                prior.copy(
                    text = text,
                    modelStringsJson = modelsJson,
                    // Save keeps a draft a draft; Accept activates it.
                    status = if (activate) "active" else prior.status,
                    updatedAt = MemoryStore.nowIso()
                )
            }
            store.upsertModelRule(record)
            store.setTagsForRule(id, tagIds)
            runOnUiThread {
                Toast.makeText(this, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.mem_edit_op_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /* ------------------------------ theme + insets ------------------------------ */

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val amoled = isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true
        ThemeManager.getThemeManager().applyTheme(this, amoled)

        if (amoled) {
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

    private fun isDarkThemeEnabled(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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

    private fun pxToDp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}
