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

package org.teslasoft.assistant.preferences.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed/export codec is the contract behind the user's memory backup and
 * restore (Import/Export) and the future cross-device sync — a silent
 * asymmetry between parse and serialize corrupts every one of them. The app
 * ships no bundled seed any more, so these tests run against an inline
 * fixture that exercises the tricky passthrough fields (protection block,
 * provenance, origin) rather than a shipped asset.
 */
class MemorySeedCodecTest {

    /** A minimal but representative store: one of each record type, with the
     *  fields most likely to be dropped in a round-trip (protection, provenance,
     *  non-default origin, raw-JSON policy). */
    private fun fixtureJson(): String = """
        {
          "schema_version": "1.11.0",
          "owner_profile": { "portrait": "A test owner.", "standing_context": "ctx", "updated_at": "2026-07-05T00:00:00Z" },
          "companions": [
            { "companion_id": "c-1", "current_name": "Test", "essence": "e",
              "memory_participation": "full", "hard_limits": ["never mean"],
              "created_at": "2026-07-05T00:00:00Z", "status": "active" }
          ],
          "entities": [
            { "entity_id": "e-1", "kind": "project", "name": "Proj", "summary": "s",
              "status": "active", "importance": 3 }
          ],
          "memories": [
            { "memory_id": "m-1", "scope": "global", "kind": "identity",
              "title": "Protected one", "content": "truth + handling",
              "importance": 4, "always_load": false,
              "protection": { "is_protected": true, "reasons": ["assumption_risk"],
                              "handling": ["follow the user's lead"], "casual_mention_ok": false },
              "provenance": { "source": "user_stated", "confidence": "certain", "noted_on": "2026-07-05T00:00:00Z" },
              "created_at": "2026-07-05T00:00:00Z", "status": "active" }
          ],
          "modes": [
            { "mode_id": "mode-x", "name": "X", "signals": ["a"], "respond": ["b"], "avoid": ["c"] }
          ],
          "directives": [
            { "directive_id": "d-1", "text": "Be kind.", "priority": 2 }
          ],
          "worlds": [], "user_personas": [], "roleplay_characters": [], "proposals": [],
          "archivist_settings": { "trigger": "manual", "harvest_generosity": "balanced",
                                  "autonomy": {}, "notes": null },
          "retrieval_policy": { "weights": { "similarity": 0.6, "importance": 0.3, "recency": 0.1 } }
        }
    """.trimIndent()

    @Test
    fun parsesFixtureWithProtectionBlock() {
        val data = MemorySeedCodec.parse(fixtureJson())
        assertEquals("1.11.0", data.schemaVersion)
        assertEquals(1, data.companions.size)
        assertEquals(1, data.memories.size)
        assertEquals("manual", data.archivistSettings!!.runTrigger)

        // The protection block is the system's core invariant — the codec must
        // never lose it.
        val protected = data.memories.first()
        val protection = JSONObject(protected.protectionJson!!)
        assertTrue(protection.getBoolean("is_protected"))
        assertTrue(protection.getJSONArray("handling").length() > 0)
    }

    @Test
    fun roundTripIsLossless() {
        val first = MemorySeedCodec.parse(fixtureJson())
        val serialized = MemorySeedCodec.serialize(first)
        val second = MemorySeedCodec.parse(serialized)

        // Data-class equality covers every field of every record, including the
        // raw-JSON passthrough columns and origin (both sides normalized by org.json).
        assertEquals(first, second)
    }

