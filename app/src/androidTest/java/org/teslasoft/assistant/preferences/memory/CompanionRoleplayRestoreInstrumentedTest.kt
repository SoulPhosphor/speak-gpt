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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SQLCipher coverage for the Companion & Roleplay Backup's database
 * engine (companion-roleplay-backup-plan.md §6.3/§6.4):
 * [MemoryStore.exportRoleplayTables] and [MemoryStore.replaceRoleplayTables]
 * against throwaway stores — the round trip, replacement (not merge)
 * semantics, every §6.4 resolution rule, memory tag-link survival, rollback
 * on an injected mid-transaction failure, and the restore-token pivot.
 *
 * Like MemoryStoreInstrumentedTest, these need an arm64 device/emulator to
 * RUN; CI compiles them to guard against bit-rot.
 */
@RunWith(AndroidJUnit4::class)
class CompanionRoleplayRestoreInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "companion-restore-test-key".toByteArray()
    private val dbNames = ArrayList<String>()

    @Before
    fun loadNative() {
        System.loadLibrary("sqlcipher")
    }

    @After
    fun cleanup() {
        for (name in dbNames) {
            try { ctx.getDatabasePath(name).delete() } catch (_: Exception) {}
            try { ctx.getDatabasePath("$name-wal").delete() } catch (_: Exception) {}
            try { ctx.getDatabasePath("$name-shm").delete() } catch (_: Exception) {}
        }
    }

    private fun freshDbName(): String {
        val name = "companion_restore_test_${System.nanoTime()}.db"
        dbNames.add(name)
        return name
    }

    private fun open(name: String): MemoryStore = MemoryStore.openForTest(ctx, name, key)

    /* ------------------------- seeding helpers ------------------------- */

    private fun seedFullStructure(db: SQLiteDatabase, suffix: String) {
        db.execSQL(
            "INSERT INTO companions (companion_id, current_name, essence, created_at, status) " +
                "VALUES ('c-$suffix', 'Aria', 'warm', '2026-07-01T00:00:00Z', 'active')"
        )
        db.execSQL(
            "INSERT INTO companion_name_history (companion_id, name, effective_from) " +
                "VALUES ('c-$suffix', 'Aria', '2026-07-01T00:00:00Z')"
        )
        db.execSQL(
            "INSERT INTO user_personas (persona_id, name, presentation, status) " +
                "VALUES ('up-$suffix', 'Me', 'casual', 'active')"
        )
        db.execSQL(
            "INSERT INTO roleplay_characters (roleplay_character_id, name, played_by, description, status) " +
                "VALUES ('r-$suffix', 'Kael', 'user', 'a ranger', 'active')"
        )
        db.execSQL(
            "INSERT INTO worlds (world_id, name, premise, status) " +
                "VALUES ('w-$suffix', 'Eldoria', 'high fantasy', 'active')"
        )
        db.execSQL(
            "INSERT INTO campaigns (campaign_id, name, world_id, companion_id, status) " +
                "VALUES ('cam-$suffix', 'The Vale', 'w-$suffix', 'c-$suffix', 'active')"
        )
        db.execSQL(
            "INSERT INTO party_members (party_member_id, name, created_at) " +
                "VALUES ('pm-$suffix', 'Brin', '2026-07-02T00:00:00Z')"
        )
        db.execSQL(
            "INSERT INTO campaign_party_members (campaign_id, party_member_id) " +
                "VALUES ('cam-$suffix', 'pm-$suffix')"
        )
        db.execSQL(
            "INSERT INTO card_entries (entry_id, card_type, card_id, section, name, quantity, created_at) " +
                "VALUES ('ce-$suffix', 'world', 'w-$suffix', 'locations', 'The Vale', 3, '2026-07-02T00:00:00Z')"
        )
        db.execSQL("INSERT INTO rp_tags (tag_id, name) VALUES ('t-$suffix', 'vale')")
        db.execSQL(
            "INSERT INTO rp_tag_links (tag_id, target_type, target_id) " +
                "VALUES ('t-$suffix', 'card_entry', 'ce-$suffix')"
        )
    }

    private fun seedMemoryWith(db: SQLiteDatabase, memoryId: String, suffix: String) {
        db.execSQL(
            "INSERT INTO memories (memory_id, scope, content, created_at, status, " +
                "world_id, campaign_id, roleplay_character_id) VALUES " +
                "('$memoryId', 'global', 'remember this', '2026-07-03T00:00:00Z', 'active', " +
                "'w-$suffix', 'cam-$suffix', 'r-$suffix')"
        )
        db.execSQL("INSERT INTO memory_companions (memory_id, companion_id) VALUES ('$memoryId', 'c-$suffix')")
        db.execSQL("INSERT INTO memory_worlds (memory_id, world_id) VALUES ('$memoryId', 'w-$suffix')")
        db.execSQL("INSERT INTO memory_campaigns (memory_id, campaign_id) VALUES ('$memoryId', 'cam-$suffix')")
        db.execSQL(
            "INSERT INTO memory_roleplay_characters (memory_id, roleplay_character_id) " +
                "VALUES ('$memoryId', 'r-$suffix')"
        )
    }

    private fun count(store: MemoryStore, table: String, where: String? = null): Int {
        val sql = "SELECT COUNT(*) FROM $table" + (where?.let { " WHERE $it" } ?: "")
        store.readableDatabase.rawQuery(sql, emptyArray<String>()).use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    private fun scalar(store: MemoryStore, sql: String): String? {
        store.readableDatabase.rawQuery(sql, emptyArray<String>()).use {
            return if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    }

    /* ------------------------------ tests ------------------------------ */

    @Test
    fun exportThenReplaceRoundTripsEveryRecordSet() {
        val source = open(freshDbName())
        seedFullStructure(source.writableDatabase, "1")
        val exported = source.exportRoleplayTables()

        val target = open(freshDbName())
        target.replaceRoleplayTables(exported, "tok-1") {}
        target.deleteMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN)

        assertEquals(exported, target.exportRoleplayTables())
    }

    @Test
    fun memoryTargetedTagLinksAreNotExported() {
        val source = open(freshDbName())
        val db = source.writableDatabase
        seedFullStructure(db, "1")
        seedMemoryWith(db, "m-1", "1")
        db.execSQL(
            "INSERT INTO rp_tag_links (tag_id, target_type, target_id) VALUES ('t-1', 'memory', 'm-1')"
        )

        val exported = source.exportRoleplayTables()
        val linkTargets = exported["rp_tag_links"]!!.map { it["target_type"] }
        assertFalse("memory links must never be exported", linkTargets.contains("memory"))
        assertTrue(linkTargets.contains("card_entry"))
    }

    @Test
    fun replaceIsReplacementNotMerge() {
        val device = open(freshDbName())
        seedFullStructure(device.writableDatabase, "old")

        val source = open(freshDbName())
        seedFullStructure(source.writableDatabase, "new")

        device.replaceRoleplayTables(source.exportRoleplayTables(), "tok-2") {}

        assertEquals(0, count(device, "companions", "companion_id = 'c-old'"))
        assertEquals(1, count(device, "companions", "companion_id = 'c-new'"))
        assertEquals(0, count(device, "worlds", "world_id = 'w-old'"))
        assertEquals(1, count(device, "campaign_party_members", "campaign_id = 'cam-new'"))
    }

    @Test
    fun resolutionRulesKeepResolvedAndRemoveUnresolved() {
        val device = open(freshDbName())
        val db = device.writableDatabase
        seedFullStructure(db, "old")
        seedMemoryWith(db, "m-1", "old")
        db.execSQL(
            "INSERT INTO transcripts (transcript_id, chat_id, companion_id, world_id, " +
                "roleplay_character_id, user_persona_id, content) VALUES " +
                "('tr-1', 'chat-1', 'c-old', 'w-old', 'r-old', 'up-old', 'transcript text')"
        )
        db.execSQL(
            "INSERT INTO app_state (id, active_companion_id, active_world_id, " +
                "active_roleplay_character_id, active_user_persona_id) VALUES " +
                "(1, 'c-old', 'w-old', 'r-old', 'up-old')"
        )

        // The backup carries the SAME companion id (the normal same-install
        // disaster-recovery case) but a DIFFERENT world/campaign/character set.
        val backup = LinkedHashMap(device.exportRoleplayTables())
        backup["worlds"] = emptyList()
        backup["campaigns"] = emptyList()
        backup["roleplay_characters"] = emptyList()
        backup["user_personas"] = emptyList()
        backup["campaign_party_members"] = emptyList()

        device.replaceRoleplayTables(backup, "tok-3") {}

        // Kept: everything whose target still resolves.
        assertEquals(1, count(device, "memory_companions", "memory_id = 'm-1' AND companion_id = 'c-old'"))
        assertEquals("c-old", scalar(device, "SELECT companion_id FROM transcripts WHERE transcript_id = 'tr-1'"))
        assertEquals("c-old", scalar(device, "SELECT active_companion_id FROM app_state WHERE id = 1"))

        // Removed/cleared: every link whose target is gone. The memory row
        // itself is untouched.
        assertEquals(1, count(device, "memories", "memory_id = 'm-1'"))
        assertEquals(0, count(device, "memory_worlds", "memory_id = 'm-1'"))
        assertEquals(0, count(device, "memory_campaigns", "memory_id = 'm-1'"))
        assertEquals(0, count(device, "memory_roleplay_characters", "memory_id = 'm-1'"))
        assertNull(scalar(device, "SELECT world_id FROM memories WHERE memory_id = 'm-1'"))
        assertNull(scalar(device, "SELECT campaign_id FROM memories WHERE memory_id = 'm-1'"))
        assertNull(scalar(device, "SELECT roleplay_character_id FROM memories WHERE memory_id = 'm-1'"))
        assertNull(scalar(device, "SELECT world_id FROM transcripts WHERE transcript_id = 'tr-1'"))
        assertNull(scalar(device, "SELECT roleplay_character_id FROM transcripts WHERE transcript_id = 'tr-1'"))
        assertNull(scalar(device, "SELECT user_persona_id FROM transcripts WHERE transcript_id = 'tr-1'"))
        assertNull(scalar(device, "SELECT active_world_id FROM app_state WHERE id = 1"))
        assertNull(scalar(device, "SELECT active_roleplay_character_id FROM app_state WHERE id = 1"))
        assertNull(scalar(device, "SELECT active_user_persona_id FROM app_state WHERE id = 1"))
        // Transcript content untouched.
        assertEquals("transcript text", scalar(device, "SELECT content FROM transcripts WHERE transcript_id = 'tr-1'"))
    }

    @Test
    fun memoryTagLinksSurviveExactlyWhenTheirTagSurvives() {
        val device = open(freshDbName())
        val db = device.writableDatabase
        seedFullStructure(db, "old")
        seedMemoryWith(db, "m-1", "old")

        // The backup is captured while only tag t-old exists; the device then
        // gains tag t-doomed and memory links through BOTH tags before the
        // restore replaces the tag pool.
        val backup = device.exportRoleplayTables()
        db.execSQL("INSERT INTO rp_tags (tag_id, name) VALUES ('t-doomed', 'doomed')")
        db.execSQL("INSERT INTO rp_tag_links (tag_id, target_type, target_id) VALUES ('t-old', 'memory', 'm-1')")
        db.execSQL("INSERT INTO rp_tag_links (tag_id, target_type, target_id) VALUES ('t-doomed', 'memory', 'm-1')")

        device.replaceRoleplayTables(backup, "tok-4") {}

        assertEquals(
            1,
            count(device, "rp_tag_links", "tag_id = 't-old' AND target_type = 'memory' AND target_id = 'm-1'")
        )
        assertEquals(0, count(device, "rp_tag_links", "tag_id = 't-doomed'"))
        assertEquals(0, count(device, "rp_tags", "tag_id = 't-doomed'"))
    }

    @Test
    fun injectedFailureBeforeCommitRollsEverythingBack() {
        val device = open(freshDbName())
        val db = device.writableDatabase
        seedFullStructure(db, "old")
        seedMemoryWith(db, "m-1", "old")
        val before = device.exportRoleplayTables()

        val source = open(freshDbName())
        seedFullStructure(source.writableDatabase, "new")

        try {
            device.replaceRoleplayTables(source.exportRoleplayTables(), "tok-5") {
                // The staged settings write blowing up mid-transaction (§6.3
                // step 4): the database change must vanish with it.
                throw RuntimeException("injected settings failure")
            }
            fail("expected the injected failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("injected settings failure", e.message)
        }

        assertEquals(before, device.exportRoleplayTables())
        assertEquals(1, count(device, "memory_worlds", "memory_id = 'm-1'"))
        assertNull(device.getMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN))
    }

    @Test
    fun restoreTokenIsDurableExactlyOnCommit() {
        val device = open(freshDbName())
        seedFullStructure(device.writableDatabase, "old")

        device.replaceRoleplayTables(device.exportRoleplayTables(), "tok-6") {}
        assertEquals("tok-6", device.getMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN))

        device.deleteMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN)
        assertNull(device.getMeta(MemoryStore.META_COMPANION_RESTORE_TOKEN))
    }
}
