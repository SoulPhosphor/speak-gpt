package org.teslasoft.assistant.preferences.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.backup.portable.CompanionMemoryRestorePlanner
import org.teslasoft.assistant.preferences.backup.portable.MemoryReferenceIds

@RunWith(AndroidJUnit4::class)
class MemorySharedRestoreInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "shared-restore-test-key".toByteArray()
    private val names = ArrayList<String>()

    @Before
    fun loadNative() {
        System.loadLibrary("sqlcipher")
    }

    @After
    fun cleanup() {
        names.forEach { name ->
            listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach {
                runCatching { context.getDatabasePath(it).delete() }
            }
        }
    }

    @Test
    fun tombstoneReplaceAndRepeat_preserveUnrelatedRows() {
        val store = open()
        listOf(
            arrayOf("memory", "m"),
            arrayOf("entity", "e"),
            arrayOf("project", "p"),
            arrayOf("roleplay_character", "r")
        ).forEach { (type, id) ->
            store.writableDatabase.execSQL(
                "INSERT INTO deleted_ids (record_type, record_id, deleted_at) VALUES (?, ?, 'now')",
                arrayOf(type, id)
            )
        }
        val current = store.exportSharedRestoreRows()
        val plan = CompanionMemoryRestorePlanner.plan(
            CompanionMemoryRestorePlanner.Input(
                current = current,
                identitiesSelected = false,
                memoriesSelected = true,
                modelRulesSelected = false,
                finalMemories = emptyPortableRows(MemoryPortableGroup.MEMORIES)
            )
        )!!

        assertTrue(store.replaceSharedRestoreRows(plan.desired, plan.affectedTables))
        assertTrue(store.replaceSharedRestoreRows(plan.desired, plan.affectedTables))

        val tombstones = store.exportSharedRestoreRows().tables.getValue("deleted_ids")
        assertEquals(listOf("roleplay_character"), tombstones.map { it["record_type"] })
        assertHealthy(store)
    }

    @Test
    fun identityApplyAndRollback_restoreCanonicalRowsIncludingDerivedState() {
        val store = open()
        insertCompanion(store, "old")
        store.writableDatabase.execSQL(
            "INSERT INTO memories (memory_id, scope, content, created_at, status) " +
                "VALUES ('memory', 'companion', 'kept', 'now', 'active')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO memory_companions (memory_id, companion_id) VALUES ('memory', 'old')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO transcripts (transcript_id, companion_id, content) " +
                "VALUES ('transcript', 'old', 'kept transcript')"
        )
        store.writableDatabase.execSQL(
            "UPDATE app_state SET active_companion_id = 'old' WHERE id = 1"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO generated_pending_drafts (memory_id, created_at) VALUES ('memory', 'now')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO embeddings (memory_id, embedding_model, vector, embedded_at) " +
                "VALUES ('memory', 'model', X'010203', 'now')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO import_conflicts " +
                "(conflict_id, kind, existing_memory_id, incoming_memory_id, incoming_json, created_at) " +
                "VALUES ('conflict', 'version', 'memory', 'memory', '{}', 'now')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO injection_cooldowns " +
                "(chat_id, source_type, entry_id, last_injected_turn) " +
                "VALUES ('chat', 'memory', 'memory', 1)"
        )
        val original = store.exportSharedRestoreRows()
        val finalRoleplay = emptyRoleplay().toMutableMap().apply {
            this["companions"] = companionRow("new")
        }
        val plan = CompanionMemoryRestorePlanner.plan(
            CompanionMemoryRestorePlanner.Input(
                current = original,
                identitiesSelected = true,
                memoriesSelected = false,
                modelRulesSelected = false,
                finalRoleplayTables = finalRoleplay,
                finalIdentityReferences = MemoryReferenceIds(companions = setOf("new"))
            )
        )!!

        assertTrue(store.replaceSharedRestoreRows(plan.desired, plan.affectedTables))
        assertTrue(store.exportSharedRestoreRows().tables.getValue("memory_companions").isEmpty())
        assertNull(store.exportSharedRestoreRows().tables.getValue("transcripts").single()["companion_id"])
        assertHealthy(store)

        assertTrue(store.replaceSharedRestoreRows(original, plan.affectedTables))
        assertEquals(original, store.exportSharedRestoreRows())
        assertHealthy(store)
    }

    @Test
    fun invalidForeignKeyBeforeCommit_changesNoRows() {
        val store = open()
        store.writableDatabase.execSQL(
            "INSERT INTO memories (memory_id, scope, content, created_at, status) " +
                "VALUES ('memory', 'global', 'before', 'now', 'active')"
        )
        val original = store.exportSharedRestoreRows()
        val brokenTables = original.tables.mapValuesTo(LinkedHashMap()) { (table, rows) ->
            if (table == "memory_companions") {
                listOf(linkedMapOf<String, Any?>(
                    "memory_id" to "memory", "companion_id" to "missing"
                ))
            } else rows
        }
        val broken = MemorySharedRestoreRows(brokenTables)
        val affected = MemorySharedRestoreRowFormat.memoryTables +
            MemorySharedRestoreRowFormat.memoryAuxiliaryTables

        assertFalse(store.replaceSharedRestoreRows(broken, affected))
        assertEquals(original, store.exportSharedRestoreRows())
        assertHealthy(store)
    }

    @Test
    fun combinedIdentityAndMemoryRestore_commitsNewRelationshipTogether() {
        val store = open()
        val current = store.exportSharedRestoreRows()
        val finalRoleplay = emptyRoleplay().toMutableMap().apply {
            this["companions"] = companionRow("new")
        }
        val finalMemories = emptyPortableRows(MemoryPortableGroup.MEMORIES).let { empty ->
            MemoryPortableRows(empty.tables.toMutableMap().apply {
                this["memories"] = listOf(linkedMapOf(
                    "memory_id" to "new-memory",
                    "scope" to "companion",
                    "content" to "new content",
                    "created_at" to "now",
                    "status" to "active"
                ))
                this["memory_companions"] = listOf(linkedMapOf(
                    "memory_id" to "new-memory",
                    "companion_id" to "new"
                ))
            })
        }
        val plan = CompanionMemoryRestorePlanner.plan(
            CompanionMemoryRestorePlanner.Input(
                current = current,
                identitiesSelected = true,
                memoriesSelected = true,
                modelRulesSelected = false,
                finalRoleplayTables = finalRoleplay,
                finalMemories = finalMemories,
                finalIdentityReferences = MemoryReferenceIds(companions = setOf("new"))
            )
        )!!

        assertTrue(store.replaceSharedRestoreRows(plan.desired, plan.affectedTables))
        val committed = store.exportSharedRestoreRows()
        assertEquals("new", committed.tables.getValue("companions").single()["companion_id"])
        assertEquals(
            "new",
            committed.tables.getValue("memory_companions").single()["companion_id"]
        )
        assertHealthy(store)
    }

    private fun open(): MemoryStore {
        val name = "shared_restore_${System.nanoTime()}.db"
        names.add(name)
        return MemoryStore.openForTest(context, name, key)
    }

    private fun insertCompanion(store: MemoryStore, id: String) {
        val row = companionRow(id).single()
        store.writableDatabase.execSQL(
            "INSERT INTO companions " +
                "(companion_id, current_name, essence, memory_participation, hard_limits_json, " +
                "model_adaptations_json, created_at, status, origin) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                row["companion_id"], row["current_name"], row["essence"],
                row["memory_participation"], row["hard_limits_json"],
                row["model_adaptations_json"], row["created_at"], row["status"], row["origin"]
            )
        )
    }

    private fun companionRow(id: String): List<Map<String, Any?>> = listOf(linkedMapOf(
        "companion_id" to id,
        "current_name" to id,
        "essence" to "essence",
        "memory_participation" to "full",
        "hard_limits_json" to "[]",
        "model_adaptations_json" to "[]",
        "created_at" to "now",
        "status" to "active",
        "origin" to "user"
    ))

    private fun emptyRoleplay(): Map<String, List<Map<String, Any?>>> =
        CompanionBackupFormat.ROLEPLAY_TABLES.associateTo(LinkedHashMap()) { it to emptyList() }

    private fun emptyPortableRows(group: MemoryPortableGroup) = MemoryPortableRows(
        MemoryPortableRowFormat.specs(group).associateTo(LinkedHashMap()) { it.table to emptyList() }
    )

    private fun assertHealthy(store: MemoryStore) {
        assertNull(store.integrityCheck())
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check", emptyArray<String>()).use {
            assertFalse(it.moveToFirst())
        }
    }
}
