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

/**
 * Data control against real SQLCipher storage (memory plan Phase 2): the
 * version 36 migration, Memory Handling Preferences stored and defaulted,
 * archived memories kept out of search, and the setting carried by every
 * memory backup path. Runs on an arm64 device or emulator like the other
 * memory store tests.
 */
@RunWith(AndroidJUnit4::class)
class MemoryDataControlInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "data-control-instrumented-test-key".toByteArray()
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

    private fun freshDbName(): String = "data_control_test_${System.nanoTime()}.db".also { dbNames.add(it) }

    private fun open(name: String = freshDbName()): MemoryStore = MemoryStore.openForTest(ctx, name, key)

    private fun mem(id: String, status: String = "active") = MemoryRecord(
        memoryId = id, scope = "global", content = "content of $id", embeddingText = null, tagsJson = "[]",
        importance = 0, worldIds = emptyList(), roleplayCharacterIds = emptyList(),
        campaignIds = emptyList(), projectIds = emptyList(), protectionJson = null, modeHintsJson = "[]",
        createdAt = "2026-08-04T00:00:00Z", updatedAt = null, status = status,
        supersedes = null, companionIds = emptyList(), entityRefs = emptyList(), changeLog = emptyList()
    )

    private val off = MemoryHandlingPreferences(deleteMovesToArchived = false)

    @Test
    fun aNewStoreUsesTheDefaultAndSavesAChange() {
        val store = open()
        assertTrue(store.getMemoryHandlingPreferences().deleteMovesToArchived)
        store.setMemoryHandlingPreferences(off)
        assertEquals(off, store.getMemoryHandlingPreferences())
    }

    @Test
    fun upgradeFromV35_keepsMemoriesAndStartsWithTheDefault() {
        val name = freshDbName()
        val before = open(name)
        before.insertMemory(mem("m-old"))
        before.insertMemory(mem("m-archived", status = "archived"))
        val prior = listOf(before.getMemory("m-old"), before.getMemory("m-archived"))
        before.writableDatabase.execSQL("DROP TABLE memory_handling_preferences")
        before.writableDatabase.version = 35
        before.close()

        val after = open(name)
        assertEquals(prior, listOf(after.getMemory("m-old"), after.getMemory("m-archived")))
        assertEquals(MemoryHandlingPreferences.DEFAULT, after.getMemoryHandlingPreferences())
        val migration = after.readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE key = 'db_migration'", null
        ).use { it.moveToFirst(); it.getString(0) }
        assertEquals("36", migration)
    }

    @Test
    fun archivingTakesAMemoryOutOfUse() {
        val store = open()
        store.insertMemory(mem("m-1"))
        store.setMemoryStatus("m-1", "archived", null)
        assertEquals("archived", store.getMemory("m-1")!!.status)
        assertFalse(store.activeMemoriesForScope(RetrievalScope.NONE).any { it.memoryId == "m-1" })
        store.setMemoryStatus("m-1", "active", null)
        assertTrue(store.activeMemoriesForScope(RetrievalScope.NONE).any { it.memoryId == "m-1" })
    }

    @Test
    fun jsonExportCarriesTheSettingAndArchivedMemories() {
        val src = open()
        src.setMemoryHandlingPreferences(off)
        src.insertMemory(mem("m-archived", status = "archived"))
        val json = MemorySeedCodec.serialize(src.exportData())

        val restored = open()
        restored.importData(MemorySeedCodec.parse(json), overwriteSingletons = true)
        assertEquals(off, restored.getMemoryHandlingPreferences())
        assertEquals("archived", restored.getMemory("m-archived")!!.status)

        // A merge keeps the current user's own setting.
        val merged = open()
        merged.importData(MemorySeedCodec.parse(json), overwriteSingletons = false)
        assertEquals(MemoryHandlingPreferences.DEFAULT, merged.getMemoryHandlingPreferences())
    }

    @Test
    fun anExportWithoutASavedSettingRestoresTheDefault() {
        val src = open()
        assertNull(src.exportData().memoryHandlingPreferences)
        val restored = open()
        restored.importData(MemorySeedCodec.parse(MemorySeedCodec.serialize(src.exportData())), overwriteSingletons = true)
        assertEquals(MemoryHandlingPreferences.DEFAULT, restored.getMemoryHandlingPreferences())
    }

    @Test
    fun rowBackupsCarryTheSetting() {
        val src = open()
        src.setMemoryHandlingPreferences(off)
        val rows = src.exportPortableRows(MemoryPortableGroup.MEMORIES)
        assertEquals(1, rows.tables.getValue("memory_handling_preferences").size)
        val parsed = MemoryPortableRowFormat.parse(
            MemoryPortableRowFormat.toJson(MemoryPortableGroup.MEMORIES, rows), MemoryPortableGroup.MEMORIES
        )!!
        val dest = open()
        assertTrue(dest.replacePortableRows(MemoryPortableGroup.MEMORIES, parsed))
        assertEquals(off, dest.getMemoryHandlingPreferences())

        val shared = MemorySharedRestoreRowFormat.parse(
            MemorySharedRestoreRowFormat.toJson(src.exportSharedRestoreRows())
        )!!
        assertEquals(1, shared.tables.getValue("memory_handling_preferences").size)
    }

    @Test
    fun anOlderBackupDatabaseWithoutTheTableRestoresTheDefault() {
        val old = open()
        old.insertMemory(mem("m-1"))
        old.writableDatabase.execSQL("DROP TABLE memory_handling_preferences")
        val rows = MemoryPortableRowFormat.read(old.readableDatabase, MemoryPortableGroup.MEMORIES)
        assertTrue(rows.tables.getValue("memory_handling_preferences").isEmpty())

        val dest = open()
        dest.setMemoryHandlingPreferences(off)
        assertTrue(dest.replacePortableRows(MemoryPortableGroup.MEMORIES, rows))
        assertEquals(MemoryHandlingPreferences.DEFAULT, dest.getMemoryHandlingPreferences())
        assertEquals("content of m-1", dest.getMemory("m-1")!!.content)
    }
}
