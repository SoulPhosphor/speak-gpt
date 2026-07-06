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
import org.teslasoft.assistant.ui.fragments.dialogs.EditMemoryDialogFragment

/**
 * The memory browser/editor (Phase 5, app_adaptation_notes §Required memory UI):
 * search (semantic when a model is installed, plus a text scan), add, edit,
 * protect/unprotect, archive/restore, delete (user-only, Material confirm), and
 * a per-memory change-log view. Every store read/write runs off the main
 * thread; failures degrade to a toast. Can be opened scoped to a world /
 * campaign / roleplay character (the scoped browsers pass those ids so its list
 * and its "new memory" are confined to that scope).
 */
class MemoryBrowserActivity : MemoryScreenActivity() {

    private var showArchived = false

    private var presetWorldId: String? = null
    private var presetCampaignId: String? = null
    private var presetRoleplayCharacterId: String? = null
    private var titleOverride: String? = null

    override fun screenTitle(): String = titleOverride ?: getString(R.string.title_memories)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.btn_new_memory)
    override fun actionIcon(): Int = R.drawable.ic_storage

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
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
            presetWorldId != null -> store.memoriesForWorld(presetWorldId!!, showArchived)
            presetCampaignId != null -> store.memoriesForCampaign(presetCampaignId!!, showArchived)
            presetRoleplayCharacterId != null ->
                store.memoriesForRoleplayCharacter(presetRoleplayCharacterId!!, showArchived)
            else -> store.browseMemories(query.ifBlank { null }, showArchived, 200)
        }

        // In a scoped view the text query filters the already-narrowed list in
        // memory (the scope query has no LIKE); the main browser filters in SQL.
        val filtered = if (presetWorldId != null || presetCampaignId != null || presetRoleplayCharacterId != null) {
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
            m.alwaysLoad -> getString(R.string.memory_badge_always)
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

    private fun openEditor(memoryId: String?) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val companions = store.getCompanions().filter { it.status != "draft" }
            val companionIds = ArrayList(companions.map { it.companionId })
            val companionNames = ArrayList(companions.map { it.currentName })

            val existing = memoryId?.let { store.getMemory(it) }
            runOnUiThread {
                val dialog = EditMemoryDialogFragment.newInstance(
                    memoryId = existing?.memoryId ?: "",
                    title = existing?.title ?: "",
                    content = existing?.content ?: "",
                    kind = existing?.kind ?: "note",
                    importance = existing?.importance ?: 3,
                    tags = existing?.let { tagsToText(it.tagsJson) } ?: "",
                    alwaysLoad = existing?.alwaysLoad ?: false,
                    scope = existing?.scope ?: "global",
                    companionId = existing?.companionIds?.firstOrNull(),
                    presetWorldId = existing?.worldId ?: presetWorldId,
                    presetCampaignId = existing?.campaignId ?: presetCampaignId,
                    presetRoleplayCharacterId = existing?.roleplayCharacterId ?: presetRoleplayCharacterId,
                    companionIds = companionIds,
                    companionNames = companionNames
                )
                dialog.setListener(editorListener)
                dialog.show(supportFragmentManager, "EditMemoryDialogFragment")
            }
        }
    }

    private val editorListener = object : EditMemoryDialogFragment.Listener {
        override fun onSave(
            memoryId: String, title: String, content: String, kind: String, importance: Int,
            tags: String, alwaysLoad: Boolean, scope: String, companionId: String?,
            presetWorldId: String?, presetCampaignId: String?, presetRoleplayCharacterId: String?
        ) {
            runOffThread {
                val store = MemoryStore.getInstance(this@MemoryBrowserActivity)
                val tagsJson = textToTagsJson(tags)
                val companionIds = if (scope == "companion" && companionId != null) listOf(companionId) else emptyList()
                if (memoryId.isEmpty()) {
                    val record = MemoryRecord(
                        memoryId = MemoryStore.newId("m-"),
                        scope = scope, kind = kind, title = title, content = content,
                        embeddingText = null, tagsJson = tagsJson, importance = importance,
                        alwaysLoad = alwaysLoad, worldId = presetWorldId,
                        roleplayCharacterId = presetRoleplayCharacterId, campaignId = presetCampaignId,
                        protectionJson = null, modeHintsJson = "[]",
                        provenanceSource = "user_entered", provenanceConfidence = null,
                        provenanceNotedOn = MemoryStore.nowIso(), provenanceContext = null,
                        createdAt = MemoryStore.nowIso(), updatedAt = null, status = "active",
                        supersedes = null, companionIds = companionIds, entityRefs = emptyList(),
                        changeLog = emptyList(), origin = "user"
                    )
                    store.insertMemory(record)
                    Librarian.getInstance(this@MemoryBrowserActivity).reindexMemory(record.memoryId)
                } else {
                    val prior = store.getMemory(memoryId) ?: return@runOffThread
                    val updated = prior.copy(
                        scope = scope, kind = kind, title = title, content = content,
                        importance = importance, alwaysLoad = alwaysLoad, tagsJson = tagsJson,
                        worldId = presetWorldId, roleplayCharacterId = presetRoleplayCharacterId,
                        campaignId = presetCampaignId, companionIds = companionIds
                    )
                    store.updateMemory(updated, getString(R.string.memory_change_edited))
                    Librarian.getInstance(this@MemoryBrowserActivity).reindexMemory(memoryId)
                }
                runOnUiThread {
                    Toast.makeText(this@MemoryBrowserActivity, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }

        override fun onError(message: String) {
            Toast.makeText(this@MemoryBrowserActivity, message, Toast.LENGTH_LONG).show()
        }
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

    private fun tagsToText(tagsJson: String?): String = try {
        if (tagsJson.isNullOrBlank()) "" else {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).joinToString(", ") { arr.getString(it) }
        }
    } catch (_: Exception) { "" }

    private fun textToTagsJson(text: String): String {
        val arr = JSONArray()
        text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun existingHandling(protectionJson: String?): String = try {
        if (protectionJson.isNullOrBlank()) "" else {
            val arr = JSONObject(protectionJson).optJSONArray("handling") ?: JSONArray()
            (0 until arr.length()).joinToString("\n") { arr.getString(it) }
        }
    } catch (_: Exception) { "" }
}
