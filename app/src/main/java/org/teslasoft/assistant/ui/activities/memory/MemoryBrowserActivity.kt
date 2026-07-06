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

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The memory browser/editor (Phase 5, app_adaptation_notes §Required memory UI):
 * search (semantic when a model is installed, plus a text scan), add, edit,
 * protect/unprotect, archive/restore, delete (user-only, Material confirm), and
 * a per-memory change-log view. Every store read/write runs off the main
 * thread; failures degrade to a toast. This is the single GLOBAL browser over
 * all scopes and types; it can also be opened pre-filtered to a companion /
 * world / campaign / roleplay character (each of those pages passes its id so
 * the list and its "new memory" are confined to that scope). A Companions link
 * sits in the action bar. A filter/sort chip row (sort, scope, type, status,
 * source, tag + reset; §2.3) filters the list in memory; its state is held
 * statically so it survives leaving to the editor and back.
 */
class MemoryBrowserActivity : MemoryScreenActivity() {

    /** Filter/sort state, held statically so it survives leaving to the editor
     *  and coming back, and leaving and returning (§14 / approved browser
     *  design). Reset returns to defaults (newest, no filters). */
    private object F {
        var sort = "newest"    // newest | oldest
        var scope = "all"      // all | <scope key>
        var type = "all"       // all | <type key>
        var status = "all"     // all | active | draft | archived | superseded
        var source = "all"     // all | hand | learned
        var tag = "all"        // all | <tag text>
        fun reset() { sort = "newest"; scope = "all"; type = "all"; status = "all"; source = "all"; tag = "all" }
    }

    /** Distinct tags across the loaded set, for the tag filter dialog. */
    @Volatile private var availableTags: List<String> = emptyList()

    private var presetCompanionId: String? = null
    private var presetWorldId: String? = null
    private var presetCampaignId: String? = null
    private var presetRoleplayCharacterId: String? = null
    private var titleOverride: String? = null

