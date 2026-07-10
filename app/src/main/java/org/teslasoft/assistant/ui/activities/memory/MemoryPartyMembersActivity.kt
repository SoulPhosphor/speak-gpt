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
import org.teslasoft.assistant.preferences.memory.PartyMemberRecord
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The top-level party-member roster (roleplay_cards_and_tags_spec §4): one
 * card per NPC, held here — campaigns LINK members from this roster (join,
 * not ownership), so an NPC travels between campaigns without rebuild. Rows
 * open the shared two-zone character card in party-member mode. The fiction
 * status shows as a badge when it isn't Alive; the card-lifecycle Archive
 * section sits at the bottom (spec §5). Death is a STATUS, never a delete —
 * the status control lives on the card. (The delete-while-linked warning
 * flow is 3.6f; delete here is a plain confirm until that slice.)
 */
class MemoryPartyMembersActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_party_title)
    override fun showSearch(): Boolean = false
    override fun addButtonText(): String = getString(R.string.mem_party_fab_add)

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val records = MemoryStore.getInstance(this).getPartyMembers(includeArchived = true)

        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) records else records.filter {
            it.name.lowercase().contains(q) ||
                it.species?.lowercase()?.contains(q) == true ||
                it.charClass?.lowercase()?.contains(q) == true
        }

        // Visible Archive section at the bottom (spec §5).
        val active = filtered.filter { !it.archived }.map { rowFor(it) }
        val archived = filtered.filter { it.archived }.map { rowFor(it) }
        if (archived.isEmpty()) return active
        return active +
            MemoryRow(id = "", title = getString(R.string.card_archive_header), isHeader = true) +
            archived
    }

    private fun rowFor(p: PartyMemberRecord): MemoryRow {
        val subtitle = listOfNotNull(p.species, p.charClass)
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { null }
        // The fiction status is worth a badge only when it isn't the default —
        // Dead/Enemy/Incapacitated change how the narrator treats the NPC.
        val badge = if (p.status == "alive") null
        else getString(CharacterCardActivity.statusLabelRes(p.status))
        return MemoryRow(
            id = p.partyMemberId,
            title = p.name,
            subtitle = subtitle,
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
        openCard(null)
    }

    /* ------------------------------ rows ------------------------------ */

    override fun onClick(row: MemoryRow) {
        if (row.isHeader) return
        openCard(row.id)
    }

    override fun onAction(row: MemoryRow, anchor: View) {
        runOffThread {
            val record = MemoryStore.getInstance(this).getPartyMember(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, record) }
        }
    }

    private fun showRowMenu(anchor: View, p: PartyMemberRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
        if (p.archived) {
            menu.menu.add(0, 3, 0, getString(R.string.action_restore))
        } else {
            menu.menu.add(0, 2, 0, getString(R.string.action_archive))
        }
        menu.menu.add(0, 4, 0, getString(R.string.action_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openCard(p.partyMemberId)
                2 -> setArchived(p.partyMemberId, true)
                3 -> setArchived(p.partyMemberId, false)
                4 -> confirmDelete(p)
            }
            true
        }
        menu.show()
    }

    private fun openCard(partyMemberId: String?) {
        startActivity(
            Intent(this, CharacterCardActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("cardType", CardType.PARTY_MEMBER)
                .putExtra("cardId", partyMemberId ?: "")
        )
    }

    /* ------------------------------ actions ------------------------------ */

    private fun setArchived(partyMemberId: String, archived: Boolean) {
        runOffThread {
            MemoryStore.getInstance(this).setPartyMemberArchived(partyMemberId, archived)
            runOnUiThread { reload() }
        }
    }

    /** §5 (3.6f): an NPC linked to campaigns gets a warning NAMING them and
     *  an archive-instead option. Party members aren't a memory scope, so
     *  there is no memories question here — and death is a STATUS on the
     *  card, never a reason to delete. */
    private fun confirmDelete(p: PartyMemberRecord) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val campaignNames = store.getCampaigns().associate { it.campaignId to it.name }
            val linked = store.campaignIdsForPartyMember(p.partyMemberId)
                .mapNotNull { campaignNames[it] }
            runOnUiThread {
                val message =
                    if (linked.isEmpty()) getString(R.string.mem_party_delete_msg, p.name)
                    else getString(R.string.rp_delete_linked_msg, linked.joinToString(", "))
                val builder = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.mem_party_delete_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.btn_delete) { _, _ ->
                        runOffThread {
                            MemoryStore.getInstance(this).deletePartyMember(p.partyMemberId)
                            runOnUiThread {
                                Toast.makeText(this, R.string.mem_party_deleted, Toast.LENGTH_SHORT).show()
                                reload()
                            }
                        }
                    }
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                if (linked.isNotEmpty()) {
                    builder.setNeutralButton(R.string.action_archive) { _, _ ->
                        setArchived(p.partyMemberId, true)
                    }
                }
                builder.show()
            }
        }
    }
}