    @Test
    fun originRoundTripsAndDefaultsToUser() {
        // A record with no origin in the JSON parses as 'user'; a non-default
        // origin survives a serialize/parse cycle.
        val data = MemorySeedCodec.parse(fixtureJson())
        assertEquals("user", data.memories.first().origin)

        val withOrigin = data.copy(
            companions = listOf(data.companions.first().copy(origin = "archivist"))
        )
        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(withOrigin))
        assertEquals("archivist", back.companions.first().origin)
    }

    @Test
    fun campaignLayerRoundTrips() {
        // The 📌 campaign amendment adds a campaigns array and a memory
        // campaign_id; both must survive a backup/restore cycle intact.
        val withCampaign = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "modes": [], "directives": [],
              "worlds": [], "user_personas": [], "roleplay_characters": [], "proposals": [],
              "campaigns": [
                { "campaign_id": "camp-1", "name": "The Long Dark", "world_id": "w-1",
                  "roleplay_character_id": "rc-1", "companion_id": "c-1",
                  "status": "active", "story_so_far": "It began in the rain.",
                  "created_at": "2026-07-06T00:00:00Z" }
              ],
              "memories": [
                { "memory_id": "m-camp", "scope": "global", "kind": "state",
                  "title": "Inventory", "content": "One silver key.",
                  "campaign_id": "camp-1", "importance": 3, "always_load": false,
                  "created_at": "2026-07-06T00:00:00Z", "status": "active" }
              ]
            }
        """.trimIndent()

        val data = MemorySeedCodec.parse(withCampaign)
        assertEquals(1, data.campaigns.size)
        assertEquals("The Long Dark", data.campaigns.first().name)
        // Legacy single "campaign_id" key parses into the multi-select set (§2).
        assertEquals(listOf("camp-1"), data.memories.first().campaignIds)

        // Lossless round-trip including the new columns.
        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data, back)
        assertEquals(listOf("camp-1"), back.memories.first().campaignIds)
        assertEquals("It began in the rain.", back.campaigns.first().storySoFar)
    }

    @Test
    fun roleplayCardLayerRoundTrips() {
        // Stage 3.6a (roleplay_cards_and_tags_spec.md): the card layer —
        // world core fields, RP-character Zone 1, the campaign bookmark +
        // party links, NPC party members, polymorphic Zone 2 card entries and
        // the roleplay tag pool — must survive a backup/restore cycle, or the
        // Reset-memories "save a backup first" path silently loses cards.
        val withCards = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "memories": [], "modes": [],
              "directives": [], "user_personas": [], "proposals": [],
              "worlds": [
                { "world_id": "w-1", "name": "Duskmere", "premise": "dormant legacy text",
                  "cosmology": "Three moons; stars are dead gods.",
                  "premise_vibe": "The sun never rises.",
                  "magic_rules": "Magic causes physical corruption.",
                  "status": "active", "created_at": "2026-07-07T00:00:00Z" }
              ],
              "roleplay_characters": [
                { "roleplay_character_id": "rc-1", "name": "Vael", "played_by": "user",
                  "description": "legacy free-text", "status": "active",
                  "species": "half-elf", "class": "ranger",
                  "core_personality": "wary, loyal", "physical_description": "scarred hands",
                  "goals_drives": "sworn grudge against orcs",
                  "created_at": "2026-07-07T00:00:00Z" }
              ],
              "party_members": [
                { "party_member_id": "pm-1", "name": "Rose", "species": "human",
                  "class": "cleric", "speech_style": "soft, formal", "status": "dead",
                  "created_at": "2026-07-07T00:00:00Z" }
              ],
              "campaigns": [
                { "campaign_id": "camp-1", "name": "The Long Dark", "world_id": "w-1",
                  "roleplay_character_id": "rc-1", "status": "active",
                  "quest_anchor": "Reach Silver Hills before the eclipse.",
                  "active_scene": "The Smuggler's Cove - flooded.",
                  "party_member_ids": ["pm-1"],
                  "created_at": "2026-07-07T00:00:00Z" }
              ],
              "card_entries": [
                { "entry_id": "ce-1", "card_type": "rp_character", "card_id": "rc-1",
                  "section": "inventory", "name": "Lockpicks", "entry_kind": "mundane",
                  "quantity": 3, "created_at": "2026-07-07T00:00:00Z" },
                { "entry_id": "ce-2", "card_type": "world", "card_id": "w-1",
                  "section": "settlements", "name": "Eldoria", "description": "A walled river town.",
                  "parent_entry_id": "ce-3", "created_at": "2026-07-07T00:00:00Z" },
                { "entry_id": "ce-4", "card_type": "campaign", "card_id": "camp-1",
                  "section": "reliquary", "name": "The Silver Key",
                  "description": "Opens the vault.", "holder": "Vael",
                  "significance": "Only way past the eclipse gate.",
                  "created_at": "2026-07-07T00:00:00Z" }
              ],
              "rp_tags": [
                { "tag_id": "tag-1", "name": "eclipse", "auto_trigger": true,
                  "targets": [ { "type": "card_entry", "id": "ce-4" }, { "type": "world", "id": "w-1" } ] },
                { "tag_id": "tag-2", "name": "magic", "auto_trigger": false }
              ]
            }
        """.trimIndent()

        val data = MemorySeedCodec.parse(withCards)
        assertEquals("Three moons; stars are dead gods.", data.worlds.first().cosmology)
        // Fresh v8 world-core fields (spec §8a): distinct from the dormant
        // premise/rules columns, which the cards never reuse.
        assertEquals("The sun never rises.", data.worlds.first().premiseVibe)
        assertEquals("Magic causes physical corruption.", data.worlds.first().magicRules)
        assertEquals("ranger", data.roleplayCharacters.first().charClass)
        assertEquals("sworn grudge against orcs", data.roleplayCharacters.first().goalsDrives)
        assertEquals("dead", data.partyMembers.first().status)
        assertEquals(listOf("pm-1"), data.campaigns.first().partyMemberIds)
        assertEquals("Reach Silver Hills before the eclipse.", data.campaigns.first().questAnchor)
        assertEquals(3, data.cardEntries.first().quantity)
        assertEquals("ce-3", data.cardEntries[1].parentEntryId)
        assertEquals("Vael", data.cardEntries[2].holder)
        // The per-tag browse-only switch (spec §3) must survive the trip.
        assertTrue(data.rpTags[0].autoTrigger)
        assertEquals(false, data.rpTags[1].autoTrigger)
        assertEquals(2, data.rpTags[0].targets.size)

        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data, back)
    }

    @Test
    fun modelRulesRoundTrip() {
        // Stage 4 (owner_approved_rules §11 Revision 5): model rules are user-
        // authored, so backups must carry the rules (with their own model-
        // strings list), the tag pool, and the links between them — including a
        // draft with its source model string.
        val withModelRules = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "memories": [], "modes": [],
              "directives": [], "worlds": [], "user_personas": [],
              "roleplay_characters": [], "proposals": [],
              "model_rules": [
                { "rule_id": "mr-1",
                  "text": "Never open with an apology.", "status": "active",
                  "model_strings": ["glm-5-0502", "glm-5-0219"],
                  "created_at": "2026-07-07T00:00:00Z" },
                { "rule_id": "mr-2", "text": "Stop repeating the question back.",
                  "status": "draft", "source_model_string": "glm-experimental",
                  "model_strings": [],
                  "created_at": "2026-07-07T00:00:00Z" }
              ],
              "model_rule_tags": [
                { "tag_id": "mrt-1", "name": "no therapy speak",
                  "created_at": "2026-07-07T00:00:00Z" }
              ],
              "model_rule_tag_links": [
                { "rule_id": "mr-1", "tag_id": "mrt-1" }
              ]
            }
        """.trimIndent()

        val data = MemorySeedCodec.parse(withModelRules)
        assertEquals(2, data.modelRules.size)
        // The active rule keeps its own model-strings list.
        assertEquals(2, JSONArray(data.modelRules[0].modelStringsJson).length())
        // The draft (no model strings yet) and its source model string survive.
        assertEquals("draft", data.modelRules[1].status)
        assertEquals("glm-experimental", data.modelRules[1].sourceModelString)
        assertEquals(0, JSONArray(data.modelRules[1].modelStringsJson).length())
        // Tags and the rule->tag link survive too.
        assertEquals(1, data.modelRuleTags.size)
        assertEquals("no therapy speak", data.modelRuleTags.first().name)
        assertEquals(1, data.modelRuleTagLinks.size)
        assertEquals("mr-1", data.modelRuleTagLinks.first().ruleId)
        assertEquals("mrt-1", data.modelRuleTagLinks.first().tagId)

        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data, back)
    }

    @Test
    fun exportEnvelopeCarriesChatsAndMeta() {
        val data = MemorySeedCodec.parse(fixtureJson())
        val chats = JSONArray().put(
            JSONObject().put("name", "Test chat").put("messages", JSONArray())
        )
        val out = JSONObject(MemorySeedCodec.serialize(data, appChats = chats, exportedAtIso = "2026-07-03T00:00:00Z"))

        assertEquals(1, out.getJSONArray("app_chats").length())
        val meta = out.getJSONObject("export_meta")
        assertEquals(MemorySeedCodec.EXPORT_FORMAT, meta.getString("format"))
        assertEquals("2026-07-03T00:00:00Z", meta.getString("exported_at"))
        // A strict schema reader must still see the standard top-level keys.
        assertTrue(out.has("companions"))
        assertTrue(out.has("memories"))
        assertTrue(out.has("retrieval_policy"))
    }

    @Test
    fun completeExportOmitsTheChatsCompleteFlag() {
        // Absent = complete (the message-completion-state convention): every
        // export ever written before the flag existed must stay trusted.
        val data = MemorySeedCodec.parse(fixtureJson())
        val out = JSONObject(MemorySeedCodec.serialize(data, appChats = JSONArray()))
        assertFalse(out.getJSONObject("export_meta").has("app_chats_complete"))
    }

    @Test
    fun exportDuringStorageOutageIsMarkedIncomplete() {
        // An export taken while chat storage is locked or partially
        // unreadable must never be mistaken for a full copy of the chats.
        val data = MemorySeedCodec.parse(fixtureJson())
        val out = JSONObject(
            MemorySeedCodec.serialize(data, appChats = JSONArray(), appChatsComplete = false)
        )
        assertFalse(out.getJSONObject("export_meta").getBoolean("app_chats_complete"))
        // The parser must not choke on the extra meta key (older versions
        // simply ignore it).
        MemorySeedCodec.parse(
            MemorySeedCodec.serialize(data, appChats = JSONArray(), appChatsComplete = false)
        )
    }
}
