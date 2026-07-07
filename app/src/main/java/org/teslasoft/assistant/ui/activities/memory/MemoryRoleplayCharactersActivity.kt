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
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RoleplayCharacterRecord
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * "Roleplay Characters": user-played fictional characters (the Mage, the
 * Druid) — kept separate from My Personas so fiction never mixes with the
 * primary user's own presentation variants. Since Stage 3.6b each character
 * IS a two-zone card (roleplay_cards_and_tags_spec §6a): rows open
 * [CharacterCardActivity]; the pre-card description/arc fields are dormant
 * and never shown (spec §8a). Archived characters sit under the visible
 * Archive section at the bottom of the list (spec §5).
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
            it.name.lowercase().contains(q) ||
                it.species?.lowercase()?.contains(q) == true ||
                it.charClass?.lowercase()?.contains(q) == true
        }

        // Visible Archive section at the bottom (spec §5): active cards
        // first, then a header, then the archived ones.
        val active = filtered.filter { it.status != "archived" }.map { rowFor(it) }
        val archived = filtered.filter { it.status == "archived" }.map { rowFor(it) }
        if (archived.isEmpty()) return active
        return active +
            MemoryRow(id = "", title = getString(R.string.card_archive_header), isHeader = true) +
            archived
    }

    private fun rowFor(r: RoleplayCharacterRecord): MemoryRow {
        val subtitle = listOfNotNull(r.species, r.charClass)
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { null }
        return MemoryRow(
            id = r.roleplayCharacterId,
            title = r.name,
            subtitle = subtitle,
            badge = null,
            hasAction = true
        )
    }

    /* ------------------------------ toolbar ------------------------------ */

    override fun onAddClick() {
        if (!MemoryStore.isProvisioned(this)) {
            Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
            return
        }
        openCard(null)
    }

    /* ------------------------------ rows ------------------------------ */

    override fun onClick(row: MemoryRow) {
        if (row.isHeader) return
        openCard(row.id)
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
        menu.menu.add(0, 5, 0, getString(R.string.title_memories))
        menu.menu.add(0, 4, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openCard(r.roleplayCharacterId)
                2 -> setStatus(r.roleplayCharacterId, "archived")
                3 -> setStatus(r.roleplayCharacterId, "active")
                4 -> confirmDelete(r)
                5 -> openMemories(r)
            }
            true
        }
        menu.show()
    }

    /** Opens the single central memory browser pre-filtered to this roleplay
     *  character (the one browser, many doors). */
    private fun openMemories(r: RoleplayCharacterRecord) {
        startActivity(
            android.content.Intent(this, MemoryBrowserActivity::class.java)
                .putExtra("roleplayCharacterId", r.roleplayCharacterId)
                .putExtra("screenTitle", r.name)
                .putExtra("chatId", chatId)
        )
    }

    /* ------------------------------ card ------------------------------ */

    /** The full-screen two-zone card replaced the old edit dialog (spec §6a;
     *  full-screen over pop-up per the coding rules). */
    private fun openCard(roleplayCharacterId: String?) {
        startActivity(
            Intent(this, CharacterCardActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("cardType", CardType.RP_CHARACTER)
                .putExtra("cardId", roleplayCharacterId ?: "")
        )
    }

    /* ------------------------------ actions ------------------------------ */

    private fun setStatus(roleplayCharacterId: String, status: String) {
        runOffThread {
            MemoryStore.getInstance(this).setRoleplayCharacterStatus(roleplayCharacterId, status)
            runOnUiThread { reload() }
        }
    }

    /** Two-step delete, §5-aware (3.6f): a character linked to campaigns gets
     *  a warning NAMING them and an archive-instead option; confirming moves
     *  on to the per-deletion choice of whether the memories go too. */
    private fun confirmDelete(r: RoleplayCharacterRecord) {
        runOffThread {
            val linked = MemoryStore.getInstance(this).getCampaigns()
                .filter { it.roleplayCharacterId == r.roleplayCharacterId }.map { it.name }
            runOnUiThread {
                val message =
                    if (linked.isEmpty()) getString(R.string.mem_pers_delete_character_msg, r.name)
                    else getString(R.string.rp_delete_linked_msg, linked.joinToString(", "))
                val builder = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.mem_pers_delete_character_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.btn_delete) { _, _ -> showDeleteScopeDialog(r) }
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                if (linked.isNotEmpty()) {
                    builder.setNeutralButton(R.string.action_archive) { _, _ ->
                        setStatus(r.roleplayCharacterId, "archived")
                    }
                }
                builder.show()
            }
        }
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
