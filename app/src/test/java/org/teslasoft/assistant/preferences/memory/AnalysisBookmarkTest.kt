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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisBookmarkTest {

    private data class Row(
        val id: String,
        val at: String,
        var legacyStatus: String = "pending"
    )

    private fun boundary(row: Row) = AnalysisBookmark.Boundary(row.at, row.id)

    @Test
    fun migrationStopsAtFirstPendingGapAndSnapshotsLaterTerminalRows() {
        val plan = AnalysisBookmark.planMigration(
            listOf(
                AnalysisBookmark.LegacyRow("t1", "1", "processed"),
                AnalysisBookmark.LegacyRow("t2", "2", "excluded"),
                AnalysisBookmark.LegacyRow("t3", "3", "pending"),
                AnalysisBookmark.LegacyRow("t4", "4", "processed"),
                AnalysisBookmark.LegacyRow("t5", "5", "excluded"),
                AnalysisBookmark.LegacyRow("t6", "6", "pending")
            )
        )

        assertEquals(AnalysisBookmark.Boundary("2", "t2"), plan.boundary)
        assertEquals(listOf("t4", "t5"), plan.skippedTranscriptIds)

        val rows = (1..6).map { Row("t$it", "$it") }
        assertEquals(
            listOf("t3"),
            AnalysisBookmark.eligibleRange(
                rows, plan.boundary, plan.skippedTranscriptIds.toSet(),
                { it.id }, ::boundary
            ).map { it.id }
        )

        val (advanced, remaining) = AnalysisBookmark.advanceAfterCommit(
            rows, AnalysisBookmark.Boundary("3", "t3"),
            plan.skippedTranscriptIds.toSet(), { it.id }, ::boundary
        )
        assertEquals(AnalysisBookmark.Boundary("5", "t5"), advanced)
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun pausedArchiveMigrationLeavesLegacyExcludedSpanAfterCompletedPrefix() {
        val plan = AnalysisBookmark.planMigration(
            listOf(
                AnalysisBookmark.LegacyRow("t40", "1", "processed"),
                AnalysisBookmark.LegacyRow("t41", "2", "excluded"),
                AnalysisBookmark.LegacyRow("t60", "3", "excluded")
            ),
            archivePaused = true
        )

        assertEquals(AnalysisBookmark.Boundary("1", "t40"), plan.boundary)
        assertTrue(plan.skippedTranscriptIds.isEmpty())
        val waiting = listOf(Row("t40", "1"), Row("t41", "2"), Row("t60", "3"))
        assertEquals(
            listOf("t41", "t60"),
            AnalysisBookmark.eligibleRange(
                waiting, plan.boundary, emptySet(), { it.id }, ::boundary
            ).map { it.id }
        )
    }

    @Test
    fun postMigrationEligibilityIgnoresLegacyStatusChanges() {
        val rows = listOf(
            Row("old", "1", "pending"),
            Row("new-a", "2", "processed"),
            Row("new-b", "3", "excluded")
        )
        val bookmark = AnalysisBookmark.Boundary("1", "old")

        fun eligible() = AnalysisBookmark.eligibleRange(
            rows, bookmark, emptySet(), { it.id }, ::boundary
        ).map { it.id }

        assertEquals(listOf("new-a", "new-b"), eligible())
        rows[0].legacyStatus = "processed"
        rows[1].legacyStatus = "excluded"
        rows[2].legacyStatus = "processed"
        assertEquals(listOf("new-a", "new-b"), eligible())
    }

    @Test
    fun lateArrivalIsOutsideFrozenSnapshotAndEligibleAfterSuccess() {
        val atStart = listOf(Row("t1", "1"), Row("t2", "2"))
        val frozen = AnalysisBookmark.eligibleRange(
            atStart, null, emptySet(), { it.id }, ::boundary
        )
        val arrivedLater = atStart + Row("t3", "3")

        assertEquals(listOf("t1", "t2"), frozen.map { it.id })
        assertEquals(
            listOf("t3"),
            AnalysisBookmark.eligibleRange(
                arrivedLater, boundary(frozen.last()), emptySet(),
                { it.id }, ::boundary
            ).map { it.id }
        )
    }

    @Test
    fun successfulFrozenRangeCommitsOnceAfterEveryChunk() = runBlocking {
        val visible = ArrayList<String>()
        val result = FrozenChatRangeExecutor.execute(
            chunks = listOf("a", "b", "c"),
            analyzeChunk = { "memory-$it" },
            commit = { staged -> visible.addAll(staged); staged.size }
        )

        assertEquals(3, result)
        assertEquals(listOf("memory-a", "memory-b", "memory-c"), visible)
    }

    @Test
    fun lateChunkFailureLeaksNoMemoryOrRuleAndDoesNotAdvanceBookmark() = runBlocking {
        val visibleMemories = ArrayList<String>()
        val visibleRules = ArrayList<String>()
        var bookmark: String? = null
        var commitCalled = false

        try {
            FrozenChatRangeExecutor.execute(
                chunks = listOf(1, 2, 3),
                analyzeChunk = { chunk ->
                    if (chunk == 3) error("late provider failure")
                    "memory-$chunk" to "rule-$chunk"
                },
                commit = { staged ->
                    commitCalled = true
                    visibleMemories.addAll(staged.map { it.first })
                    visibleRules.addAll(staged.map { it.second })
                    bookmark = "t3"
                }
            )
        } catch (_: IllegalStateException) {
            // Expected deterministic fake failure.
        }

        assertFalse(commitCalled)
        assertTrue(visibleMemories.isEmpty())
        assertTrue(visibleRules.isEmpty())
        assertEquals(null, bookmark)
    }

    @Test
    fun cancellationLeaksNothingAndDoesNotAdvanceBookmark() = runBlocking {
        val visible = ArrayList<String>()
        var bookmark: String? = null

        try {
            FrozenChatRangeExecutor.execute(
                chunks = listOf(1, 2),
                analyzeChunk = { chunk ->
                    if (chunk == 2) throw CancellationException("cancelled")
                    "memory-$chunk"
                },
                commit = { staged -> visible.addAll(staged); bookmark = "t2" }
            )
        } catch (_: CancellationException) {
            // Expected: production run cleanup owns cancellation.
        }

        assertTrue(visible.isEmpty())
        assertEquals(null, bookmark)
    }

    @Test
    fun multiChatRunCommitsSuccessfulChatAndLeavesFailedChatUntouched() = runBlocking {
        val visibleByChat = linkedMapOf<String, List<String>>()
        val bookmarks = linkedMapOf<String, String>()

        for (chat in listOf("success", "failure")) {
            try {
                FrozenChatRangeExecutor.execute(
                    chunks = listOf(1, 2),
                    analyzeChunk = { chunk ->
                        if (chat == "failure" && chunk == 2) error("provider failure")
                        "$chat-memory-$chunk"
                    },
                    commit = { staged ->
                        visibleByChat[chat] = staged
                        bookmarks[chat] = "t2"
                    }
                )
            } catch (_: IllegalStateException) {
                // The run continues to the next independent chat.
            }
        }

        assertEquals(listOf("success-memory-1", "success-memory-2"), visibleByChat["success"])
        assertEquals("t2", bookmarks["success"])
        assertFalse(visibleByChat.containsKey("failure"))
        assertFalse(bookmarks.containsKey("failure"))
    }
}
