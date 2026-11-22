package org.teslasoft.assistant.preferences.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.memory.matching.ScannerComparisonCache

/**
 * The scanner comparison cache against real SQLCipher storage (Stage 6B.2):
 * the version 38 migration keeps everything and adds two empty tables, rows
 * go with their memory or scan range, changed text never reuses a vector,
 * the size limit holds, and scanner comparison keeps chat scope and status
 * boundaries. Runs on an arm64 device or emulator like the other memory
 * store tests.
 */
@RunWith(AndroidJUnit4::class)
class ScannerComparisonCacheInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "scanner-cache-instrumented-test-key".toByteArray()
    private val dbNames = ArrayList<String>()
    private val model = "gemma-256|doc-2"

    @Before
    fun loadNative() {
        System.loadLibrary("sqlcipher")
    }

    @After
    fun cleanup() {
        for (name in dbNames) {
            runCatching { ctx.getDatabasePath(name).delete() }
            runCatching { ctx.getDatabasePath("$name-wal").delete() }
            runCatching { ctx.getDatabasePath("$name-shm").delete() }
        }
    }

    private fun freshDbName(): String = "scanner_cache_test_${System.nanoTime()}.db".also { dbNames.add(it) }

    private fun open(name: String = freshDbName()): MemoryStore = MemoryStore.openForTest(ctx, name, key)

    private fun mem(
        id: String,
        status: String = "active",
        scope: String = "global",
        companions: List<String> = emptyList(),
        worlds: List<String> = emptyList()
    ) = MemoryRecord(
        memoryId = id, scope = scope, content = "content of $id", embeddingText = null, tagsJson = "[]",
        importance = 0, worldIds = worlds, roleplayCharacterIds = emptyList(),
        campaignIds = emptyList(), projectIds = emptyList(), protectionJson = null, modeHintsJson = "[]",
        createdAt = "2026-11-22T00:00:00Z", updatedAt = null, status = status,
        supersedes = null, companionIds = companions, entityRefs = emptyList(), changeLog = emptyList()
    )

    private fun companion(id: String) = CompanionRecord(
        companionId = id, currentName = id, essence = "e", relationshipNotes = null,
        memoryParticipation = "full", hardLimitsJson = "[]", appCharacterId = null,
        mirrorText = null, mirrorSyncedAt = null, modelAdaptationsJson = "[]",
        createdAt = "2026-11-22T00:00:00Z", status = "active", nameHistory = emptyList()
    )

    private fun tableExists(store: MemoryStore, table: String): Boolean =
        store.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)
        ).use { it.moveToFirst() }

    private fun rows(store: MemoryStore, table: String): Int =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", emptyArray<String>())
            .use { it.moveToFirst(); it.getInt(0) }

    private fun openRange(store: MemoryStore, rangeId: String) {
        store.writableDatabase.execSQL(
            "INSERT INTO analysis_run_state (run_id, chat_id, created_at) VALUES (?, 'chat', '2026-11-22T00:00:00Z')",
            arrayOf(rangeId)
        )
    }

    private val vector = byteArrayOf(1, 2, 3, 4)

    @Test
    fun upgradeFromV37_keepsEverythingAndAddsEmptyCacheTables() {
        val name = freshDbName()
        val before = open(name)
        before.insertMemory(mem("m-active"))
        before.insertMemory(mem("m-pending", status = "draft"))
        before.insertMemory(mem("m-archived", status = "archived"))
        before.upsertEmbedding("m-active", model, vector)
        openRange(before, "range-1")
        val prior = listOf("m-active", "m-pending", "m-archived").map { before.getMemory(it) }
        before.writableDatabase.execSQL("DROP TABLE ${ScannerComparisonCache.FILED_TABLE}")
        before.writableDatabase.execSQL("DROP TABLE ${ScannerComparisonCache.DRAFT_TABLE}")
        before.writableDatabase.version = 37
        before.close()

        val after = open(name)
        assertEquals(prior, listOf("m-active", "m-pending", "m-archived").map { after.getMemory(it) })
        assertEquals(1, after.embeddingsForMemories(listOf("m-active"), model).size)
        assertEquals(1, rows(after, "analysis_run_state"))
        assertTrue(tableExists(after, ScannerComparisonCache.FILED_TABLE))
        assertTrue(tableExists(after, ScannerComparisonCache.DRAFT_TABLE))
        assertEquals(0, ScannerComparisonCache.count(after.readableDatabase))
        val migration = after.readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE key = 'db_migration'", null
        ).use { it.moveToFirst(); it.getString(0) }
        assertEquals("38", migration)
    }

    @Test
    fun aFreshStoreHasTheCacheTables() {
        val store = open()
        assertTrue(tableExists(store, ScannerComparisonCache.FILED_TABLE))
        assertTrue(tableExists(store, ScannerComparisonCache.DRAFT_TABLE))
    }

    @Test
    fun aVectorIsReusedOnlyForTheSameTextAndModel() {
        val store = open()
        store.insertMemory(mem("m-archived", status = "archived"))
        val db = store.writableDatabase
        assertTrue(ScannerComparisonCache.putFiled(db, "m-archived", model, "fp-1", vector))
        assertEquals(
            setOf("m-archived"),
            ScannerComparisonCache.filedVectors(db, model, mapOf("m-archived" to "fp-1")).keys
        )
        assertTrue("changed text", ScannerComparisonCache.filedVectors(db, model, mapOf("m-archived" to "fp-2")).isEmpty())
        assertTrue("other model", ScannerComparisonCache.filedVectors(db, "other|doc-2", mapOf("m-archived" to "fp-1")).isEmpty())
        // The refreshed vector replaces the stale one.
        assertTrue(ScannerComparisonCache.putFiled(db, "m-archived", model, "fp-2", vector))
        assertEquals(1, rows(store, ScannerComparisonCache.FILED_TABLE))
    }

    @Test
    fun deletingAMemoryDeletesItsCachedVector() {
        val store = open()
        store.insertMemory(mem("m-pending", status = "draft"))
        store.insertMemory(mem("m-other", status = "archived"))
        val db = store.writableDatabase
        ScannerComparisonCache.putFiled(db, "m-pending", model, "fp", vector)
        ScannerComparisonCache.putFiled(db, "m-other", model, "fp", vector)
        store.deleteMemory("m-pending")
        assertEquals(1, rows(store, ScannerComparisonCache.FILED_TABLE))
        assertFalse("a deleted memory is not cached again", ScannerComparisonCache.putFiled(db, "m-pending", model, "fp", vector))
        assertEquals(1, rows(store, ScannerComparisonCache.FILED_TABLE))
    }

    @Test
    fun draftVectorsGoWithTheirScanRange() {
        val store = open()
        val db = store.writableDatabase
        openRange(store, "range-1")
        openRange(store, "range-2")
        assertTrue(ScannerComparisonCache.putDraft(db, "range-1", 1, model, "fp", vector))
        assertTrue(ScannerComparisonCache.putDraft(db, "range-2", 1, model, "fp", vector))
        assertEquals(setOf(1), ScannerComparisonCache.draftVectors(db, "range-1", model, mapOf(1 to "fp")).keys)
        assertTrue(ScannerComparisonCache.draftVectors(db, "range-1", model, mapOf(1 to "edited")).isEmpty())

        // A restarted range discards its drafts and their vectors.
        store.restartScanRange("range-1")
        assertTrue(ScannerComparisonCache.draftVectors(db, "range-1", model, mapOf(1 to "fp")).isEmpty())
        assertEquals(1, rows(store, ScannerComparisonCache.DRAFT_TABLE))

        // A cancelled or finished range's collection takes its vectors with it.
        db.delete("analysis_run_state", "run_id = ?", arrayOf("range-2"))
        assertEquals(0, rows(store, ScannerComparisonCache.DRAFT_TABLE))
        assertFalse(ScannerComparisonCache.putDraft(db, "range-2", 1, model, "fp", vector))
    }

    @Test
    fun theCacheNeverHoldsMoreThanItsLimit() {
        val store = open()
        val db = store.writableDatabase
        val total = ScannerComparisonCache.MAX_ENTRIES + 3
        db.beginTransaction()
        try {
            for (i in 0 until total) store.insertMemory(mem("m-$i", status = "archived"))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        for (i in 0 until total) {
            ScannerComparisonCache.putFiled(db, "m-$i", model, "fp", vector, now = i.toLong())
        }
        assertEquals(ScannerComparisonCache.MAX_ENTRIES, ScannerComparisonCache.count(db))
        // The least recently used went first.
        assertTrue(ScannerComparisonCache.filedVectors(db, model, mapOf("m-0" to "fp")).isEmpty())
        assertEquals(1, ScannerComparisonCache.filedVectors(db, model, mapOf("m-${total - 1}" to "fp")).size)
    }

    @Test
    fun scannerComparisonKeepsScopeAndStatusBoundaries() {
        val store = open()
        store.insertCompanion(companion("c-a"))
        store.insertCompanion(companion("c-b"))
        store.insertMemory(mem("global-active"))
        store.insertMemory(mem("global-pending", status = "draft"))
        store.insertMemory(mem("global-archived", status = "archived"))
        store.insertMemory(mem("global-superseded", status = "superseded"))
        store.insertMemory(mem("real-life", scope = "real_life", status = "archived"))
        store.insertMemory(mem("for-a", scope = "companion", companions = listOf("c-a"), status = "draft"))
        store.insertMemory(mem("for-b", scope = "companion", companions = listOf("c-b")))

        val withA = store.scannerComparableMemories(RetrievalScope("c-a", null, null, null))
            .associate { (m, status) -> m.memoryId to status }
        assertEquals(
            mapOf(
                "global-active" to "active", "global-pending" to "draft", "global-archived" to "archived",
                "real-life" to "archived", "for-a" to "draft"
            ),
            withA
        )
        val roleplay = store.scannerComparableMemories(RetrievalScope(null, "world-1", null, null))
            .map { it.first.memoryId }.toSet()
        assertEquals(setOf("global-active", "global-pending", "global-archived"), roleplay)

        // Chat retrieval is unchanged: Active only.
        assertEquals(
            setOf("global-active"),
            store.activeMemoriesForScope(RetrievalScope("c-a", null, null, null)).map { it.memoryId }.toSet()
        )
    }
}
