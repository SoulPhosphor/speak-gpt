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
 * sits in the action bar. (The full filter/sort rebuild is stage 2.3.)
 */
class MemoryBrowserActivity : MemoryScreenActivity() {

    private var showArchived = false

    private var presetCompanionId: String? = null
    private var presetWorldId: String? = null
    private var presetCampaignId: String? = null
    private var presetRoleplayCharacterId: String? = null
    private var titleOverride: String? = null

    override fun screenTitle(): String = titleOverride ?: getString(R.string.title_memories)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.btn_new_memory)
    override fun actionIcon(): Int = R.drawable.ic_storage

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

        val records: List<MemoryRecord> = when {
            presetCompanionId != null -> store.memoriesForCompanion(presetCompanionId!!, showArchived)
            presetWorldId != null -> store.memoriesForWorld(presetWorldId!!, showArchived)
            presetCampaignId != null -> store.memoriesForCampaign(presetCampaignId!!, showArchived)
            presetRoleplayCharacterId != null ->
                store.memoriesForRoleplayCharacter(presetRoleplayCharacterId!!, showArchived)
            else -> store.browseMemories(query.ifBlank { null }, showArchived, 200)
        }

        // In a scoped view the text query filters the already-narrowed list in
        // memory (the scope query has no LIKE); the main browser filters in SQL.
        val filtered = if (isScoped()) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) records
            else records.filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) }
        } else records

        return filtered.map { rowFor(it) }
    }

    private fun rowFor(m: MemoryRecord): MemoryRow {
        val badge = when {
            m.status == "archived" -> getString(R.string.memory_badge_archived)
            m.status == "superseded" -> getString(R.string.memory_badge_superseded)
            !m.protectionJson.isNullOrBlank() -> getString(R.string.memory_badge_protected)
            else -> null
        }
        val scopeLabel = if (m.scope == "companion") getString(R.string.memory_scope_companion)
        else getString(R.string.memory_scope_global)
        val subtitle = "$scopeLabel · ${m.content}"
        return MemoryRow(id = m.memoryId, title = m.title, subtitle = subtitle, badge = badge, hasAction = true)
    }

    /* ------------------------------ toolbar ------------------------------ */

    override fun onActionClick() {
        showArchived = !showArchived
        Toast.makeText(
            this,
            getString(if (showArchived) R.string.memory_showing_archived else R.string.memory_hiding_archived),
            Toast.LENGTH_SHORT
        ).show()
        reload()
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
