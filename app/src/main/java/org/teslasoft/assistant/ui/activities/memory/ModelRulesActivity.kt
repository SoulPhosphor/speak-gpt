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

import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.memory.ModelRuleRecord
import org.teslasoft.assistant.preferences.memory.ModelRuleTagRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.models.ModelCleanupReportStore
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.models.ModelIdentityCodec
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The Model rules browser (§11 Revision 6): the memory-browser-style
 * list of model-specific rules. Endpoint + exact model id is the identity — each row shows
 * the rule text, its endpoint/model targets, legacy strings, and tags. A filter/sort chip row (sort,
 * model, tag, status) narrows the view; picking a specific model also shows the
 * honest size readout §11 asks for (≈ characters that model pulls, with a soft
 * warning when large). A tag action in the bar opens the tag index; tapping a
 * rule opens the full-screen editor. Drafts (Phase 6 Archivist suggestions)
 * surface via the pinned Pending banner, which opens this screen in pending-only
 * mode — empty until Phase 6 files any. All store work is off the main thread.
 */
class ModelRulesActivity : MemoryScreenActivity() {

    private data class ModelFilterOption(
        val key: String,
        val label: String,
        val target: ModelIdentity? = null,
        val legacy: String? = null
    )

    /** Filter/sort state, held statically so it survives leaving to the editor
     *  and back (same pattern as the memory browser). */
    private object F {
        var sort = "newest"   // newest | oldest
        var model = "all"     // all | <model string>
        var tag = "all"       // all | <tagId>
        var status = "all"    // all | active | draft
        fun reset() { sort = "newest"; model = "all"; tag = "all"; status = "all" }
    }

    private var pendingOnly = false

    @Volatile private var availableModels: List<ModelFilterOption> = emptyList()
    @Volatile private var availableTags: List<ModelRuleTagRecord> = emptyList()
    @Volatile private var pendingCount: Int = 0
    @Volatile private var unavailableTargets: Set<ModelIdentity> = emptySet()

