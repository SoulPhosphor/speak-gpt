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
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The memory browser/editor: search (semantic when a model is installed, plus
 * a text scan), add, edit, protect/unprotect, archive/restore, delete
 * (user-only, Material confirm), and a per-memory change-log view. Every store
 * read/write runs off the main thread; failures degrade to a toast. This is
 * the single GLOBAL browser over all scopes and types; it can also be opened
 * pre-filtered to a companion / world / campaign / roleplay character (each
 * of those pages passes its id so the list and its "new memory" are confined
 * to that scope). A Companions link sits in the action bar.
 *
 * Filter surface (owner ruling, July 8 2026): the chip row is retired.
 * Instead the search bar carries a three-dots button that opens the "Memory
 * Filters" slide-out ([MemoryFilterPanelActivity]). All filter state lives in
 * [MemoryBrowserFilterState] so the panel edits it directly; the browser
 * reloads on resume and reflects the changes. Sort and Source are single-
 * value, everything else is multi-select. Status defaults to just "active".
 */
class MemoryBrowserActivity : MemoryScreenActivity() {

    /** Distinct tags across the loaded set, for the filter panel's Tags picker. */
    @Volatile private var availableTags: List<String> = emptyList()

    /** Draft count in the current (global or scoped) view, for the count line
     *  under the Memories | Pending toggle (owner design, July 8 2026
     *  evening — hidden when nothing is pending). */
    @Volatile private var pendingCount: Int = 0

    /** The two-mode view (owner design): "memories" = everything approved
     *  (non-draft; archived/superseded keep their badges), "pending" = drafts
     *  with the Accept / Delete / Edit (+ Add to Card) action words. Replaces
     *  the old Pending banner + separate Pending screen AND the filter
     *  panel's Status section (removed as a duplicate of this toggle). */
    private var mode: String = "memories"

    private var presetCompanionId: String? = null
    private var presetWorldId: String? = null
    private var presetCampaignId: String? = null
    private var presetRoleplayCharacterId: String? = null
    private var titleOverride: String? = null

