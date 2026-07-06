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
        assertEquals("camp-1", data.memories.first().campaignId)

        // Lossless round-trip including the new columns.
        val back = MemorySeedCodec.parse(MemorySeedCodec.serialize(data))
        assertEquals(data, back)
        assertEquals("camp-1", back.memories.first().campaignId)
        assertEquals("It began in the rain.", back.campaigns.first().storySoFar)
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
}
