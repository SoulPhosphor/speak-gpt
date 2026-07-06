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

        return store.getCampaigns()
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .map { c ->
                val worldName = c.worldId?.let { worldNames[it] } ?: getString(R.string.mem_world_campaign_no_world)
                val subtitle = getString(R.string.mem_world_campaign_subtitle_fmt, statusLabel(c.status), worldName)
                MemoryRow(
                    id = c.campaignId,
                    title = c.name,
                    subtitle = subtitle,
                    badge = if (c.status != "active") statusLabel(c.status) else null,
                    hasAction = false
                )
            }
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