    override fun screenTitle(): String = titleOverride ?: getString(R.string.title_memories)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.btn_new_memory)

    /** The old chip bar is retired; the slide-out panel is opened via the
     *  three-dots button beside the search field (owner ruling, July 8 2026). */
    override fun showFilterBar(): Boolean = false
    override fun showFilterButton(): Boolean = true

    override fun onFilterButtonClick() {
        val intent = Intent(this, MemoryFilterPanelActivity::class.java)
            .putExtra("chatId", chatId)
            .putExtra(MemoryFilterPanelActivity.EXTRA_AVAILABLE_TAGS, availableTags.toTypedArray())
        startActivity(intent)
        // Pair with the panel's slide-out on close so the transition matches.
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.anim_hold)
    }

    override fun showModeToggle(): Boolean = true

    override fun onModeSelected(mode: String) {
        if (this.mode == mode) return
        this.mode = mode
        // Keep the shared filter state coherent for the entry points that
        // read it (the Memory Assistant's View Pending Memories link writes
        // status={draft} to open this view pre-switched).
        MemoryBrowserFilterState.status.clear()
        MemoryBrowserFilterState.status.add(if (mode == "pending") "draft" else "active")
        reload()
    }

    // Count line under the toggle: "One Memory Pending" / "N Memories
    // Pending", hidden when nothing is pending (owner wording).
    override fun onRowsRendered() {
        val countLine = when {
            pendingCount <= 0 -> null
            pendingCount == 1 -> getString(R.string.mem_pending_count_one)
            else -> getString(R.string.mem_pending_count_many, pendingCount)
        }
        updateModeToggle(mode, countLine)
    }

    // Companions link (unscoped browser only).
    override fun secondaryActionIcon(): Int? =
        if (isScoped()) null else R.drawable.ic_user
    override fun secondaryActionLabel(): String? = getString(R.string.mem_comp_title)
    override fun onSecondaryActionClick() {
        startActivity(Intent(this, MemoryCompanionsActivity::class.java).putExtra("chatId", chatId))
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
        // An entry point that wrote status={draft} (the Memory Assistant's
        // View Pending Memories link) opens straight into Pending view.
        mode = if (MemoryBrowserFilterState.status == setOf("draft")) "pending" else "memories"
        super.onCreate(savedInstanceState)
    }

    /* ------------------------------ data ------------------------------ */

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)

        // Fetch the base set (all statuses), then apply the filters/sort in
        // memory — the browser is global over all scopes and types, and a
        // scoped door pre-narrows to one target while the filters still apply.
        val base: List<MemoryRecord> = when {
            presetCompanionId != null -> store.memoriesForCompanion(presetCompanionId!!, true)
            presetWorldId != null -> store.memoriesForWorld(presetWorldId!!, true)
            presetCampaignId != null -> store.memoriesForCampaign(presetCampaignId!!, true)
            presetRoleplayCharacterId != null ->
                store.memoriesForRoleplayCharacter(presetRoleplayCharacterId!!, true)
            else -> store.browseMemories(null, true, 1000)
        }
        availableTags = base.flatMap { parseTags(it.tagsJson) }.distinct().sortedBy { it.lowercase() }
        pendingCount = base.count { it.status == "draft" }

        val f = MemoryBrowserFilterState
        val q = query.trim().lowercase()
        var list = base
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) }
        if (!isScoped() && f.scope.isNotEmpty()) list = list.filter { it.scope in f.scope }
        if (f.type.isNotEmpty()) list = list.filter { it.kind in f.type }
        // The mode toggle IS the status split (owner design, July 8 2026
        // evening — the filter panel's Status section was removed as a
        // duplicate): Memories = everything approved (non-draft; archived and
        // superseded keep their row badges), Pending = drafts only.
        list = if (mode == "pending") list.filter { it.status == "draft" }
        else list.filter { it.status != "draft" }
        if (f.source != "all") list = list.filter { sourceKey(it.provenanceSource) == f.source }
        if (f.tags.isNotEmpty()) {
            val lowered = f.tags.map { it.lowercase() }.toSet()
            list = list.filter { parseTags(it.tagsJson).any { t -> t.lowercase() in lowered } }
        }
        list = if (f.sort == "oldest") list.sortedBy { it.createdAt } else list.sortedByDescending { it.createdAt }

        return list.map { rowFor(it) }
    }

    // Row layout: title / tags line / first line of content, with a leading
    // identity icon picked from the memory's scope (owner icon set, July 8
    // 2026). Badge is intentionally suppressed for "active" — the Active
    // pill added visual noise on the row that meant nothing (the browser
    // filters to Active by default).
    private fun rowFor(m: MemoryRecord): MemoryRow {
        val tags = parseTags(m.tagsJson)
        val firstLine = m.content.substringBefore('\n').trim()
        val tagsLine = if (tags.isEmpty()) null else formatTagsLine(tags)
        val pending = mode == "pending"
        // In Pending view every row is a draft, so the "Pending" badge would
        // repeat the mode — suppressed, same reasoning as the Active badge.
        val badge = if (m.status == "active" || pending) null else statusLabel(m.status)
        return MemoryRow(
            id = m.memoryId,
            title = m.title,
            subtitle = firstLine.ifEmpty { null },
            tagsLine = tagsLine,
            badge = badge,
            hasAction = !pending,
            iconRes = iconForScope(m.scope, isOnCard(m)),
            pendingActions = pending,
            // Owner design: roleplay memories additionally get Add to Card.
            showAddToCard = pending && m.scope in ROLEPLAY_SCOPES
        )
    }

    /**
     * Whether this memory has been placed on a roleplay card.
     *
     * RESERVED / always false today: "on a card" is Phase-6 territory — the
     * card-placement flow and the memory↔card link that would back it do not
     * exist yet (card_entries has no source-memory column, memories has no
     * on-card flag). When Phase 6 adds that link, this is the ONE place to
     * teach it, and the book_5 icon lights up automatically.
     */
    private fun isOnCard(@Suppress("UNUSED_PARAMETER") m: MemoryRecord): Boolean = false

    /**
     * Pick the leading identity icon (owner icon set, July 8 2026):
     *   on a card (any roleplay scope) → book_5   [Phase-6, see isOnCard]
     *   real_life                      → person
     *   global                         → borg (its OWN icon — global is a
     *                                    distinct scope from real life: it
     *                                    crosses into roleplay, §3)
     *   companion                      → partner (two people + a heart)
     *   project                        → draft (folded-corner page)
     *   rp_character (user's RP char)   → theater comedy mask
     *   world / campaign               → public globe (roleplay, not on a card)
     *   unknown / fallback              → public globe
     *
     * The user-roleplay-character slot will get its OWN icon later; it shares
     * the comedy mask for now, so keep the branch separate from world/campaign
     * (which are the globe) — the split is already here for that day.
     */
    private fun iconForScope(scope: String?, onCard: Boolean): Int = when {
        onCard -> R.drawable.ic_mem_book
        scope == "real_life" -> R.drawable.ic_mem_person
        scope == "global" -> R.drawable.ic_mem_global
        scope == "companion" -> R.drawable.ic_mem_companion
        scope == "project" -> R.drawable.ic_mem_draft
        scope == "rp_character" -> R.drawable.ic_mem_theater
        else -> R.drawable.ic_mem_public   // world, campaign, unknown
    }

    /**
     * Format the tag list as "Communication · Technical Help · Tone" (owner
     * ruling, July 8 2026): first letter of each tag capitalised, joined by
     * a middle dot with a space each side. No hashtags.
     */
    private fun formatTagsLine(tags: List<String>): String =
        tags.joinToString("  ·  ") { capitalise(it) }

    private fun capitalise(s: String): String {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w
            else w[0].uppercaseChar() + w.substring(1)
        }
    }

    /** "Learned from chat" once the Archivist exists; everything the user
     *  typed (including legacy rows with no provenance) reads as by hand. */
    private fun sourceKey(provenanceSource: String?): String =
        if (provenanceSource == null || provenanceSource == "user_entered" || provenanceSource == "user_stated") "hand" else "learned"

    private fun parseTags(tagsJson: String?): List<String> = try {
        if (tagsJson.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) { emptyList() }

    private fun statusLabel(key: String) = getString(
        when (key) {
            "draft" -> R.string.mem_filter_pending
            "archived" -> R.string.mem_status_archived
            "superseded" -> R.string.mem_status_superseded
            else -> R.string.mem_status_active
        }
    )

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

    // Protect/Unprotect is retired (owner ruling, July 8 2026): "protection"
    // was a handling concern, not a memory concept. Rule-like protections are
    // now ordinary Global memories; a sensitive fact keeps its care-note in
    // its own text (memories inject whole, so the note can't be sheared off).
    // The DB protection column stays dormant for backup/import compatibility.
    private fun showRowMenu(anchor: View, m: MemoryRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_edit))
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

    // The editor is a full-screen activity. A scoped browser pre-selects its
    // scope + target for a NEW memory; an existing memory carries its own
    // scope/targets, so no preset is sent. The browser reloads on resume, so
    // returning refreshes the list.
    private fun openEditor(memoryId: String?) {
        val intent = Intent(this, MemoryEditorActivity::class.java)
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

    /* -------------------- pending actions (owner design) -------------------- */

    // Accept → the draft becomes a regular active memory and leaves Pending.
    // Delete → the existing can't-be-undone confirm. Edit → the editor (which
    // shows Approve All As Shown for drafts). Add to Card → the link pop-up.
    override fun onPendingAction(row: MemoryRow, action: String) {
        when (action) {
            org.teslasoft.assistant.ui.adapters.memory.MemoryRowAdapter.ACTION_ACCEPT ->
                setStatus(row.id, "active")
            org.teslasoft.assistant.ui.adapters.memory.MemoryRowAdapter.ACTION_EDIT ->
                openEditor(row.id)
            org.teslasoft.assistant.ui.adapters.memory.MemoryRowAdapter.ACTION_DELETE ->
                runOffThread {
                    val m = MemoryStore.getInstance(this).getMemory(row.id) ?: return@runOffThread
                    runOnUiThread { confirmDelete(m) }
                }
            org.teslasoft.assistant.ui.adapters.memory.MemoryRowAdapter.ACTION_ADD_TO_CARD ->
                prepareAddToCard(row.id)
        }
    }

    /** One selectable lore card in the Add-to-Card dropdown. */
    private data class LoreCard(val cardType: String, val id: String, val name: String)

    private fun prepareAddToCard(memoryId: String) {
        runOffThread {
            val store = MemoryStore.getInstance(this)
            val memory = store.getMemory(memoryId) ?: return@runOffThread
            // Every live roleplay card is a valid destination; archived cards
            // aren't offered (their content is shelved).
            val cards = ArrayList<LoreCard>()
            store.getAllWorlds().filter { it.status == "active" }
                .forEach { cards.add(LoreCard(CardType.WORLD, it.worldId, it.name)) }
            store.getActiveCampaigns()
                .forEach { cards.add(LoreCard(CardType.CAMPAIGN, it.campaignId, it.name)) }
            store.getAllRoleplayCharacters().filter { it.status == "active" }
                .forEach { cards.add(LoreCard(CardType.RP_CHARACTER, it.roleplayCharacterId, it.name)) }
            store.getPartyMembers(includeArchived = false)
                .forEach { cards.add(LoreCard(CardType.PARTY_MEMBER, it.partyMemberId, it.name)) }
            runOnUiThread { showAddToCardDialog(memory, cards) }
        }
    }

    /** "Accept Memory and Link to Lore Card?" (owner wording) — the card and
     *  section dropdowns are boxed controls (5dp corners, box around the
     *  dropdown only). No Archivist placement suggestions exist yet; when the
     *  suggestion engine lands, it pre-selects both dropdowns here. */
    private fun showAddToCardDialog(memory: MemoryRecord, cards: List<LoreCard>) {
        val view = layoutInflater.inflate(R.layout.dialog_add_to_card, null)
        val dropCard = view.findViewById<android.widget.TextView>(R.id.dropdown_card)
        val dropSection = view.findViewById<android.widget.TextView>(R.id.dropdown_section)
        var pickedCard: LoreCard? = null
        var pickedSection: String? = null

        dropCard.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            cards.forEachIndexed { i, c -> menu.menu.add(0, i, i, c.name) }
            menu.setOnMenuItemClickListener { item ->
                pickedCard = cards[item.itemId]
                dropCard.text = pickedCard!!.name
                // Sections belong to the picked card's type; reset the choice.
                pickedSection = null
                dropSection.text = getString(R.string.mem_dropdown_select)
                true
            }
            menu.show()
        }
        dropSection.setOnClickListener { anchor ->
            val card = pickedCard ?: return@setOnClickListener
            val sections = CardSections.sectionsFor(card.cardType)
            val menu = PopupMenu(this, anchor)
            sections.forEachIndexed { i, s ->
                menu.menu.add(0, i, i, getString(CardEntryEditorActivity.sectionLabelRes(s)))
            }
            menu.setOnMenuItemClickListener { item ->
                pickedSection = sections[item.itemId]
                dropSection.text = getString(CardEntryEditorActivity.sectionLabelRes(pickedSection!!))
                true
            }
            menu.show()
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_add_card_title)
            .setView(view)
            .setPositiveButton(R.string.mem_pending_accept, null)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
        // Manual click handling so an incomplete pick doesn't dismiss.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val card = pickedCard ?: return@setOnClickListener
            val section = pickedSection ?: return@setOnClickListener
            dialog.dismiss()
            runOffThread {
                MemoryStore.getInstance(this)
                    .convertMemoryToCardEntry(memory.memoryId, card.cardType, card.id, section)
                runOnUiThread { reload() }
            }
        }
    }

    companion object {
        /** Scopes whose pending rows carry Add to Card (owner: "Roleplay
         *  Memories: Accept Delete Edit Add to Card"). */
        private val ROLEPLAY_SCOPES = setOf("world", "campaign", "rp_character")
    }
}
