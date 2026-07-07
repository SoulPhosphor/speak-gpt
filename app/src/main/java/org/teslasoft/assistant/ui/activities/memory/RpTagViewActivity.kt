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
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RpTagTargetType
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The cross-card tag view (3.6e, roleplay_cards_and_tags_spec §3): every
 * entry sharing the tapped tag, grouped by the PREDEFINED card types and
 * sections from §6 (system-defined categories, never user-configured), plus
 * whole-card links and — via the tag bridge — roleplay-scoped MEMORIES
 * sharing the tag. Pure lookup, no AI; the browse view is GLOBAL across the
 * roleplay module even though firing is campaign-scoped. Rows open the
 * thing itself: the entry editor, the card, or the memory editor. Anything
 * pointing at a record that no longer resolves is simply absent here —
 * the "(deleted card)" rendering belongs to the referencing cards (3.6f).
 */
class RpTagViewActivity : MemoryScreenActivity() {

    private var tagId: String = ""
    private var tagName: String = ""

    /** Row-id routing caches, rebuilt on every load (worker thread). */
    private val entryById = HashMap<String, CardEntryRecord>()

    override fun screenTitle(): String =
        intent.extras?.getString("tagName", "")?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.rp_tags_title)

    override fun showSearch(): Boolean = false

    override fun loadRows(query: String): List<MemoryRow> {
        tagId = intent.extras?.getString("tagId", "") ?: ""
        tagName = intent.extras?.getString("tagName", "") ?: ""
        if (tagId.isEmpty() || !MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)

        val targets = store.targetsForTag(tagId)
        entryById.clear()

        // Card entries, bucketed by the predefined (card type, section) pairs.
        val entryTargets = targets.filter { it.first == RpTagTargetType.CARD_ENTRY }
        for ((_, id) in entryTargets) {
            store.getCardEntry(id)?.let { entryById[it.entryId] = it }
        }

        fun cardName(type: String, id: String): String? = when (type) {
            CardType.WORLD -> store.getWorld(id)?.name
            CardType.CAMPAIGN -> store.getCampaign(id)?.name
            CardType.RP_CHARACTER -> store.getRoleplayCharacter(id)?.name
            CardType.PARTY_MEMBER -> store.getPartyMember(id)?.name
            else -> null
        }

        fun cardTypeLabel(type: String): String = getString(
            when (type) {
                CardType.WORLD -> R.string.mem_world_detail_title
                CardType.CAMPAIGN -> R.string.mem_world_campaign_detail_title
                CardType.PARTY_MEMBER -> R.string.card_title_party_member
                else -> R.string.card_title_character
            }
        )

        val rows = ArrayList<MemoryRow>()

        // Fixed, system-defined group order: card types in spec order, each
        // type's sections in its §6 order.
        val typeOrder = listOf(CardType.RP_CHARACTER, CardType.PARTY_MEMBER, CardType.WORLD, CardType.CAMPAIGN)
        for (type in typeOrder) {
            // Every card carries the owner-added Notes section (spec §8a);
            // distinct() because the campaign's section list already has it.
            for (section in (CardSections.sectionsFor(type) + CardSections.NOTES).distinct()) {
                val inBucket = entryById.values
                    .filter { it.cardType == type && it.section == section }
                    .sortedBy { it.name.lowercase() }
                if (inBucket.isEmpty()) continue
                rows.add(
                    MemoryRow(
                        id = "",
                        title = getString(
                            R.string.rp_tag_group_fmt,
                            cardTypeLabel(type),
                            getString(CardEntryEditorActivity.sectionLabelRes(section))
                        ),
                        isHeader = true
                    )
                )
                for (e in inBucket) {
                    rows.add(
                        MemoryRow(
                            id = "entry:${e.entryId}",
                            title = e.name,
                            subtitle = cardName(e.cardType, e.cardId)
                        )
                    )
                }
            }
        }

        // Whole cards carrying the tag.
        val cardTargets = targets.filter { it.first in RpTagTargetType.ALL && it.first != RpTagTargetType.CARD_ENTRY && it.first != RpTagTargetType.MEMORY }
        val cardRows = cardTargets.mapNotNull { (type, id) ->
            cardName(type, id)?.let { name ->
                MemoryRow(id = "card:$type:$id", title = name, subtitle = cardTypeLabel(type))
            }
        }.sortedBy { it.title.lowercase() }
        if (cardRows.isNotEmpty()) {
            rows.add(MemoryRow(id = "", title = getString(R.string.rp_tag_group_cards), isHeader = true))
            rows.addAll(cardRows)
        }

        // The tag bridge (§3): roleplay-scoped memories sharing the tag —
        // linked rows plus the name-matched read-side bridge, deduped. The
        // realm wall holds: real-life memories never appear here.
        val memoryRows = LinkedHashMap<String, MemoryRow>()
        for ((_, id) in targets.filter { it.first == RpTagTargetType.MEMORY }) {
            store.getMemory(id)?.let { m ->
                memoryRows[m.memoryId] = MemoryRow(id = "mem:${m.memoryId}", title = m.title)
            }
        }
        if (tagName.isNotEmpty()) {
            for (m in store.roleplayMemoriesWithTag(tagName)) {
                memoryRows.getOrPut(m.memoryId) { MemoryRow(id = "mem:${m.memoryId}", title = m.title) }
            }
        }
        if (memoryRows.isNotEmpty()) {
            rows.add(MemoryRow(id = "", title = getString(R.string.title_memories), isHeader = true))
            rows.addAll(memoryRows.values.sortedBy { it.title.lowercase() })
        }

        return rows
    }

    override fun onClick(row: MemoryRow) {
        if (row.isHeader) return
        val id = row.id
        when {
            id.startsWith("entry:") -> {
                val e = entryById[id.removePrefix("entry:")] ?: return
                startActivity(
                    Intent(this, CardEntryEditorActivity::class.java)
                        .putExtra("chatId", chatId)
                        .putExtra("cardType", e.cardType)
                        .putExtra("cardId", e.cardId)
                        .putExtra("section", e.section)
                        .putExtra("entryId", e.entryId)
                )
            }
            id.startsWith("card:") -> {
                val parts = id.split(":", limit = 3)
                if (parts.size < 3) return
                val (_, type, cardId) = parts
                val intent = when (type) {
                    CardType.WORLD -> Intent(this, WorldDetailActivity::class.java).putExtra("worldId", cardId)
                    CardType.CAMPAIGN -> Intent(this, CampaignDetailActivity::class.java).putExtra("campaignId", cardId)
                    else -> Intent(this, CharacterCardActivity::class.java)
                        .putExtra("cardType", type)
                        .putExtra("cardId", cardId)
                }
                startActivity(intent.putExtra("chatId", chatId))
            }
            id.startsWith("mem:") -> {
                startActivity(
                    Intent(this, MemoryEditorActivity::class.java)
                        .putExtra("chatId", chatId)
                        .putExtra("memoryId", id.removePrefix("mem:"))
                )
            }
        }
    }
}