    override fun screenTitle(): String =
        if (pendingOnly) getString(R.string.model_rules_pending_title) else getString(R.string.row_model_rules_title)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.model_rules_btn_new)
    override fun showFilterBar(): Boolean = !pendingOnly
    override fun renderFilterBar() { buildFilterChips() }

    // The tag index lives behind the bar's secondary action (normal view only).
    override fun secondaryActionIcon(): Int? = if (pendingOnly) null else R.drawable.ic_book
    override fun secondaryActionLabel(): String? = getString(R.string.model_rule_tags_title)
    override fun onSecondaryActionClick() {
        startActivity(Intent(this, ModelRuleTagsActivity::class.java).putExtra("chatId", chatId))
    }

    override fun onRowsRendered() {
        if (!pendingOnly && pendingCount > 0) {
            setPendingBanner(getString(R.string.model_rules_pending_banner, pendingCount)) {
                startActivity(
                    Intent(this, ModelRulesActivity::class.java)
                        .putExtra("chatId", chatId)
                        .putExtra("pendingOnly", true)
                )
            }
        } else {
            setPendingBanner(null, null)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        pendingOnly = intent.getBooleanExtra("pendingOnly", false)
        super.onCreate(savedInstanceState)
    }

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        store.migrateUnambiguousLegacyModelTargets()

        val all = store.getModelRules(null)
        val exactOptions = all.flatMap { ModelIdentityCodec.decode(it.modelTargetsJson) }
            .distinct()
            .map { target ->
                ModelFilterOption(
                    key = exactFilterKey(target),
                    label = targetLabel(target),
                    target = target
                )
            }
        val legacyOptions = all.flatMap { parseModels(it.modelStringsJson) }
            .distinct()
            .map { legacy ->
                ModelFilterOption(
                    key = "legacy\u0000$legacy",
                    label = getString(R.string.model_rule_legacy_target_label, legacy),
                    legacy = legacy
                )
            }
        availableModels = (exactOptions + legacyOptions).sortedBy { it.label.lowercase() }
        if (F.model != "all" && availableModels.none { it.key == F.model }) {
            F.model = "all"
        }
        availableTags = store.getModelRuleTags()
        pendingCount = all.count { it.status == "draft" }
        unavailableTargets = ModelCleanupReportStore.get(this).load().unavailable

        // Tag filter is by id; resolve the member rule-ids once when active.
        val tagRuleIds: Set<String>? = if (F.tag != "all") {
            store.getModelRulesForTag(F.tag).map { it.ruleId }.toSet()
        } else null

        val q = query.trim().lowercase()
        var list = all
        list = if (pendingOnly) list.filter { it.status == "draft" }
        else if (F.status != "all") list.filter { it.status == F.status } else list
        if (q.isNotEmpty()) list = list.filter { it.text.lowercase().contains(q) }
        val selectedModel = availableModels.firstOrNull { it.key == F.model }
        if (F.model != "all") {
            list = if (selectedModel == null) emptyList()
            else list.filter { ruleMatchesFilter(it, selectedModel) }
        }
        if (tagRuleIds != null) list = list.filter { tagRuleIds.contains(it.ruleId) }
        list = if (F.sort == "oldest") list.sortedBy { it.createdAt } else list.sortedByDescending { it.createdAt }

        val rows = ArrayList<MemoryRow>()
        // Honest size readout (§11): with a specific model picked, show how many
        // characters that model actually pulls (sum of active matching rules).
        if (!pendingOnly && F.model != "all") {
            val chars = all.filter {
                it.status == "active" && selectedModel != null && ruleMatchesFilter(it, selectedModel)
            }.sumOf { it.text.length }
            val selectedLabel = selectedModel?.label ?: F.model
            val header = if (chars >= SIZE_WARN_CHARS)
                getString(R.string.model_rules_size_header_warn, selectedLabel, chars)
            else getString(R.string.model_rules_size_header, selectedLabel, chars)
            rows.add(MemoryRow(id = "size", title = header, isHeader = true))
        }
        list.forEach { rows.add(rowFor(it)) }
        return rows
    }

    private fun rowFor(r: ModelRuleRecord): MemoryRow {
        val firstLine = r.text.substringBefore('\n').trim()
        val targetLabels = ModelIdentityCodec.decode(r.modelTargetsJson).map(::targetLabel)
        val legacyLabels = parseModels(r.modelStringsJson).map {
            getString(R.string.model_rule_legacy_target_label, it)
        }
        val modelLabels = targetLabels + legacyLabels
        val modelsLine = if (modelLabels.isEmpty()) getString(R.string.model_rules_no_models)
        else modelLabels.joinToString(", ")
        val tags = tagNamesFor(r.ruleId)
        val subtitle = if (tags.isEmpty()) modelsLine
        else tags.joinToString(" ") { "#$it" } + "\n" + modelsLine
        val badge = if (r.status == "draft") getString(R.string.mem_status_draft) else null
        val hasUnavailableTarget = ModelIdentityCodec.decode(r.modelTargetsJson).any { it in unavailableTargets }
        return MemoryRow(
            id = r.ruleId,
            title = firstLine,
            subtitle = subtitle,
            badge = badge,
            hasAction = true,
            iconRes = if (hasUnavailableTarget) R.drawable.ic_report else null,
            iconTintError = hasUnavailableTarget
        )
    }

    private fun tagNamesFor(ruleId: String): List<String> = try {
        MemoryStore.getInstance(this).getTagsForRule(ruleId).map { it.name }
    } catch (_: Exception) { emptyList() }

    private fun parseModels(json: String?): List<String> = try {
        if (json.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) { emptyList() }

    /* ------------------------------ filter chips ------------------------------ */

    private fun buildFilterChips() {
        val group = filterChipGroup() ?: return
        group.removeAllViews()
        addFilterChip(group, getString(R.string.mem_filter_sort), sortLabel()) { showSortDialog() }
        addFilterChip(group, getString(R.string.model_rules_filter_model), modelFilterLabel()) { showModelDialog() }
        addFilterChip(group, getString(R.string.mem_filter_tag), tagFilterLabel()) { showTagDialog() }
        addFilterChip(group, getString(R.string.mem_filter_status), statusFilterLabel()) { showStatusDialog() }
        val reset = com.google.android.material.chip.Chip(this).apply {
            text = getString(R.string.mem_filter_reset)
            setOnClickListener { F.reset(); buildFilterChips(); reload() }
        }
        group.addView(reset)
    }

    private fun addFilterChip(group: android.view.ViewGroup, label: String, value: String, onClick: () -> Unit) {
        val chip = com.google.android.material.chip.Chip(this).apply {
            text = getString(R.string.mem_filter_chip, label, value)
            setOnClickListener { onClick() }
        }
        group.addView(chip)
    }

    private fun allLabel() = getString(R.string.mem_filter_all)
    private fun sortLabel() = getString(if (F.sort == "oldest") R.string.mem_filter_sort_oldest else R.string.mem_filter_sort_newest)
    private fun modelFilterLabel() = if (F.model == "all") allLabel()
        else availableModels.firstOrNull { it.key == F.model }?.label ?: allLabel()
    private fun tagFilterLabel() = if (F.tag == "all") allLabel() else (availableTags.firstOrNull { it.tagId == F.tag }?.name ?: allLabel())
    private fun statusFilterLabel() = when (F.status) {
        "active" -> getString(R.string.mem_status_active)
        "draft" -> getString(R.string.mem_status_draft)
        else -> allLabel()
    }

    private fun showSortDialog() = pickFilter(
        R.string.mem_filter_sort,
        arrayOf(getString(R.string.mem_filter_sort_newest), getString(R.string.mem_filter_sort_oldest)),
        listOf("newest", "oldest"), F.sort
    ) { F.sort = it }

    private fun showModelDialog() {
        val keys = listOf("all") + availableModels.map { it.key }
        val labels = (listOf(allLabel()) + availableModels.map { it.label }).toTypedArray()
        pickFilter(R.string.model_rules_filter_model, labels, keys, F.model) { F.model = it }
    }

    private fun exactFilterKey(target: ModelIdentity): String =
        "exact\u0000${target.endpointId}\u0000${target.modelId}"

    private fun ruleMatchesFilter(rule: ModelRuleRecord, option: ModelFilterOption): Boolean = when {
        option.target != null -> option.target in ModelIdentityCodec.decode(rule.modelTargetsJson)
        option.legacy != null -> option.legacy in parseModels(rule.modelStringsJson)
        else -> false
    }

    private fun targetLabel(target: ModelIdentity): String = getString(
        R.string.model_rule_target_label,
        endpointLabel(target.endpointId),
        target.modelId
    )

    private fun endpointLabel(endpointId: String): String = try {
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(this)
            .getApiEndpoint(this, endpointId)
        when {
            endpoint.provider.isNotBlank() &&
                !endpoint.provider.equals(endpoint.label, ignoreCase = true) ->
                "${endpoint.provider} — ${endpoint.label}"
            endpoint.label.isNotBlank() -> endpoint.label
            endpoint.provider.isNotBlank() -> endpoint.provider
            else -> getString(R.string.model_rule_missing_endpoint)
        }
    } catch (_: Exception) {
        getString(R.string.model_rule_missing_endpoint)
    }

    private fun showTagDialog() {
        val keys = listOf("all") + availableTags.map { it.tagId }
        val labels = (listOf(allLabel()) + availableTags.map { it.name }).toTypedArray()
        pickFilter(R.string.mem_filter_tag, labels, keys, F.tag) { F.tag = it }
    }

    private fun showStatusDialog() {
        val keys = listOf("all", "active", "draft")
        val labels = arrayOf(allLabel(), getString(R.string.mem_status_active), getString(R.string.mem_status_draft))
        pickFilter(R.string.mem_filter_status, labels, keys, F.status) { F.status = it }
    }

    private fun pickFilter(titleRes: Int, labels: Array<String>, keys: List<String>, current: String, apply: (String) -> Unit) {
        val idx = keys.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, idx) { d, which ->
                apply(keys[which]); buildFilterChips(); reload(); d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /* ------------------------------ rows ------------------------------ */

    override fun onAddClick() {
        openEditor(null)
    }

    override fun onClick(row: MemoryRow) {
        if (row.isHeader) return
        openEditor(row.id)
    }

    override fun onAction(row: MemoryRow, anchor: View) {
        if (row.isHeader) return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        menu.menu.add(0, 2, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(row.id)
                2 -> confirmDelete(row)
            }
            true
        }
        menu.show()
    }

    private fun openEditor(ruleId: String?) {
        val intent = Intent(this, ModelRuleEditorActivity::class.java).putExtra("chatId", chatId)
        if (ruleId != null) intent.putExtra("ruleId", ruleId)
        startActivity(intent)
    }

    private fun confirmDelete(row: MemoryRow) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.model_rules_delete_confirm_title)
            .setMessage(getString(R.string.model_rules_delete_confirm_msg, row.title))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteModelRule(row.id)
                    runOnUiThread {
                        Toast.makeText(this, R.string.memory_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    companion object {
        /** Soft-warning threshold for the per-model size readout header (§11). */
        private const val SIZE_WARN_CHARS = 2000
    }
}
