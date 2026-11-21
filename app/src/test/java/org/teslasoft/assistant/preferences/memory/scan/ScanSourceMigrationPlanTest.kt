/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.AnalysisChatBookmark
import org.teslasoft.assistant.preferences.memory.LegacyTranscript

/**
 * The migration away from the transcript store (memory development plan
 * M.2, "Removing the duplicate transcript store", step 4 cases).
 */
class ScanSourceMigrationPlanTest {

    private fun turn(id: String?, at: String? = null) = JSONObject().apply {
        put("role", "user"); put("content", "never read")
        id?.let { put(ScanSourceMigrationPlan.LEGACY_MESSAGE_ID, it) }
        at?.let { put("at", it) }
    }

    private fun row(
        id: String, started: String, turns: List<JSONObject>, status: String = "pending",
        source: String = "live", companion: String? = "comp", project: String? = null, claim: String? = null
    ) = LegacyTranscript(
        transcriptId = id, chatId = "c", companionId = companion, worldId = null, roleplayCharacterId = null,
        userPersonaId = null, campaignId = null, projectId = project, source = source, startedAt = started,
        content = JSONArray(turns).toString(), claimRunId = claim, reviewStatus = status
    )

    private fun bookmark(lastId: String?, lastStarted: String?, skipped: List<String> = emptyList()) =
        AnalysisChatBookmark("c", lastStarted, lastId, skipped, false, "x")

    private fun messages(vararg ids: String, times: Boolean = false) =
        ids.mapIndexed { i, id -> ScanSourceMigrationPlan.Message(id, if (times) 1000L * (i + 1) else null, false) }

    private fun plan(
        messages: List<ScanSourceMigrationPlan.Message>,
        rows: List<LegacyTranscript>,
        legacy: AnalysisChatBookmark?,
        paused: Boolean = false
    ) = ScanSourceMigrationPlan.plan(messages, rows, legacy, paused) { if (it == "comp") "persona-1" else null }

    @Test fun aTurnWithAUuidBecomesTheBookmarkAndRecordsScope() {
        val rows = listOf(
            row("t1", "1", listOf(turn("m1"), turn("m2"))),
            row("t2", "2", listOf(turn("m3")))
        )
        val result = plan(messages("m1", "m2", "m3"), rows, bookmark("t1", "1"))
        assertFalse(result.needsFullRescan)
        assertEquals(1, result.bookmarkIndex)
        assertEquals("persona-1", result.scopes["m1"]!!.companionPersonaId)
        assertEquals("persona-1", result.scopes["m3"]!!.companionPersonaId)
    }

    @Test fun aTurnWithoutAUuidAndNoTimesNeedsACompleteRescan() {
        val rows = listOf(row("t1", "1", listOf(turn(null))))
        val result = plan(messages("m1", "m2"), rows, bookmark("t1", "1"))
        assertTrue(result.needsFullRescan)
        assertEquals(-1, result.bookmarkIndex)
    }

    @Test fun aTurnWithoutAUuidMapsByTimeOnlyWhenUnambiguous() {
        val rows = listOf(
            row("t1", "1", listOf(turn(null, "1970-01-01T00:00:02.500Z"))),
            row("t2", "2", listOf(turn(null, "1970-01-01T00:00:04.500Z")))
        )
        val clear = plan(messages("m1", "m2", "m3", "m4", times = true), rows, bookmark("t1", "1"))
        assertFalse(clear.needsFullRescan)
        assertEquals(1, clear.bookmarkIndex)
        // A message without a time makes it ambiguous.
        val noTime = messages("m1", "m2", "m3", "m4", times = true).toMutableList()
        noTime[3] = noTime[3].copy(timeMillis = null)
        assertTrue(plan(noTime, rows, bookmark("t1", "1")).needsFullRescan)
    }

    @Test fun aDeletedMessageIsSkippedAndNeverRecreated() {
        val rows = listOf(row("t1", "1", listOf(turn("gone"), turn("m2"))))
        val result = plan(messages("m2", "m3"), rows, bookmark("t1", "1"))
        assertEquals(0, result.bookmarkIndex)
        assertFalse("gone" in result.scopes)
    }

    @Test fun anEditedOrRegeneratedMessageKeepsItsUuidMapping() {
        // The transcript's text differs from the chat now; only the UUID matters.
        val rows = listOf(row("t1", "1", listOf(turn("m1"))))
        assertEquals(0, plan(messages("m1", "m2"), rows, bookmark("t1", "1")).bookmarkIndex)
    }

    @Test fun anImportedRowGivesNoScope() {
        val rows = listOf(row("t1", "1", listOf(turn("m1")), source = "imported"))
        assertTrue(plan(messages("m1"), rows, bookmark("t1", "1")).scopes.isEmpty())
    }

    @Test fun scopeThatChangedPartwayIsRecordedPerMessage() {
        val rows = listOf(
            row("t1", "1", listOf(turn("m1")), project = "p1"),
            row("t2", "2", listOf(turn("m2")), project = "p2")
        )
        val result = plan(messages("m1", "m2"), rows, null)
        assertEquals("p1", result.scopes["m1"]!!.projectId)
        assertEquals("p2", result.scopes["m2"]!!.projectId)
    }

    @Test fun anAlreadyRecordedScopeIsNotOverwritten() {
        val rows = listOf(row("t1", "1", listOf(turn("m1"))))
        val stamped = listOf(ScanSourceMigrationPlan.Message("m1", null, hasScope = true))
        assertTrue(plan(stamped, rows, null).scopes.isEmpty())
    }

    @Test fun anArchivePausedChatKeepsItsExcludedRowsWaiting() {
        val rows = listOf(
            row("t1", "1", listOf(turn("m1")), status = "processed"),
            row("t2", "2", listOf(turn("m2")), status = "excluded")
        )
        assertEquals(0, plan(messages("m1", "m2"), rows, null, paused = true).bookmarkIndex)
        assertEquals(1, plan(messages("m1", "m2"), rows, null, paused = false).bookmarkIndex)
    }

    @Test fun participationNoneRowsThatWereSkippedCountAsDone() {
        val rows = listOf(
            row("t1", "1", listOf(turn("m1"))),
            row("t2", "2", listOf(turn("m2")), companion = "silent")
        )
        val result = plan(messages("m1", "m2", "m3"), rows, bookmark("t1", "1", skipped = listOf("t2")))
        assertEquals(1, result.bookmarkIndex)
    }

    @Test fun aScanRunningAtUpgradeIsUnfinishedAndKeepsItsEnd() {
        val rows = listOf(
            row("t1", "1", listOf(turn("m1"))),
            row("t2", "2", listOf(turn("m2"), turn("m3")), claim = "run-live")
        )
        val result = plan(messages("m1", "m2", "m3"), rows, bookmark("t1", "1"))
        assertEquals(0, result.bookmarkIndex)
        assertEquals(2, result.claimedEnds["run-live"])
    }

    @Test fun scannedAfterUnscannedIsUnreliable() {
        val rows = listOf(
            row("t1", "1", listOf(turn("m2"))),
            row("t2", "2", listOf(turn("m1")))
        )
        assertTrue(plan(messages("m1", "m2"), rows, bookmark("t1", "1")).needsFullRescan)
    }

    @Test fun aChatWithNothingScannedHasNoBookmark() {
        val rows = listOf(row("t1", "1", listOf(turn("m1"))))
        val result = plan(messages("m1"), rows, bookmark(null, null))
        assertEquals(-1, result.bookmarkIndex)
        assertFalse(result.needsFullRescan)
        assertNull(result.claimedEnds["x"])
    }
}
