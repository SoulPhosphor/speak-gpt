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

        // Titles are retired (§3.1): they are deliberately not written to a
        // revised-model export, so a legacy title does not survive the trip.
        // Every other field must be preserved exactly. Normalize the one
        // intentionally-dropped field, then assert strict data-class equality.
        val stripTitles = { d: MemoryStoreData ->
            d.copy(memories = d.memories.map { it.copy(title = "") })
        }
        assertEquals("only the retired title differs", stripTitles(first), second)
        // And prove the drop is real, not accidental preservation.
        assertEquals("", second.memories.first().title)
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

        // Round-trip preserving the new columns; the retired title is the only
        // field intentionally not carried across (§3.1).
        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data.copy(memories = data.memories.map { it.copy(title = "") }), back)
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
    fun profileImageRefRoundTrips() {
        // Profile Images (DB v15): a My Persona and a user-side Roleplay
        // Character may each carry an image_ref (a bare hash). It must survive
        // a backup/restore cycle for both record types.
        val withImages = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "memories": [], "modes": [],
              "directives": [], "worlds": [], "proposals": [],
              "user_personas": [
                { "persona_id": "up-1", "name": "Explorer", "presentation": "curious",
                  "status": "active", "image_ref": "aaaa1111",
                  "created_at": "2026-07-19T00:00:00Z" }
              ],
              "roleplay_characters": [
                { "roleplay_character_id": "rc-1", "name": "Mira", "played_by": "user",
                  "description": "d", "status": "active", "image_ref": "bbbb2222",
                  "created_at": "2026-07-19T00:00:00Z" }
              ]
            }
        """.trimIndent()

        val data = MemorySeedCodec.parse(withImages)
        assertEquals("aaaa1111", data.userPersonas.first().imageRef)
        assertEquals("bbbb2222", data.roleplayCharacters.first().imageRef)

        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data, back)
        assertEquals("aaaa1111", back.userPersonas.first().imageRef)
        assertEquals("bbbb2222", back.roleplayCharacters.first().imageRef)
    }

    @Test
    fun profileImageRefIsNullWhenAbsentInBackup() {
        // Pre-v15 backups carry no image_ref key: both record types must parse
        // it as null (not "" or "null"), so old backups import cleanly.
        val noImages = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "memories": [], "modes": [],
              "directives": [], "worlds": [], "proposals": [],
              "user_personas": [
                { "persona_id": "up-1", "name": "Explorer", "presentation": "curious",
                  "status": "active", "created_at": "2026-07-19T00:00:00Z" }
              ],
              "roleplay_characters": [
                { "roleplay_character_id": "rc-1", "name": "Mira", "played_by": "user",
                  "description": "d", "status": "active",
                  "created_at": "2026-07-19T00:00:00Z" }
              ]
            }
        """.trimIndent()

        val data = MemorySeedCodec.parse(noImages)
        assertEquals(null, data.userPersonas.first().imageRef)
        assertEquals(null, data.roleplayCharacters.first().imageRef)

        // A null image_ref is simply omitted on export (putIfNotNull), so the
        // round-trip stays null rather than materializing an empty string.
        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data, back)
        assertEquals(null, back.userPersonas.first().imageRef)
        assertEquals(null, back.roleplayCharacters.first().imageRef)
    }

    @Test
    fun userPersonaShortDescriptionRoundTrip() {
        // v16 (Profile Images phase 8): a My Persona's short_description is the
        // list-row subtitle. It must survive backup/restore, and older backups
        // that predate the column must import it as null.
        val withDesc = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "memories": [], "modes": [],
              "directives": [], "worlds": [], "proposals": [],
              "roleplay_characters": [],
              "user_personas": [
                { "persona_id": "up-1", "name": "Explorer", "presentation": "curious",
                  "status": "active", "short_description": "Weekend hiking me",
                  "created_at": "2026-07-19T00:00:00Z" },
                { "persona_id": "up-2", "name": "Formal", "presentation": "poised",
                  "status": "active", "created_at": "2026-07-19T00:00:00Z" }
              ]
            }
        """.trimIndent()

        val data = MemorySeedCodec.parse(withDesc)
        assertEquals("Weekend hiking me", data.userPersonas.first().shortDescription)
        assertEquals(null, data.userPersonas[1].shortDescription)

        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data, back)
        assertEquals("Weekend hiking me", back.userPersonas.first().shortDescription)
        assertEquals(null, back.userPersonas[1].shortDescription)
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

    /* --------------------- Phase 1: Types / importance --------------------- */

    /** A pre-Phase-1 backup: memories carry the legacy six-value `kind` (no
     *  `type_id`), meaningful `title`s, and 1–5 importance. */
    private fun legacyBackupJson(): String = """
        {
          "schema_version": "1.11.0",
          "companions": [], "entities": [], "modes": [], "directives": [],
          "worlds": [], "user_personas": [], "roleplay_characters": [], "proposals": [],
          "memories": [
            { "memory_id": "m-fact", "scope": "global", "kind": "fact",
              "title": "A fact title", "content": "The sky is blue.",
              "importance": 4, "created_at": "2026-07-05T00:00:00Z", "status": "active" },
            { "memory_id": "m-inst", "scope": "companion", "kind": "instruction",
              "companion_ids": ["c-1"], "title": "An instruction",
              "content": "Avoid unsolicited checklists.",
              "importance": 5, "created_at": "2026-07-05T00:00:00Z", "status": "active" },
            { "memory_id": "m-lore", "scope": "world", "kind": "lore",
              "world_ids": ["w-1"], "title": "Lore title", "content": "Three moons hang overhead.",
              "importance": 2, "created_at": "2026-07-05T00:00:00Z", "status": "active" }
          ]
        }
    """.trimIndent()

    @Test
    fun legacyKindMapsToSeededTypeOnImportAndLoreBecomesNoType() {
        val data = MemorySeedCodec.parse(legacyBackupJson())
        val byId = data.memories.associateBy { it.memoryId }

        // Recognized starter kinds map to their seeded Type ids.
        assertEquals("mtype-fact", byId["m-fact"]!!.typeId)
        assertEquals("mtype-instruction", byId["m-inst"]!!.typeId)
        // Legacy lore is not a Type — it becomes No Type (null), without
        // touching the memory's scope/targets/content.
        assertEquals(null, byId["m-lore"]!!.typeId)
        assertEquals("world", byId["m-lore"]!!.scope)
        assertEquals(listOf("w-1"), byId["m-lore"]!!.worldIds)
        assertEquals("Three moons hang overhead.", byId["m-lore"]!!.content)
    }

    @Test
    fun legacyImportanceValuesArePreservedOnImport() {
        val byId = MemorySeedCodec.parse(legacyBackupJson()).memories.associateBy { it.memoryId }
        assertEquals(4, byId["m-fact"]!!.importance)
        assertEquals(5, byId["m-inst"]!!.importance)
        assertEquals(2, byId["m-lore"]!!.importance)
    }

    @Test
    fun revisedExportOmitsTitlesAndCarriesTypeId() {
        val data = MemorySeedCodec.parse(legacyBackupJson())
        val out = JSONObject(MemorySeedCodec.serialize(data))
        val mems = out.getJSONArray("memories")
        var checkedFact = false
        for (i in 0 until mems.length()) {
            val m = mems.getJSONObject(i)
            // No memory carries a title in a revised-model export (§3.1).
            assertFalse("titles must not be exported", m.has("title"))
            if (m.getString("memory_id") == "m-fact") {
                assertEquals("mtype-fact", m.getString("type_id"))
                checkedFact = true
            }
            // A No Type memory omits type_id entirely (putIfNotNull).
            if (m.getString("memory_id") == "m-lore") {
                assertFalse(m.has("type_id"))
            }
        }
        assertTrue(checkedFact)
    }

    @Test
    fun explicitTypeIdWinsOverLegacyKindOnImport() {
        // A Phase 1+ backup carries an explicit type_id; it takes precedence
        // over any legacy kind that may still ride along as baggage.
        val json = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "modes": [], "directives": [],
              "worlds": [], "user_personas": [], "roleplay_characters": [], "proposals": [],
              "memory_types": [
                { "type_id": "mtype-classic-cars", "name": "Classic Cars",
                  "created_at": "2026-08-04T00:00:00Z" }
              ],
              "memories": [
                { "memory_id": "m-1", "scope": "global", "kind": "fact",
                  "type_id": "mtype-classic-cars", "content": "A 1967 Mustang.",
                  "importance": 0, "created_at": "2026-08-04T00:00:00Z", "status": "active" }
              ]
            }
        """.trimIndent()
        val data = MemorySeedCodec.parse(json)
        assertEquals("mtype-classic-cars", data.memories.first().typeId)
        // The user-owned Type rides the backup.
        assertEquals(1, data.memoryTypes.size)
        assertEquals("Classic Cars", data.memoryTypes.first().name)
    }

    @Test
    fun memoryTypesRoundTrip() {
        val json = """
            {
              "schema_version": "1.11.0",
              "companions": [], "entities": [], "modes": [], "directives": [],
              "worlds": [], "user_personas": [], "roleplay_characters": [], "proposals": [],
              "memory_types": [
                { "type_id": "mtype-fact", "name": "Fact", "created_at": "2026-08-04T00:00:00Z" },
                { "type_id": "mtype-pets", "name": "Pets", "created_at": "2026-08-04T00:00:00Z" }
              ],
              "memories": [
                { "memory_id": "m-1", "scope": "global", "type_id": "mtype-pets",
                  "content": "The cat's name is Biscuit.", "importance": 0,
                  "created_at": "2026-08-04T00:00:00Z", "status": "active" }
              ]
            }
        """.trimIndent()
        val data = MemorySeedCodec.parse(json)
        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data.memoryTypes, back.memoryTypes)
        assertEquals("mtype-pets", back.memories.first().typeId)
        // A memory with no legacy kind and no title stays clean across the trip.
        assertEquals("", back.memories.first().kind)
        assertEquals("", back.memories.first().title)
    }

    @Test
    fun prePhase1BackupHasNoMemoryTypes() {
        // Older backups carry no memory_types array; it parses to empty and the
        // store keeps its seeded starter Types.
        assertTrue(MemorySeedCodec.parse(legacyBackupJson()).memoryTypes.isEmpty())
    }
}
