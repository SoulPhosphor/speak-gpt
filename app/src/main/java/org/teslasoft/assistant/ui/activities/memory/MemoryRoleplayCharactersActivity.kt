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
import android.widget.PopupMenu
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RoleplayCharacterRecord
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow
import org.teslasoft.assistant.ui.fragments.dialogs.EditRoleplayCharacterDialogFragment

/**
 * "Roleplay Characters" (Phase 5, app_adaptation_notes.md §Tab structure):
 * user-played fictional characters (the Mage, the Druid) — kept separate from
 * My Personas so fiction never mixes with the primary user's own presentation
 * variants. Each card shows the **definition** (name/description/played-by,
 * user-editable) and the **arc** (story-so-far, Archivist-maintained,
 * read-only — memory-system-integration-plan.md 📌 campaign amendment).
 * Characters the Archivist registers retroactively (emergence) appear here
 * exactly the same as user-created ones.
 */
class MemoryRoleplayCharactersActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_pers_title_roleplay_characters)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.mem_pers_fab_add_character)

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val records = store.getAllRoleplayCharacters()

        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) records else records.filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }

        return filtered.map { rowFor(it) }
    }

    private fun rowFor(r: RoleplayCharacterRecord): MemoryRow {
        val badge = if (r.status == "archived") getString(R.string.memory_badge_archived) else null
        return MemoryRow(
            id = r.roleplayCharacterId,
            title = r.name,
            subtitle = r.description.trim(),
            badge = badge,
            hasAction = true
        )
    }

    /* ------------------------------ toolbar ------------------------------ */

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
            val r = store.getRoleplayCharacter(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, r) }
        }
    }

    private fun showRowMenu(anchor: View, r: RoleplayCharacterRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        if (r.status == "active") {
            menu.menu.add(0, 2, 0, getString(R.string.action_archive))
        } else {
            menu.menu.add(0, 3, 0, getString(R.string.action_restore))
        }
        menu.menu.add(0, 4, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(r.roleplayCharacterId)
                2 -> setStatus(r.roleplayCharacterId, "archived")
                3 -> setStatus(r.roleplayCharacterId, "active")
                4 -> confirmDelete(r)
            }
            true
        }
        menu.show()
    }

    /* ------------------------------ editor ------------------------------ */

    private fun openEditor(roleplayCharacterId: String?) {
        runOffThread {
            val existing = roleplayCharacterId?.let { MemoryStore.getInstance(this).getRoleplayCharacter(it) }
            runOnUiThread {
                val dialog = EditRoleplayCharacterDialogFragment.newInstance(
                    roleplayCharacterId = existing?.roleplayCharacterId ?: "",
                    name = existing?.name ?: "",
                    description = existing?.description ?: "",
                    playedBy = existing?.playedBy ?: "user",
                    arc = existing?.arc
                )
                dialog.setListener(editorListener)
                dialog.show(supportFragmentManager, "EditRoleplayCharacterDialogFragment")
            }
        }
    }

    private val editorListener = object : EditRoleplayCharacterDialogFragment.Listener {
        override fun onSave(roleplayCharacterId: String, name: String, description: String, playedBy: String) {
            runOffThread {
                val store = MemoryStore.getInstance(this@MemoryRoleplayCharactersActivity)
                val prior = if (roleplayCharacterId.isNotEmpty()) store.getRoleplayCharacter(roleplayCharacterId) else null
                val record = RoleplayCharacterRecord(
                    roleplayCharacterId = prior?.roleplayCharacterId ?: MemoryStore.newId("rc-"),
                    name = name,
                    playedBy = playedBy,
                    description = description,
                    // The arc is Archivist-maintained; this editor never touches it.
                    arc = prior?.arc,
                    worldsPlayedJson = prior?.worldsPlayedJson ?: "[]",
                    status = prior?.status ?: "active",
                    createdAt = prior?.createdAt ?: MemoryStore.nowIso()
                )
                store.upsertRoleplayCharacter(record)
                runOnUiThread {
                    Toast.makeText(this@MemoryRoleplayCharactersActivity, R.string.mem_pers_character_saved, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }

        override fun onError(message: String) {
            Toast.makeText(this@MemoryRoleplayCharactersActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /* ------------------------------ actions ------------------------------ */

    private fun setStatus(roleplayCharacterId: String, status: String) {
        runOffThread {
            MemoryStore.getInstance(this).setRoleplayCharacterStatus(roleplayCharacterId, status)
            runOnUiThread { reload() }
        }
    }

    /** Two-step delete: first a plain confirm, then — only if the user
     *  confirms — a second choice for whether the character's memories go
     *  with it (kept as unlinked global memories, or removed together). */
    private fun confirmDelete(r: RoleplayCharacterRecord) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_pers_delete_character_title)
            .setMessage(getString(R.string.mem_pers_delete_character_msg, r.name))
            .setPositiveButton(R.string.btn_delete) { _, _ -> showDeleteScopeDialog(r) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun showDeleteScopeDialog(r: RoleplayCharacterRecord) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_pers_delete_character_scope_title)
            .setMessage(getString(R.string.mem_pers_delete_character_scope_msg, r.name))
            .setPositiveButton(R.string.mem_pers_delete_character_with_memories) { _, _ ->
                deleteCharacter(r.roleplayCharacterId, deleteMemories = true)
            }
            .setNegativeButton(R.string.mem_pers_delete_character_keep_memories) { _, _ ->
                deleteCharacter(r.roleplayCharacterId, deleteMemories = false)
            }
            .show()
    }

    private fun deleteCharacter(roleplayCharacterId: String, deleteMemories: Boolean) {
        runOffThread {
            MemoryStore.getInstance(this).deleteRoleplayCharacter(roleplayCharacterId, deleteMemories)
            runOnUiThread {
                Toast.makeText(this, R.string.mem_pers_character_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }
}
