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
 * The hub of the Phase 5 memory manager: a plain list of every area the user
 * can browse and edit by hand (memories, companions, personas, roleplay
 * characters, worlds, campaigns, entities, modes, directives, owner profile).
 * Reuses the shared list scaffold with search and the add-FAB off — each row
 * just opens its area, forwarding the chat id so scoped screens can read
 * per-chat context.
 */
class MemoryManagerActivity : MemoryScreenActivity() {

    private data class Area(val target: Class<*>, val titleRes: Int, val descRes: Int)

    private val areas by lazy {
        listOf(
            Area(MemoryBrowserActivity::class.java, R.string.title_memories, R.string.mm_memories_desc),
            Area(MemoryCompanionsActivity::class.java, R.string.mem_comp_title, R.string.mm_companions_desc),
            Area(MemoryUserPersonasActivity::class.java, R.string.mem_pers_title_user_personas, R.string.mm_user_personas_desc),
            Area(MemoryRoleplayCharactersActivity::class.java, R.string.mem_pers_title_roleplay_characters, R.string.mm_roleplay_desc),
            Area(MemoryWorldsActivity::class.java, R.string.mem_world_title, R.string.mm_worlds_desc),
            Area(MemoryCampaignsActivity::class.java, R.string.mem_world_campaigns_title, R.string.mm_campaigns_desc),
            Area(MemoryEntitiesActivity::class.java, R.string.mem_admin_title_entities, R.string.mm_entities_desc),
            Area(MemoryModesActivity::class.java, R.string.mem_admin_title_modes, R.string.mm_modes_desc),
            Area(MemoryDirectivesActivity::class.java, R.string.mem_admin_title_directives, R.string.mm_directives_desc),
            Area(OwnerProfileActivity::class.java, R.string.mem_admin_title_owner_profile, R.string.mm_owner_desc)
        )
    }

    override fun screenTitle(): String = getString(R.string.title_memory_manager)
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
