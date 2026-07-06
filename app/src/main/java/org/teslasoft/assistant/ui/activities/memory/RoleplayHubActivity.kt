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
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The Roleplay hub: reached from the "Roleplay" card on the main Settings
 * page. Groups the three roleplay-continuity areas — Worlds, Campaigns and
 * Roleplay Characters — each opening its own editor. Kept separate from the
 * Memory manager so fiction/game state never mixes with the companion memory
 * surfaces.
 */
class RoleplayHubActivity : MemoryScreenActivity() {

    private data class Area(val target: Class<*>, val titleRes: Int, val descRes: Int)

    private val areas by lazy {
        listOf(
            Area(MemoryWorldsActivity::class.java, R.string.mem_world_title, R.string.mm_worlds_desc),
            Area(MemoryCampaignsActivity::class.java, R.string.mem_world_campaigns_title, R.string.mm_campaigns_desc),
            Area(MemoryRoleplayCharactersActivity::class.java, R.string.mem_pers_title_roleplay_characters, R.string.mm_roleplay_desc)
        )
    }

    override fun screenTitle(): String = getString(R.string.title_roleplay)
    override fun showSearch(): Boolean = false

    override fun loadRows(query: String): List<MemoryRow> =
        areas.mapIndexed { i, a ->
            MemoryRow(id = i.toString(), title = getString(a.titleRes), subtitle = getString(a.descRes), hasAction = false)
        }

    override fun onClick(row: MemoryRow) {
        val area = areas.getOrNull(row.id.toIntOrNull() ?: return) ?: return
        startActivity(Intent(this, area.target).putExtra("chatId", chatId))
    }
}