    override fun screenTitle(): String = titleOverride ?: getString(R.string.title_memories)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.btn_new_memory)
    override fun showFilterBar(): Boolean = true
    override fun renderFilterBar() { buildFilterChips() }

    // The action bar's Companions link (only in the unscoped, global browser —
    // a scoped view is already about one thing, so the jump would be confusing).
    override fun secondaryActionIcon(): Int? =
        if (isScoped()) null else R.drawable.ic_user
    override fun secondaryActionLabel(): String? = getString(R.string.mem_comp_title)
    override fun onSecondaryActionClick() {
        startActivity(
            android.content.Intent(this, MemoryCompanionsActivity::class.java)
                .putExtra("chatId", chatId)
        )
    }

    private fun isScoped(): Boolean =
        presetCompanionId != null || presetWorldId != null ||
            presetCampaignId != null || presetRoleplayCharacterId != null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        presetCompanionId = intent.getStringExtra("companionId")
        presetWorldId = intent.getStringExtra("worldId")
        presetCampaignId = intent.getStringExtra("campaignId")
        presetRoleplayCharacterId = intent.getStringExtra("roleplayCharacterId")
        titleOverride = intent.getStringExtra("screenTitle")
        super.onCreate(savedInstanceState)
    }

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)

        // Fetch the base set (all statuses), then apply the filters/sort in
        // memory — the browser is global over all scopes and types, and a scoped
        // door pre-narrows to one target while the filters still apply.
        val base: List<MemoryRecord> = when {
            presetCompanionId != null -> store.memoriesForCompanion(presetCompanionId!!, true)
            presetWorldId != null -> store.memoriesForWorld(presetWorldId!!, true)
            presetCampaignId != null -> store.memoriesForCampaign(presetCampaignId!!, true)
            presetRoleplayCharacterId != null ->
                store.memoriesForRoleplayCharacter(presetRoleplayCharacterId!!, true)
            else -> store.browseMemories(null, true, 1000)
        }
        availableTags = base.flatMap { parseTags(it.tagsJson) }.distinct().sortedBy { it.lowercase() }

        val q = query.trim().lowercase()
        var list = base
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) }
        if (!isScoped() && F.scope != "all") list = list.filter { it.scope == F.scope }
        if (F.type != "all") list = list.filter { it.kind == F.type }
        if (F.status != "all") list = list.filter { it.status == F.status }
        if (F.source != "all") list = list.filter { sourceKey(it.provenanceSource) == F.source }
        if (F.tag != "all") list = list.filter { parseTags(it.tagsJson).any { t -> t.equals(F.tag, true) } }
        list = if (F.sort == "oldest") list.sortedBy { it.createdAt } else list.sortedByDescending { it.createdAt }

        return list.map { rowFor(it) }
    }

    // Row layout (§2.3): title -> status -> tags -> first line of the content.
    private fun rowFor(m: MemoryRecord): MemoryRow {
        val tags = parseTags(m.tagsJson)
        val firstLine = m.content.substringBefore('\n').trim()
        val subtitle = if (tags.isEmpty()) firstLine
        else tags.joinToString(" ") { "#$it" } + "\n" + firstLine
        return MemoryRow(id = m.memoryId, title = m.title, subtitle = subtitle, badge = statusLabel(m.status), hasAction = true)
    }

    /** "Learned from chat" once the Archivist exists; everything the user typed
     *  (including legacy rows with no provenance) reads as entered by hand. */
    private fun sourceKey(provenanceSource: String?): String =
        if (provenanceSource == null || provenanceSource == "user_entered" || provenanceSource == "user_stated") "hand" else "learned"

    private fun parseTags(tagsJson: String?): List<String> = try {
        if (tagsJson.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) { emptyList() }

    /* ------------------------------ filter chips ------------------------------ */

    private fun buildFilterChips() {
        val group = filterChipGroup() ?: return
        group.removeAllViews()
        addFilterChip(group, getString(R.string.mem_filter_sort), sortLabel()) { showSortDialog() }
        if (!isScoped()) addFilterChip(group, getString(R.string.mem_edit_label_scope), scopeFilterLabel()) { showScopeDialog() }
        addFilterChip(group, getString(R.string.mem_edit_label_type), typeFilterLabel()) { showTypeDialog() }
        addFilterChip(group, getString(R.string.mem_filter_status), statusFilterLabel()) { showStatusDialog() }
        addFilterChip(group, getString(R.string.mem_filter_source), sourceFilterLabel()) { showSourceDialog() }
        addFilterChip(group, getString(R.string.mem_filter_tag), tagFilterLabel()) { showTagDialog() }
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

    private val scopeKeys = listOf("all", "global", "real_life", "companion", "project", "world", "campaign", "rp_character")
    private val typeKeys = listOf("all", "fact", "preference", "event", "status", "instruction", "lore")
    private val statusKeys = listOf("all", "active", "draft", "archived", "superseded")
    private val sourceKeys = listOf("all", "hand", "learned")

    private fun allLabel() = getString(R.string.mem_filter_all)
    private fun sortLabel() = getString(if (F.sort == "oldest") R.string.mem_filter_sort_oldest else R.string.mem_filter_sort_newest)
    private fun scopeFilterLabel() = if (F.scope == "all") allLabel() else scopeLabel(F.scope)
    private fun typeFilterLabel() = if (F.type == "all") allLabel() else typeLabel(F.type)
    private fun statusFilterLabel() = if (F.status == "all") allLabel() else statusLabel(F.status)
    private fun sourceFilterLabel() = if (F.source == "all") allLabel() else sourceLabel(F.source)
    private fun tagFilterLabel() = if (F.tag == "all") allLabel() else F.tag

    private fun scopeLabel(key: String) = getString(
        when (key) {
            "global" -> R.string.mem_scope_global
            "real_life" -> R.string.mem_scope_real_life
            "companion" -> R.string.mem_scope_companion
            "project" -> R.string.mem_scope_project
            "world" -> R.string.mem_scope_world
            "campaign" -> R.string.mem_scope_campaign
            else -> R.string.mem_scope_rp_character
        }
    )

    private fun typeLabel(key: String) = getString(
        when (key) {
            "fact" -> R.string.mem_type_fact
            "preference" -> R.string.mem_type_preference
            "event" -> R.string.mem_type_event
            "status" -> R.string.mem_type_status
            "instruction" -> R.string.mem_type_instruction
            else -> R.string.mem_type_lore
        }
    )

    private fun statusLabel(key: String) = getString(
        when (key) {
            "draft" -> R.string.mem_status_draft
            "archived" -> R.string.mem_status_archived
            "superseded" -> R.string.mem_status_superseded
            else -> R.string.mem_status_active
        }
    )

    private fun sourceLabel(key: String) = getString(
        if (key == "learned") R.string.mem_source_learned else R.string.mem_source_hand
    )

    private fun showSortDialog() = pickFilter(
        R.string.mem_filter_sort,
        arrayOf(getString(R.string.mem_filter_sort_newest), getString(R.string.mem_filter_sort_oldest)),
        listOf("newest", "oldest"), F.sort
    ) { F.sort = it }

    private fun showScopeDialog() = pickFilter(
        R.string.mem_edit_label_scope,
        scopeKeys.map { if (it == "all") allLabel() else scopeLabel(it) }.toTypedArray(),
        scopeKeys, F.scope
    ) { F.scope = it }

    private fun showTypeDialog() = pickFilter(
        R.string.mem_edit_label_type,
        typeKeys.map { if (it == "all") allLabel() else typeLabel(it) }.toTypedArray(),
        typeKeys, F.type
    ) { F.type = it }

    private fun showStatusDialog() = pickFilter(
        R.string.mem_filter_status,
        statusKeys.map { if (it == "all") allLabel() else statusLabel(it) }.toTypedArray(),
        statusKeys, F.status
    ) { F.status = it }

    private fun showSourceDialog() = pickFilter(
        R.string.mem_filter_source,
        sourceKeys.map { if (it == "all") allLabel() else sourceLabel(it) }.toTypedArray(),
        sourceKeys, F.source
    ) { F.source = it }

    private fun showTagDialog() {
        val keys = listOf("all") + availableTags
        val labels = (listOf(allLabel()) + availableTags).toTypedArray()
        pickFilter(R.string.mem_filter_tag, labels, keys, F.tag) { F.tag = it }
    }

    override fun onAddClick() {
        if (!MemoryStore.isProvisioned(this)) {
            Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
            return
        }
        openEditor(null)
    }

    /* ------------------------------ rows ------------------------------ */

    override fun onClick(row: MemoryRow) {
        openEditor(row.id)
    }

    override fun onAction(row: MemoryRow, anchor: View) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val m = store.getMemory(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, m) }
        }
    }

    private fun showRowMenu(anchor: View, m: MemoryRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        if (m.protectionJson.isNullOrBlank()) {
            menu.menu.add(0, 2, 0, getString(R.string.action_protect))
        } else {
            menu.menu.add(0, 3, 0, getString(R.string.action_unprotect))
        }
        if (m.status == "active") {
            menu.menu.add(0, 4, 0, getString(R.string.action_archive))
        } else {
            menu.menu.add(0, 5, 0, getString(R.string.action_restore))
        }
        menu.menu.add(0, 6, 0, getString(R.string.action_history))
        menu.menu.add(0, 7, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(m.memoryId)
                2 -> showProtectDialog(m)
                3 -> applyProtection(m.memoryId, null)
                4 -> setStatus(m.memoryId, "archived")
                5 -> setStatus(m.memoryId, "active")
                6 -> showHistory(m.memoryId)
                7 -> confirmDelete(m)
            }
            true
        }
        menu.show()
    }

    /* ------------------------------ editor ------------------------------ */

    // The editor is a full-screen activity now (owner: no pop-ups for big
    // forms). A scoped browser pre-selects its scope + target for a NEW memory;
    // an existing memory carries its own scope/targets, so no preset is sent.
    // The browser reloads on resume, so returning refreshes the list.
    private fun openEditor(memoryId: String?) {
        val intent = android.content.Intent(this, MemoryEditorActivity::class.java)
            .putExtra("chatId", chatId)
        if (memoryId != null) {
            intent.putExtra("memoryId", memoryId)
        } else when {
            presetCompanionId != null ->
                intent.putExtra("presetScope", "companion").putExtra("presetTargetId", presetCompanionId)
            presetWorldId != null ->
                intent.putExtra("presetScope", "world").putExtra("presetTargetId", presetWorldId)
            presetCampaignId != null ->
                intent.putExtra("presetScope", "campaign").putExtra("presetTargetId", presetCampaignId)
            presetRoleplayCharacterId != null ->
                intent.putExtra("presetScope", "rp_character").putExtra("presetTargetId", presetRoleplayCharacterId)
        }
        startActivity(intent)
    }

    /* ------------------------------ actions ------------------------------ */

    private fun setStatus(memoryId: String, status: String) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            store.setMemoryStatus(memoryId, status, getString(R.string.memory_change_status))
            if (status == "active") Librarian.getInstance(this).reindexMemory(memoryId)
            runOnUiThread { reload() }
        }
    }

    private fun confirmDelete(m: MemoryRecord) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_delete_confirm_title)
            .setMessage(getString(R.string.memory_delete_confirm_msg, m.title))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                runOffThread {
                    MemoryStore.getInstance(this).deleteMemory(m.memoryId)
                    runOnUiThread {
                        Toast.makeText(this, R.string.memory_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /** Protect: capture the HANDLE WITH CARE handling lines (one per line). The
     *  enforcer renders these inseparably from the memory when it injects. */
    private fun showProtectDialog(m: MemoryRecord) {
        val field = EditText(this).apply {
            hint = getString(R.string.memory_protect_handling_hint)
            setText(existingHandling(m.protectionJson))
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.action_protect)
            .setMessage(R.string.memory_protect_dialog_msg)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val handling = field.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val json = JSONObject().apply {
                    put("is_protected", true)
                    put("handling", JSONArray(handling))
                }.toString()
                applyProtection(m.memoryId, json)
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun applyProtection(memoryId: String, protectionJson: String?) {
        runOffThread {
            MemoryStore.getInstance(this).setMemoryProtection(
                memoryId, protectionJson, getString(R.string.memory_change_protection)
            )
            runOnUiThread { reload() }
        }
    }

    private fun showHistory(memoryId: String) {
        runOffThread {
            val log = MemoryStore.getInstance(this).getMemoryChangeLog(memoryId)
            val text = if (log.isEmpty()) getString(R.string.memory_history_empty)
            else log.joinToString("\n\n") { e ->
                val when0 = e.at.take(19).replace("T", " ")
                val note = if (!e.note.isNullOrBlank()) " — ${e.note}" else ""
                "• ${e.action} (${e.actor}) $when0$note"
            }
            runOnUiThread {
                MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.memory_history_title)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
            }
        }
    }

    /* ------------------------------ json helpers ------------------------------ */

    private fun existingHandling(protectionJson: String?): String = try {
        if (protectionJson.isNullOrBlank()) "" else {
            val arr = JSONObject(protectionJson).optJSONArray("handling") ?: JSONArray()
            (0 until arr.length()).joinToString("\n") { arr.getString(it) }
        }
    } catch (_: Exception) { "" }
}
