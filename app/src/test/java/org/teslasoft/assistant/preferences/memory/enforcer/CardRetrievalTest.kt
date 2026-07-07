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

package org.teslasoft.assistant.preferences.memory.enforcer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.CardEntryRecord
import org.teslasoft.assistant.preferences.memory.CardSections
import org.teslasoft.assistant.preferences.memory.CardType
import org.teslasoft.assistant.preferences.memory.RpTagRecord

/**
 * Zone 2 card retrieval is trigger-matched, never embedded
 * (roleplay_cards_and_tags_spec §1/§3/§10): these tests pin the firing
 * rules — names fire, section names fire the whole section, auto-trigger
 * tags fire, browse-only tags DON'T fire from message text but still count
 * for one-hop pull-along, and geography renders its full parent chain.
 */
class CardRetrievalTest {

    private fun entry(
        id: String,
        section: String,
        name: String,
        cardType: String = CardType.WORLD,
        parent: String? = null,
        description: String? = null,
        quantity: Int? = null,
        kind: String? = null
    ) = CardEntryRecord(
        entryId = id, cardType = cardType, cardId = "card-1", section = section,
        name = name, description = description, entryKind = kind, quantity = quantity,
        parentEntryId = parent, createdAt = "2026-07-07T00:00:00Z"
    )

    @Test
    fun entryNameFires() {
        val e = entry("e1", CardSections.SETTLEMENTS, "Eldoria")
        val result = CardRetrieval.fire("tell me about Eldoria", listOf(e), emptyMap(), emptyMap())
        assertEquals(listOf("e1"), result.direct.map { it.entry.entryId })
        assertEquals("name", result.direct.first().reason)
    }

    @Test
    fun sectionNameFiresTheWholeSectionButGroupHeadersNever() {
        val a = entry("e1", CardSections.SETTLEMENTS, "Eldoria")
        val b = entry("e2", CardSections.SETTLEMENTS, "Port Vane")
        val c = entry("e3", CardSections.REGIONS, "Verdant Kingdom")
        // "settlements" fires that whole section (owner's own example: "what
        // cities are there?" -> Settlements); the GROUP header "geography" is
        // not a trigger word.
        val bySection = CardRetrieval.fire("what settlements are there?", listOf(a, b, c), emptyMap(), emptyMap())
        assertEquals(setOf("e1", "e2"), bySection.direct.map { it.entry.entryId }.toSet())
        val byGroup = CardRetrieval.fire("tell me about the geography", listOf(a, b, c), emptyMap(), emptyMap())
        assertTrue(byGroup.direct.isEmpty())
    }

    @Test
    fun autoTriggerTagFiresAndBrowseOnlyDoesNot() {
        val tagged = entry("e1", CardSections.HISTORICAL_EVENTS, "The Gray Rot Outbreak")
        val tags = mapOf(
            "t1" to RpTagRecord("t1", "gray rot", autoTrigger = true),
            "t2" to RpTagRecord("t2", "magic", autoTrigger = false)
        )
        val entryTags = mapOf("e1" to listOf("t1", "t2"))

        val fired = CardRetrieval.fire("what regions does the gray rot affect?", listOf(tagged), tags, entryTags)
        assertEquals(listOf("e1"), fired.direct.map { it.entry.entryId })
        assertTrue(fired.direct.first().reason.contains("gray rot"))

        // The browse-only switch silences ONLY message-text matching (§3).
        val silenced = CardRetrieval.fire("tell me about magic", listOf(tagged), tags, entryTags)
        assertTrue(silenced.direct.isEmpty())
    }

    @Test
    fun browseOnlyTagsStillPullAlong() {
        val fired = entry("e1", CardSections.SACRED_ARTIFACTS, "The Silver Key")
        val sibling = entry("e2", CardSections.POINTS_OF_INTEREST, "Eclipse Gate")
        val tags = mapOf("t1" to RpTagRecord("t1", "eclipse-things", autoTrigger = false))
        val entryTags = mapOf("e1" to listOf("t1"), "e2" to listOf("t1"))

        val result = CardRetrieval.fire("where is The Silver Key?", listOf(fired, sibling), tags, entryTags)
        assertEquals(listOf("e1"), result.direct.map { it.entry.entryId })
        // One hop: the sibling rides along via the shared (browse-only) tag.
        assertEquals(listOf("e2"), result.pullAlong.map { it.entry.entryId })
    }

    @Test
    fun geographyBodyCarriesTheFullParentChain() {
        val region = entry("r1", CardSections.REGIONS, "Verdant Kingdom")
        val settlement = entry("s1", CardSections.SETTLEMENTS, "Eldoria", parent = "r1")
        val poi = entry(
            "p1", CardSections.POINTS_OF_INTEREST, "Red Dragon Tavern",
            parent = "s1", description = "Smoky common room."
        )
        val byId = listOf(region, settlement, poi).associateBy { it.entryId }
        val body = CardRetrieval.composeBody(poi, byId)
        assertEquals("in Eldoria → Verdant Kingdom; Smoky common room.", body)
    }

    @Test
    fun inventoryBodyShowsKindAndQuantity() {
        val picks = entry(
            "i1", CardSections.INVENTORY, "Lockpicks",
            cardType = CardType.RP_CHARACTER, quantity = 3, kind = "mundane"
        )
        assertEquals("mundane; ×3", CardRetrieval.composeBody(picks, mapOf("i1" to picks)))
    }

    @Test
    fun connectedNamesListTagSharingSiblings() {
        val a = entry("e1", CardSections.RELIQUARY, "The Silver Key")
        val b = entry("e2", CardSections.POINTS_OF_INTEREST, "Eclipse Gate")
        val c = entry("e3", CardSections.REGIONS, "Verdant Kingdom")
        val entryTags = mapOf("e1" to listOf("t1"), "e2" to listOf("t1"))
        assertEquals(
            listOf("Eclipse Gate"),
            CardRetrieval.connectedNames(a, listOf(a, b, c), entryTags)
        )
    }
}
