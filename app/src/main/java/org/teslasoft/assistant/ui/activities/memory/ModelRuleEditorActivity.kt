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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.SurfaceColors
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.memory.ModelRuleRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.models.ModelCleanupReportStore
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.models.ModelIdentityCodec
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.assistant.ui.widgets.AppRemovableChip

/**
 * The full-screen model-rule add/edit form (§11 Revision 6). The rule
 * TEXT is the focus (a large box on top); below it the exact endpoint/model
 * targets this rule applies to (removable chips + the shared favorites-first
 * model picker) and the organizing tags (a separate-pool chip input). A live
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
    private var fieldText: EditText? = null
    private var textSize: TextView? = null
    private var chipsModels: ChipGroup? = null
    private var fieldModelEndpoint: TextView? = null
    private var btnChooseModel: MaterialButton? = null
    private var textSelectedModel: TextView? = null
    private var btnAddModel: MaterialButton? = null
    private var chipsTags: ChipGroup? = null
    private var fieldTagInput: AutoCompleteTextView? = null
    private var btnAddTag: MaterialButton? = null
    private var btnSave: MaterialButton? = null
    private var btnAccept: MaterialButton? = null

    private var tagChips: ModelRuleTagChips? = null

    /** New exact targets and conservatively preserved pre-Revision-6 strings. */
    private val targets = ArrayList<ModelIdentity>()
    private val legacyModels = ArrayList<String>()
    private var selectedEndpointId: String = ""
    private var selectedModelId: String = ""
    private var unavailableTargets: Set<ModelIdentity> = emptySet()

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
        fieldModelEndpoint = findViewById(R.id.field_model_endpoint)
        btnChooseModel = findViewById(R.id.btn_choose_model)
        textSelectedModel = findViewById(R.id.text_selected_model)
        btnAddModel = findViewById(R.id.btn_add_model)
        chipsTags = findViewById(R.id.chips_tags)
        fieldTagInput = findViewById(R.id.field_tag_input)
        btnAddTag = findViewById(R.id.btn_add_tag)
        btnSave = findViewById(R.id.btn_rule_save)
        btnAccept = findViewById(R.id.btn_rule_accept)

        titleView?.setText(if (ruleId == null) R.string.model_rule_edit_title_new else R.string.model_rule_edit_title_edit)

        applyTheme()

        tagChips = ModelRuleTagChips(this, chipsTags!!, fieldTagInput!!)
        unavailableTargets = ModelCleanupReportStore.get(this).load().unavailable
        initializeEndpointSelection()

        btnBack?.setOnClickListener { finish() }
        fieldModelEndpoint?.setOnClickListener { showEndpointDropdown() }
        btnChooseModel?.setOnClickListener { openPendingModelPicker() }
        btnAddModel?.setOnClickListener { addSelectedTarget() }
        btnAddTag?.setOnClickListener { tagChips?.confirmText() }
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
            store.migrateUnambiguousLegacyModelTargets()
            val record = ruleId?.let { store.getModelRule(it) }
            val tags = ruleId?.let { store.getTagsForRule(it) } ?: emptyList()
            runOnUiThread {
                existing = record
                if (record != null) {
                    fieldText?.setText(record.text)
                    targets.clear()
                    targets.addAll(ModelIdentityCodec.decode(record.modelTargetsJson))
                    legacyModels.clear()
                    legacyModels.addAll(parseModelStrings(record.modelStringsJson))
                    renderModelChips()
                    tagChips?.setInitial(tags)
                    if (record.status == "draft") btnAccept?.visibility = View.VISIBLE
                    refreshSize()
                }
                ready = true
            }
        }
    }

    /* ------------------------------ model targets ------------------------------ */

    private fun addTarget(endpointId: String, modelId: String) {
        val target = ModelIdentity(endpointId, modelId)
        if (target.endpointId.isBlank() || target.modelId.isBlank()) return
        if (target in targets) return
        targets.add(target)
        renderModelChips()
    }

    private fun renderModelChips() {
        val group = chipsModels ?: return
        group.removeAllViews()
        for (target in targets.toList()) {
            val chip = AppRemovableChip.create(this, group).apply {
                text = getString(
                    R.string.model_rule_target_label,
                    endpointLabel(target.endpointId),
                    target.modelId
                )
                isCloseIconVisible = true
                if (target in unavailableTargets) {
                    setChipIconResource(R.drawable.ic_report)
                    isChipIconVisible = true
                    chipIconTint = ColorStateList.valueOf(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                    )
                    contentDescription = "${getString(R.string.model_cleanup_unavailable)}. $text"
                }
                setOnCloseIconClickListener {
                    targets.remove(target)
                    renderModelChips()
                }
            }
            group.addView(chip)
        }
        for (legacy in legacyModels.toList()) {
            val chip = AppRemovableChip.create(this, group).apply {
                text = getString(R.string.model_rule_legacy_target_label, legacy)
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    legacyModels.remove(legacy)
                    renderModelChips()
                }
            }
            group.addView(chip)
        }
    }

    private fun initializeEndpointSelection() {
        val endpoints = endpointProfiles()
        if (endpoints.isEmpty()) {
            selectedEndpointId = ""
            fieldModelEndpoint?.apply {
                text = getString(R.string.model_rule_select_endpoint)
                isEnabled = false
            }
            btnChooseModel?.isEnabled = false
            btnAddModel?.isEnabled = false
            return
        }
        val currentEndpointId = preferences?.getApiEndpointId().orEmpty()
        val initial = endpoints.firstOrNull { it.id == currentEndpointId } ?: endpoints.first()
        selectedEndpointId = initial.id
        fieldModelEndpoint?.apply {
            text = endpointDisplayLabel(initial)
            isEnabled = true
        }
        btnChooseModel?.isEnabled = true
        btnAddModel?.isEnabled = true
        renderPendingModel()
    }

    private fun showEndpointDropdown() {
        val dropdown = fieldModelEndpoint ?: return
        val endpoints = endpointProfiles()
        if (endpoints.isEmpty()) {
            Toast.makeText(this, R.string.model_rule_no_endpoints, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = endpoints.map(::endpointDisplayLabel)
        val current = endpoints.indexOfFirst { it.id == selectedEndpointId }
        AppDropdown.show(dropdown, labels, current) { position ->
            val endpoint = endpoints[position]
            if (endpoint.id != selectedEndpointId) {
                selectedEndpointId = endpoint.id
                selectedModelId = ""
            }
            dropdown.text = endpointDisplayLabel(endpoint)
            renderPendingModel()
        }
    }

    private fun openPendingModelPicker() {
        val endpoint = endpointProfiles().firstOrNull { it.id == selectedEndpointId }
        if (endpoint == null) {
            Toast.makeText(this, R.string.model_rule_no_endpoints, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = AdvancedModelSelectorDialogFragment.newModelRuleTargetInstance(
            chatId = chatId,
            endpointId = endpoint.id,
            currentChatModel = preferences?.getModel().orEmpty().takeIf {
                preferences?.getApiEndpointId().orEmpty() == endpoint.id
            }.orEmpty()
        )
        dialog.setModelSelectedListener { modelId ->
            selectedModelId = modelId
            renderPendingModel()
        }
        dialog.show(supportFragmentManager, "ModelRuleModelSelector")
    }

    private fun renderPendingModel() {
        textSelectedModel?.apply {
            text = selectedModelId
            visibility = if (selectedModelId.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun addSelectedTarget() {
        if (selectedEndpointId.isBlank() || selectedModelId.isBlank()) {
            openPendingModelPicker()
            return
        }
        addTarget(selectedEndpointId, selectedModelId)
    }

    private fun endpointProfiles(): List<ApiEndpointObject> =
        ApiEndpointPreferences.getApiEndpointPreferences(this)
            .getApiEndpointsList(this)
            .filter { it.id.isNotBlank() && endpointDisplayLabel(it).isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { endpointDisplayLabel(it).lowercase() }

    private fun endpointDisplayLabel(endpoint: ApiEndpointObject): String = when {
        endpoint.provider.isNotBlank() &&
            !endpoint.provider.equals(endpoint.label, ignoreCase = true) ->
            "${endpoint.provider} — ${endpoint.label}"
        endpoint.label.isNotBlank() -> endpoint.label
        else -> endpoint.provider
    }

    private fun endpointLabel(endpointId: String): String = try {
        endpointProfiles().firstOrNull { it.id == endpointId }
            ?.let(::endpointDisplayLabel)
            ?: getString(R.string.model_rule_missing_endpoint)
    } catch (_: Exception) {
        getString(R.string.model_rule_missing_endpoint)
    }

    private fun parseModelStrings(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun legacyModelsToJson(): String {
        val arr = JSONArray()
        legacyModels.forEach { arr.put(it) }
        return arr.toString()
    }

    /* ------------------------------ size readout ------------------------------ */

    private fun refreshSize() {
        val len = fieldText?.text?.toString()?.length ?: 0
        val tv = textSize ?: return
        if (len >= SIZE_WARN_CHARS) {
            tv.text = getString(R.string.model_rule_size_warn, len)
            tv.setTextColor(MaterialColors.getColor(tv, androidx.appcompat.R.attr.colorError))
        } else {
            tv.text = getString(R.string.model_rule_size_count, len)
            tv.setTextColor(MaterialColors.getColor(tv, R.attr.appSubtleTextColor))
        }
    }

    /* ------------------------------ save ------------------------------ */

    private fun save(activate: Boolean) {
        if (!ready) {
            Toast.makeText(this, R.string.mem_edit_still_loading, Toast.LENGTH_SHORT).show()
            return
        }

        // Flush a typed-but-unconfirmed tag before reading the selected ids.
        // A newly typed tag is created off-thread, so saving continues only
        // after that work has finished and its chip is part of the selection.
        tagChips?.confirmText { saveAfterTagFlush(activate) }
            ?: saveAfterTagFlush(activate)
    }

    private fun saveAfterTagFlush(activate: Boolean) {
        val text = fieldText?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.model_rule_edit_required, Toast.LENGTH_SHORT).show()
            return
        }
        val legacyModelsJson = legacyModelsToJson()
        val targetsJson = ModelIdentityCodec.encode(targets)
        val tagIds = tagChips?.selectedTagIds() ?: emptyList()

        runOffThread {
            val store = MemoryStore.getInstance(this)
            val prior = existing ?: ruleId?.let { store.getModelRule(it) }
            val willBeActive = prior == null || prior.status == "active" || activate
            if (willBeActive && targets.isEmpty() && legacyModels.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, R.string.model_rule_edit_model_required, Toast.LENGTH_SHORT).show()
                }
                return@runOffThread
            }
            val id = prior?.ruleId ?: MemoryStore.newId("mr_")
            val record = if (prior == null) {
                ModelRuleRecord(
                    ruleId = id,
                    text = text,
                    modelStringsJson = legacyModelsJson,
                    status = "active",
                    sourceModelString = null,
                    createdAt = MemoryStore.nowIso(),
                    updatedAt = null,
                    modelTargetsJson = targetsJson
                )
            } else {
                prior.copy(
                    text = text,
                    modelStringsJson = legacyModelsJson,
                    modelTargetsJson = targetsJson,
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
