/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.memory.archivist.MemoryDependencies
import org.teslasoft.assistant.preferences.memory.archivist.MemoryDependency
import org.teslasoft.assistant.preferences.memory.archivist.ScannerDependencyChangedException

/**
 * Stage 6B.3 safe filing in the real encrypted database: a filing decision
 * that relied on a saved memory is checked again inside the filing
 * transaction, so a memory edited during the scan can never cause a stale
 * filing or a false completion; a user's discard of exactly that wording is
 * honored; and a linked pair of new suggestions point at each other.
 */
@RunWith(AndroidJUnit4::class)
class ScanFilingInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "scan-filing-instrumented-test-key".toByteArray()
    private val dbNames = ArrayList<String>()

    @Before fun loadNative() {
        System.loadLibrary("sqlcipher")
    }

    @After fun cleanup() {
        for (name in dbNames) {
            runCatching { ctx.getDatabasePath(name).delete() }
            runCatching { ctx.getDatabasePath("$name-wal").delete() }
            runCatching { ctx.getDatabasePath("$name-shm").delete() }
        }
    }

    private fun open(): MemoryStore =
        MemoryStore.openForTest(ctx, "scan_filing_test_${System.nanoTime()}.db".also { dbNames.add(it) }, key)

    private fun mem(id: String, status: String = "active", content: String = "content of $id") = MemoryRecord(
        memoryId = id, scope = "global", content = content, embeddingText = null, tagsJson = "[]",
        importance = 0, worldIds = emptyList(), roleplayCharacterIds = emptyList(),
        campaignIds = emptyList(), projectIds = emptyList(), protectionJson = null, modeHintsJson = "[]",
        createdAt = "2026-11-22T00:00:00Z", updatedAt = null, status = status,
        supersedes = null, companionIds = emptyList(), entityRefs = emptyList(), changeLog = emptyList()
    )

    private fun dependencyOn(store: MemoryStore, id: String): MemoryDependency {
        val record = store.getMemory(id)!!
        return MemoryDependencies.of(record, MemoryStore.draftRejectionHash(record.content))
    }

    /** A scanner range ready to file, as a rerun holds it. */
    private fun range(store: MemoryStore): ScanRangeRecord {
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
        return range
    }

    private fun file(
        store: MemoryStore,
        range: ScanRangeRecord,
        memories: List<MemoryRecord>,
        dependencies: List<MemoryDependency> = emptyList(),
        hints: Map<String, List<String>> = emptyMap()
    ) = store.commitScanRange(
        range, emptyList(), memories, emptyList(), emptyList(),
        relationshipHintsByMemoryId = hints, dependencies = dependencies
    )

    @Test fun aMemoryEditedAfterTheCheckStopsTheFilingAndWritesNothing() {
        val store = open()
        store.insertMemory(mem("covering", status = "draft", content = "Prefers window seats."))
        val dependency = dependencyOn(store, "covering")
        store.updateMemory(store.getMemory("covering")!!.copy(content = "Prefers aisle seats."), null)
        val range = range(store)
        try {
            file(store, range, listOf(mem("new-suggestion", status = "draft")), listOf(dependency))
            fail("a changed dependency must stop the filing")
        } catch (e: ScannerDependencyChangedException) {
            assertEquals(listOf("covering"), e.memoryIds)
        }
        assertNull(store.getMemory("new-suggestion"))
        // The range is still held, so the decision can be made again and filed.
        file(store, range, listOf(mem("new-suggestion", status = "draft")), listOf(dependencyOn(store, "covering")))
        assertEquals("draft", store.getMemory("new-suggestion")?.status)
    }

    @Test fun approvalKeepsTheDecisionButAPlainDeleteDoesNot() {
        val store = open()
        store.insertMemory(mem("approved", status = "draft", content = "Has two sisters."))
        store.insertMemory(mem("removed", content = "Lives in Leeds."))
        val approved = dependencyOn(store, "approved")
        val removed = dependencyOn(store, "removed")
        store.updateMemory(store.getMemory("approved")!!.copy(status = "active"), null)
        store.deleteMemory("removed")
        file(store, range(store), emptyList(), listOf(approved))
        try {
            file(store, range(store), emptyList(), listOf(removed))
            fail("a deleted memory that was not a discarded suggestion must stop the filing")
        } catch (_: ScannerDependencyChangedException) {
        }
    }

    @Test fun theUsersDiscardOfThatExactSuggestionIsHonored() {
        val store = open()
        // A scanner-made Pending suggestion, then discarded by the user.
        file(store, range(store), listOf(mem("pending", status = "draft", content = "Started learning Welsh.")))
        val dependency = dependencyOn(store, "pending")
        store.deleteMemory("pending")
        assertTrue(store.isRejectionHashRecorded(dependency.wordingHash))
        file(store, range(store), emptyList(), listOf(dependency))
    }

    @Test fun twoNewSuggestionsOfALinkedPairPointAtEachOther() {
        val store = open()
        file(
            store, range(store),
            listOf(mem("april", status = "draft"), mem("june", status = "draft")),
            hints = mapOf("april" to listOf("june"), "june" to listOf("april"))
        )
        assertEquals(listOf("june"), store.relationshipHintsForDraft("april").map { it.memoryId })
        assertEquals(listOf("april"), store.relationshipHintsForDraft("june").map { it.memoryId })
    }
}
