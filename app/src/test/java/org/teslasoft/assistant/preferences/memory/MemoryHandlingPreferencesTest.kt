package org.teslasoft.assistant.preferences.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memory Handling Preferences and Delete routing (memory plan Phase 2, D44). */
class MemoryHandlingPreferencesTest {

    private val on = MemoryHandlingPreferences(deleteMovesToArchived = true)
    private val off = MemoryHandlingPreferences(deleteMovesToArchived = false)

    @Test
    fun deleteMovesToArchivedIsOnByDefault() {
        assertTrue(MemoryHandlingPreferences.DEFAULT.deleteMovesToArchived)
    }

    @Test
    fun deleteMovesAnInUseMemoryToArchivedWhileTheSettingIsOn() {
        assertEquals(MemoryDeleteAction.MOVE_TO_ARCHIVED, MemoryDeleteRules.forDelete("active", on))
    }

    @Test
    fun deleteIsPermanentWhileTheSettingIsOff() {
        assertEquals(MemoryDeleteAction.CONFIRM_PERMANENT_DELETE, MemoryDeleteRules.forDelete("active", off))
    }

    @Test
    fun archivedSupersededAndPendingAreAlwaysDeletedPermanently() {
        for (status in listOf("archived", "superseded", "draft")) {
            assertEquals(status, MemoryDeleteAction.CONFIRM_PERMANENT_DELETE, MemoryDeleteRules.forDelete(status, on))
            assertEquals(status, MemoryDeleteAction.CONFIRM_PERMANENT_DELETE, MemoryDeleteRules.forDelete(status, off))
        }
    }

    private fun emptyExport(preferences: MemoryHandlingPreferences?) = MemoryStoreData(
        schemaVersion = "1.11.0", ownerProfile = null, companions = emptyList(),
        entities = emptyList(), memories = emptyList(), modes = emptyList(),
        directives = emptyList(), worlds = emptyList(), userPersonas = emptyList(),
        roleplayCharacters = emptyList(), archivistSettings = null, proposals = emptyList(),
        retrievalPolicyJson = null, transcripts = emptyList(),
        memoryHandlingPreferences = preferences
    )

    @Test
    fun theSettingSurvivesAJsonExportAndImport() {
        for (preferences in listOf(on, off)) {
            val parsed = MemorySeedCodec.parse(MemorySeedCodec.serialize(emptyExport(preferences)))
            assertEquals(preferences, parsed.memoryHandlingPreferences)
        }
    }

    @Test
    fun anOlderExportCarriesNoSettingSoTheDefaultsApply() {
        val json = JSONObject(MemorySeedCodec.serialize(emptyExport(off)))
        json.remove("memory_handling_preferences")
        assertNull(MemorySeedCodec.parse(json.toString()).memoryHandlingPreferences)
        assertFalse(MemorySeedCodec.serialize(emptyExport(null)).contains("memory_handling_preferences"))
    }

    @Test
    fun theSettingIsCarriedByEveryRowBasedMemoryBackup() {
        assertTrue(MemoryPortableRowFormat.memorySpecs.any { it.table == "memory_handling_preferences" })
        assertTrue("memory_handling_preferences" in MemoryPortableRowFormat.tablesAddedLater)
        assertTrue("memory_handling_preferences" in MemorySharedRestoreRowFormat.memoryTables)
        assertTrue("memory_handling_preferences" in MemorySharedRestoreRowFormat.tableNames)
    }

    @Test
    fun rowBackupsMadeBeforeTheSettingExistedStillRestore() {
        val shared = JSONObject(MemorySharedRestoreRowFormat.toJson(MemorySharedRestoreRowFormat.empty()))
        shared.getJSONObject("tables").remove("memory_handling_preferences")
        val parsedShared = MemorySharedRestoreRowFormat.parse(shared.toString())
        assertNotNull(parsedShared)
        assertTrue(parsedShared!!.tables.getValue("memory_handling_preferences").isEmpty())

        val emptyRows = MemoryPortableRows(
            MemoryPortableRowFormat.memorySpecs.associate { it.table to emptyList<Map<String, Any?>>() }
        )
        val portable = JSONObject(MemoryPortableRowFormat.toJson(MemoryPortableGroup.MEMORIES, emptyRows))
        portable.getJSONObject("tables").remove("memory_handling_preferences")
        val parsedPortable = MemoryPortableRowFormat.parse(portable.toString(), MemoryPortableGroup.MEMORIES)
        assertNotNull(parsedPortable)
        assertTrue(parsedPortable!!.tables.getValue("memory_handling_preferences").isEmpty())
    }
}
