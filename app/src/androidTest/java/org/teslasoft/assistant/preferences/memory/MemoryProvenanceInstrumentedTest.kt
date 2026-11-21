package org.teslasoft.assistant.preferences.memory

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory provenance against real SQLCipher storage (development plan M.3):
 * the version 35 migration, sources recorded and kept through Pending and
 * edits, nothing carried by Save & Supersede or Save & Replace, chat renames,
 * and every backup path.
 * Runs on an arm64 device or emulator like the other memory store tests.
 */
@RunWith(AndroidJUnit4::class)
class MemoryProvenanceInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "provenance-instrumented-test-key".toByteArray()
    private val dbNames = ArrayList<String>()

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

    private fun freshDbName(): String = "provenance_test_${System.nanoTime()}.db".also { dbNames.add(it) }

    private fun open(name: String = freshDbName()): MemoryStore = MemoryStore.openForTest(ctx, name, key)

    private fun mem(id: String, status: String = "active", content: String = "content of $id") = MemoryRecord(
        memoryId = id, scope = "global", content = content, embeddingText = null, tagsJson = "[]",
        importance = 0, worldIds = emptyList(), roleplayCharacterIds = emptyList(),
        campaignIds = emptyList(), projectIds = emptyList(), protectionJson = null, modeHintsJson = "[]",
        createdAt = "2026-08-04T00:00:00Z", updatedAt = null, status = status,
        supersedes = null, companionIds = emptyList(), entityRefs = emptyList(), changeLog = emptyList()
    )

    private fun conversation(memoryId: String, chatId: String = "chat-1") =
        MemorySourceRules.conversationOnly("src-$memoryId-$chatId", memoryId, chatId, "2026-11-16T00:00:00Z")

    /** Files [draft] the way the Memory Scanner does, with its sources. */
    private fun fileDraft(store: MemoryStore, draft: MemoryRecord, sources: List<MemorySource>) {
        fileLikeTheScanner(store, listOf(draft), sourcesByMemoryId = mapOf(draft.memoryId to sources))
    }

    /** Files outputs the way a Memory Scanner rerun range does. */
    private fun fileLikeTheScanner(
        store: MemoryStore,
        memories: List<MemoryRecord>,
        relationshipHintsByMemoryId: Map<String, List<String>> = emptyMap(),
        sourcesByMemoryId: Map<String, List<MemorySource>> = emptyMap()
    ) {
        val runId = MemoryStore.newId("run-")
        val range = ScanRangeRecord(
            MemoryStore.newId("range-"), runId, "source-chat", 0, ScanRangeRecord.KIND_RERUN,
            emptyList(), emptyList()
        )
        store.beginScanRun(
            ArchivistRunRecord(
                runId = runId, startedAt = MemoryStore.nowIso(), finishedAt = null, status = "running",
                chatIdsJson = "[]", transcriptIdsJson = "[]", memoryIdsJson = "[]", ruleIdsJson = "[]",
                foundCount = 0, failedChatIdsJson = "[]", error = null
            ),
            listOf(range)
        )
        store.commitScanRange(
            range, emptyList(), memories, emptyList(), emptyList(),
            relationshipHintsByMemoryId = relationshipHintsByMemoryId,
            sourcesByMemoryId = sourcesByMemoryId
        )
    }


    private fun sourceRowCount(store: MemoryStore): Int =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM memory_sources", null)
            .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun upgradeFromV34_keepsMemoriesUnchangedWithNoSources() {
        val name = freshDbName()
        val before = open(name)
        before.insertMemory(mem("m-old"))
        val prior = before.getMemory("m-old")!!
        before.writableDatabase.execSQL("DROP TABLE memory_sources")
        before.writableDatabase.version = 34
        before.close()

        val after = open(name)
        val now = after.getMemory("m-old")!!
        assertEquals(prior, now)
        assertTrue(after.memorySources("m-old").isEmpty())
        assertEquals(0, sourceRowCount(after))
        val migration = after.readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE key = 'db_migration'", null
        ).use { it.moveToFirst(); it.getString(0) }
        assertEquals("35", migration)
    }

    @Test
    fun aMemoryTheUserWritesIsDatedAtCreation() {
        val store = open()
        store.insertMemory(mem("m-own"))
        val source = store.memorySources("m-own").single()
        assertEquals(MemorySource.KIND_USER_ENTRY, source.kind)
        assertEquals("2026-08-04T00:00:00Z", source.statedAt)
        assertEquals(null, source.speaker)
        // Editing never changes the date.
        store.updateMemory(store.getMemory("m-own")!!.copy(content = "edited"), null)
        assertEquals(listOf(source), store.memorySources("m-own"))
    }

    @Test
    fun scannerSourcesAreKeptThroughPendingAcceptAndEdit() {
        val store = open()
        val src = conversation("m-draft")
        fileDraft(store, mem("m-draft", status = "draft"), listOf(src))
        assertEquals(listOf(src), store.memorySources("m-draft"))
        store.setMemoryStatus("m-draft", "active", null)
        store.updateMemory(store.getMemory("m-draft")!!.copy(content = "edited text"), null)
        assertEquals(listOf(src), store.memorySources("m-draft"))
    }

    @Test
    fun supersedeAndReplaceCarryNoOldSources() {
        val store = open()
        fileDraft(store, mem("m-a", status = "draft"), listOf(conversation("m-a", "chat-a")))
        store.setMemoryStatus("m-a", "active", null)
        fileDraft(store, mem("m-b", status = "draft"), listOf(conversation("m-b", "chat-b")))
        store.setMemoryStatus("m-b", "active", null)
        fileDraft(store, mem("m-new", status = "draft", content = "updated"), listOf(conversation("m-new", "chat-new")))
        store.resolveSupersede("m-new", listOf("m-a"))
        assertEquals(listOf("chat-new"), store.memorySources("m-new").map { it.chatId })
        // The superseded memory keeps its own source as history.
        assertEquals(listOf("chat-a"), store.memorySources("m-a").map { it.chatId })

        fileDraft(store, mem("m-new2", status = "draft", content = "replacement"), listOf(conversation("m-new2", "chat-c")))
        store.resolveReplace("m-new2", listOf("m-b"))
        assertEquals(null, store.getMemory("m-b"))
        assertEquals(listOf("chat-c"), store.memorySources("m-new2").map { it.chatId })
    }

    @Test
    fun deletingAMemoryRemovesItsSourcesOnly() {
        val store = open()
        fileDraft(store, mem("m-1", status = "draft"), listOf(conversation("m-1")))
        fileDraft(store, mem("m-2", status = "draft"), listOf(conversation("m-2")))
        store.deleteMemory("m-1")
        assertEquals(1, sourceRowCount(store))
    }

    @Test
    fun aChatRenameMovesSourceReferences() {
        val store = open()
        fileDraft(store, mem("m-1", status = "draft"), listOf(conversation("m-1", "old-hash")))
        store.repointChat("old-hash", "new-id")
        assertEquals("new-id", store.memorySources("m-1").single().chatId)
    }

    @Test
    fun sourceChangesNeverTouchStoredVectors() {
        val store = open()
        fileDraft(store, mem("m-1", status = "draft"), listOf(conversation("m-1")))
        store.setMemoryStatus("m-1", "active", null)
        store.writableDatabase.insert("embeddings", null, ContentValues().apply {
            put("memory_id", "m-1"); put("embedding_model", "model|doc")
            put("vector", byteArrayOf(1, 2, 3)); put("embedded_at", "2026-11-16T00:00:00Z")
        })
        store.repointChat("chat-1", "chat-2")
        val vectors = store.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM embeddings WHERE memory_id = 'm-1'", null
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, vectors)
    }

    private fun newId(): String = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)

    @Test
    fun memoryBackupRoundTripsSourcesAndOlderBackupsRestoreWithout() {
        val drafted = newId()
        val own = newId()
        val src = open()
        fileDraft(src, mem(drafted, status = "draft"), listOf(conversation(drafted)))
        src.insertMemory(mem(own))
        val json = MemorySeedCodec.serialize(src.exportData())

        val dest = open()
        dest.importData(MemorySeedCodec.parse(json), overwriteSingletons = true)
        assertEquals(src.memorySources(drafted), dest.memorySources(drafted))
        assertEquals(MemorySource.KIND_USER_ENTRY, dest.memorySources(own).single().kind)

        val older = JSONObject(json)
        val memories = older.getJSONArray("memories")
        for (i in 0 until memories.length()) memories.getJSONObject(i).remove("sources")
        val oldDest = open()
        oldDest.importData(MemorySeedCodec.parse(older.toString()), overwriteSingletons = true)
        assertEquals("content of $drafted", oldDest.getMemory(drafted)!!.content)
        assertEquals(0, sourceRowCount(oldDest))
    }

    @Test
    fun aRemappedImportKeepsItsSourcesUnderTheNewId() {
        val id = newId()
        val src = open()
        fileDraft(src, mem(id, status = "draft", content = "the backup's memory"), listOf(conversation(id)))
        val json = MemorySeedCodec.serialize(src.exportData())

        // The destination holds a different memory (another birth) under the same id.
        val dest = open()
        dest.insertMemory(mem(id, content = "a different memory").copy(createdAt = "2025-01-01T00:00:00Z"))
        dest.importData(MemorySeedCodec.parse(json), overwriteSingletons = false)
        val imported = dest.exportData().memories.single { it.content == "the backup's memory" }
        assertNotEquals(id, imported.memoryId)
        assertEquals(listOf("chat-1"), dest.memorySources(imported.memoryId).map { it.chatId })
        assertEquals(MemorySource.KIND_USER_ENTRY, dest.memorySources(id).single().kind)
    }

    @Test
    fun portableAndReadableRowsRoundTripSources() {
        val src = open()
        fileDraft(src, mem("m-1", status = "draft"), listOf(conversation("m-1")))
        val rows = src.exportPortableRows(MemoryPortableGroup.MEMORIES)
        assertEquals(1, rows.tables.getValue("memory_sources").size)
        val parsed = MemoryPortableRowFormat.parse(
            MemoryPortableRowFormat.toJson(MemoryPortableGroup.MEMORIES, rows), MemoryPortableGroup.MEMORIES
        )!!

        val dest = open()
        assertTrue(dest.replacePortableRows(MemoryPortableGroup.MEMORIES, parsed))
        assertEquals(src.memorySources("m-1"), dest.memorySources("m-1"))

        // Shared restore staging carries them too.
        val shared = MemorySharedRestoreRowFormat.parse(
            MemorySharedRestoreRowFormat.toJson(src.exportSharedRestoreRows())
        )!!
        assertEquals(1, shared.tables.getValue("memory_sources").size)
    }

    @Test
    fun anOlderBackupDatabaseWithoutTheTableReadsAsNoSources() {
        val name = freshDbName()
        val old = open(name)
        old.insertMemory(mem("m-1"))
        old.writableDatabase.execSQL("DROP TABLE memory_sources")
        val rows = MemoryPortableRowFormat.read(old.readableDatabase, MemoryPortableGroup.MEMORIES)
        assertTrue(rows.tables.getValue("memory_sources").isEmpty())
        assertEquals(1, rows.tables.getValue("memories").size)

        val dest = open()
        assertTrue(dest.replacePortableRows(MemoryPortableGroup.MEMORIES, rows))
        assertEquals("content of m-1", dest.getMemory("m-1")!!.content)
        assertTrue(dest.memorySources("m-1").isEmpty())
    }
}
