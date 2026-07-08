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

    /** Draft count in the current (global or scoped) view, for the Pending
     *  banner (§2.4). */
    @Volatile private var pendingCount: Int = 0

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

    // Pending banner (§2.4).
    override fun onRowsRendered() {
        if (pendingCount > 0) {
            setPendingBanner(getString(R.string.mem_pending_banner, pendingCount)) { openPending() }
        } else {
            setPendingBanner(null, null)
        }
    }

    private fun openPending() {
        startActivity(
            Intent(this, MemoryPendingActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("companionId", presetCompanionId)
                .putExtra("worldId", presetWorldId)
                .putExtra("campaignId", presetCampaignId)
                .putExtra("roleplayCharacterId", presetRoleplayCharacterId)
        )
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
        if (f.status.isNotEmpty()) list = list.filter { it.status in f.status }
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
        val badge = if (m.status == "active") null else statusLabel(m.status)
        return MemoryRow(
            id = m.memoryId,
            title = m.title,
            subtitle = firstLine.ifEmpty { null },
            tagsLine = tagsLine,
            badge = badge,
            hasAction = true,
            iconRes = iconForScope(m.scope, isOnCard(m))
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
     *   companion                      → partner (two people + a heart)
     *   project                        → draft (folded-corner page)
     *   rp_character (user's RP char)   → theater comedy mask
     *   world / campaign               → public globe (roleplay, not on a card)
     *   global (and any unknown)        → public globe
     *
     * The user-roleplay-character slot will get its OWN icon later; it shares
     * the comedy mask for now, so keep the branch separate from world/campaign
     * (which are the globe) — the split is already here for that day.
     */
    private fun iconForScope(scope: String?, onCard: Boolean): Int = when {
        onCard -> R.drawable.ic_mem_book
        scope == "real_life" -> R.drawable.ic_mem_person
        scope == "companion" -> R.drawable.ic_mem_companion
        scope == "project" -> R.drawable.ic_mem_draft
        scope == "rp_character" -> R.drawable.ic_mem_theater
        else -> R.drawable.ic_mem_public   // world, campaign, global, unknown
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

    /** Protect: capture the HANDLE WITH CARE handling lines (one per line). */
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
