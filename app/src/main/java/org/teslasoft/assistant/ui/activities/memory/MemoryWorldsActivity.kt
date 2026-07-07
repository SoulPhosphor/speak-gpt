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
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * Phase 5 "Worlds" list (app_adaptation_notes §Worlds UI): one flat, searchable
 * list of every world. A world page is editable premise/rules + its characters
 * + a world-scoped memory browser — there are deliberately NO structured
 * worldbuilding fields (cities/cultures are just world-scoped memories in
 * prose), so this list is intentionally plain. Reuses [MemoryScreenActivity]'s
 * theme/insets/search/off-thread scaffold; a tap opens [WorldDetailActivity],
 * the FAB opens it with no worldId (a new world).
 */
class MemoryWorldsActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_world_title)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.mem_world_add)

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val q = query.trim().lowercase()

        val all = store.getAllWorlds()
            // Search + subtitle use the card's Premise/Vibe — the dormant
            // pre-card premise text is never shown (spec §8a).
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.premiseVibe?.lowercase()?.contains(q) == true }

        fun rowFor(w: org.teslasoft.assistant.preferences.memory.WorldRecord): MemoryRow {
            val badge = when {
                w.status == "ended" -> getString(R.string.mem_world_badge_ended)
                w.status != "active" && w.status != "archived" -> w.status
                else -> null
            }
            return MemoryRow(
                id = w.worldId,
                title = w.name,
                subtitle = w.premiseVibe?.lineSequence()?.firstOrNull()?.trim()?.ifBlank { null },
                badge = badge,
                hasAction = w.status == "archived"
            )
        }

        // Visible Archive section at the bottom (spec §5, 3.6f): archived
        // worlds stay evident and restore in one tap. Legacy dormant/ended
        // states keep their badges in the main list.
        val active = all.filter { it.status != "archived" }.map { rowFor(it) }
        val archived = all.filter { it.status == "archived" }.map { rowFor(it) }
        if (archived.isEmpty()) return active
        return active +
            MemoryRow(id = "", title = getString(R.string.card_archive_header), isHeader = true) +
            archived
    }

    override fun onAction(row: MemoryRow, anchor: android.view.View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.action_restore))
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                runOffThread {
                    MemoryStore.getInstance(this).restoreWorld(row.id)
                    runOnUiThread { reload() }
                }
            }
            true
        }
        menu.show()
    }

    override fun onClick(row: MemoryRow) {
        startActivity(
            Intent(this, WorldDetailActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("worldId", row.id)
        )
    }

    override fun onAddClick() {
        startActivity(
            Intent(this, WorldDetailActivity::class.java)
                .putExtra("chatId", chatId)
        )
    }
}
