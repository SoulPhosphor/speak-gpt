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
import org.teslasoft.assistant.preferences.memory.CompanionRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * Phase 5 "Companions" area of the memory manager (app_adaptation_notes
 * §Characters area). Companions are the store's continuity file for each of the
 * app's personas — they are NOT created here, so there is no "add" button
 * (the base default hides the FAB): the persona -> companion bootstrap makes
 * them. This screen lists them, badges drafts, and opens the per-companion
 * detail/edit page. All store reads run off the main thread via the base
 * scaffold; failures degrade to a toast.
 */
class MemoryCompanionsActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_comp_title)
    override fun showSearch(): Boolean = true
    // No add button: companions are created automatically from personas.

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)

        val q = query.trim().lowercase()
        val companions = store.getCompanions().filter {
            q.isEmpty() ||
                it.currentName.lowercase().contains(q) ||
                it.essence.lowercase().contains(q)
        }
        return companions.map { rowFor(it) }
    }

    private fun rowFor(c: CompanionRecord): MemoryRow {
        // Essence is no longer surfaced on the companion page (owner decision
        // July 2026); the row shows only the participation label.
        val subtitle = participationLabel(c.memoryParticipation)
        val badge = when {
            c.status == "draft" -> getString(R.string.mem_comp_badge_draft)
            c.status != "active" -> c.status
            else -> null
        }
        return MemoryRow(
            id = c.companionId,
            title = c.currentName,
            subtitle = subtitle,
            badge = badge,
            hasAction = false
        )
    }

    private fun participationLabel(participation: String): String = when (participation) {
        "global_only" -> getString(R.string.mem_comp_participation_global_only)
        "none" -> getString(R.string.mem_comp_participation_none)
        else -> getString(R.string.mem_comp_participation_full)
    }

    override fun onClick(row: MemoryRow) {
        startActivity(
            Intent(this, CompanionDetailActivity::class.java)
                .putExtra("companionId", row.id)
                .putExtra("chatId", chatId)
        )
    }
}
