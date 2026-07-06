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

        return store.getAllWorlds()
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.premise.lowercase().contains(q) }
            .map { w ->
                val badge = when {
                    w.status == "ended" -> getString(R.string.mem_world_badge_ended)
                    w.status != "active" -> w.status
                    else -> null
                }
                MemoryRow(
                    id = w.worldId,
                    title = w.name,
                    subtitle = w.premise.trim().ifBlank { null },
                    badge = badge,
                    hasAction = false
                )
            }
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
