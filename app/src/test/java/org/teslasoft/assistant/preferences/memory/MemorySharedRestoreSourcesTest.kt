package org.teslasoft.assistant.preferences.memory

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rollback staging rows written before memory sources existed (M.3). */
class MemorySharedRestoreSourcesTest {

    @Test
    fun stagedRowsWithoutTheSourcesTableReadAsHavingNone() {
        val json = JSONObject(MemorySharedRestoreRowFormat.toJson(MemorySharedRestoreRowFormat.empty()))
        json.getJSONObject("tables").remove("memory_sources")
        val parsed = MemorySharedRestoreRowFormat.parse(json.toString())
        assertNotNull(parsed)
        assertTrue(parsed!!.tables.getValue("memory_sources").isEmpty())
    }

    @Test
    fun anyOtherMissingTableIsStillRefused() {
        val json = JSONObject(MemorySharedRestoreRowFormat.toJson(MemorySharedRestoreRowFormat.empty()))
        json.getJSONObject("tables").remove("memories")
        assertNull(MemorySharedRestoreRowFormat.parse(json.toString()))
    }

    @Test
    fun theSourcesTableIsCarriedByEveryRowBasedMemoryBackup() {
        assertTrue(MemoryPortableRowFormat.memorySpecs.any { it.table == "memory_sources" })
        assertTrue("memory_sources" in MemorySharedRestoreRowFormat.memoryTables)
        assertTrue("memory_sources" in MemorySharedRestoreRowFormat.tableNames)
    }
}
