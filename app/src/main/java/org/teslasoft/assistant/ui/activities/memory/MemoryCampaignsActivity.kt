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
 * Phase 5 "Campaigns" list (integration plan 📌 amendment): the roleplay-
 * continuity layer. A campaign is one playthrough inside a world, keeping its
 * game-state facts from bleeding into other campaigns in the same world. A flat,
 * searchable list; a tap opens [CampaignDetailActivity], the FAB opens it for a
 * new campaign. Reuses [MemoryScreenActivity]'s off-thread/toast scaffold.
 */
class MemoryCampaignsActivity : MemoryScreenActivity() {

    override fun screenTitle(): String = getString(R.string.mem_world_campaigns_title)
    override fun showSearch(): Boolean = true
    override fun addButtonText(): String = getString(R.string.mem_world_campaign_add)

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        val q = query.trim().lowercase()

        // Cache world names for the subtitle without re-querying per row.
        val worldNames = store.getAllWorlds().associate { it.worldId to it.name }

        val all = store.getCampaigns().filter { q.isEmpty() || it.name.lowercase().contains(q) }

        fun rowFor(c: org.teslasoft.assistant.preferences.memory.CampaignRecord): MemoryRow {
            val worldName = c.worldId?.let { worldNames[it] } ?: getString(R.string.mem_world_campaign_no_world)
            val subtitle = getString(R.string.mem_world_campaign_subtitle_fmt, statusLabel(c.status), worldName)
            return MemoryRow(
                id = c.campaignId,
                title = c.name,
                subtitle = subtitle,
                badge = if (c.status != "active" && c.status != "archived") statusLabel(c.status) else null,
                hasAction = c.status == "archived"
            )
        }

        // Visible Archive section at the bottom (spec §5, 3.6f): archived
        // campaigns are hidden from active selection but stay evident and
        // restorable in one tap (the row action).
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
                    MemoryStore.getInstance(this).setCampaignStatus(row.id, "active")
                    runOnUiThread { reload() }
                }
            }
            true
        }
        menu.show()
    }

    private fun statusLabel(status: String): String = when (status) {
        "active" -> getString(R.string.mem_world_campaign_status_active)
        "paused" -> getString(R.string.mem_world_campaign_status_paused)
        "ended" -> getString(R.string.mem_world_campaign_status_ended)
        "archived" -> getString(R.string.mem_world_campaign_status_archived)
        else -> status
    }

    override fun onClick(row: MemoryRow) {
        startActivity(
            Intent(this, CampaignDetailActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("campaignId", row.id)
        )
    }

    override fun onAddClick() {
        startActivity(
            Intent(this, CampaignDetailActivity::class.java)
                .putExtra("chatId", chatId)
        )
    }
}
