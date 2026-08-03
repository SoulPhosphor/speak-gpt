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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ruled "Archive this chat" pause semantics on the row rules that
 * MemoryStore applies (the archive bookmark is the set of processed rows;
 * there is no stored watermark):
 *
 *  - toggling archiving off never touches the bookmark;
 *  - messages accumulated while off stay stored, unprocessed, and eligible
 *    again the moment archiving is re-enabled — no prompt, no choice;
 *  - the next normal analysis takes the whole backlog exactly once;
 *  - the bookmark advances only through rows a run actually claimed and
 *    read, so interrupted or partial runs never advance past unseen text
 *    and repeated analysis never reprocesses a completed row.
 */
class TranscriptReviewTransitionsTest {

    /** A transcript row as the transitions see it. */
    private data class Row(
        val id: String,
        var reviewStatus: String,
        var processedAt: String? = null,
        var claimRunId: String? = null,
        val chatId: String? = "chat-1",
        val companionBlocksReview: Boolean = false
    )

    private fun Row.applyToggle(archiveOff: Boolean) {
        TranscriptReviewTransitions.statusAfterArchiveToggle(
            archiveOff, reviewStatus, processedAt != null, companionBlocksReview
        )?.let { reviewStatus = it }
    }

    private fun Row.eligible(): Boolean =
        TranscriptReviewTransitions.eligibleForAnalysis(
            reviewStatus, processedAt, claimRunId, chatId
        )

    /** 1. Turning archive off (or on) does not change the bookmark. */
    @Test
    fun togglingArchiveNeverTouchesProcessedRows() {
        assertNull(TranscriptReviewTransitions.statusAfterArchiveToggle(true, "processed", true, false))
        assertNull(TranscriptReviewTransitions.statusAfterArchiveToggle(false, "processed", true, false))
        // Belt: even an inconsistent row is protected by either marker alone.
        assertNull(TranscriptReviewTransitions.statusAfterArchiveToggle(true, "pending", true, false))
        assertNull(TranscriptReviewTransitions.statusAfterArchiveToggle(false, "excluded", true, false))
        assertNull(TranscriptReviewTransitions.statusAfterArchiveToggle(true, "processed", false, false))
        assertNull(TranscriptReviewTransitions.statusAfterArchiveToggle(false, "processed", false, false))
    }

    /** A companion opt-out is its own exclusion: an Archive off/on round
     *  trip must not re-queue rows whose companion blocks review. */
    @Test
    fun archiveToggleRoundTripNeverReenablesCompanionOptOutRows() {
        val optOutRow = Row("t-1", "excluded", companionBlocksReview = true)
        val pausedRow = Row("t-2", "excluded", companionBlocksReview = false)

        optOutRow.applyToggle(archiveOff = true)
        pausedRow.applyToggle(archiveOff = true)
        optOutRow.applyToggle(archiveOff = false)
        pausedRow.applyToggle(archiveOff = false)

        // Only the row the archive pause excluded returns to the queue.
        assertEquals("excluded", optOutRow.reviewStatus)
        assertFalse(optOutRow.eligible())
        assertEquals("pending", pausedRow.reviewStatus)
        assertTrue(pausedRow.eligible())

        // Direct check of the re-include transition as well.
        assertNull(TranscriptReviewTransitions.statusAfterArchiveToggle(false, "excluded", false, true))
        assertEquals("pending",
            TranscriptReviewTransitions.statusAfterArchiveToggle(false, "excluded", false, false))
    }

    /** 2. Messages accumulated while off remain unprocessed and eligible. */
    @Test
    fun rowsCapturedWhileOffStayUnprocessedAndBecomeEligibleOnReenable() {
        // Captured while archiving was off: excluded, never processed.
        val row = Row("t-1", "excluded")
        // Paused: the normal analysis must not see it...
        assertFalse(row.eligible())
        // ...and nothing has advanced past it.
        assertNull(row.processedAt)
        // Re-enable: silently back to pending — no prompt path exists here,
        // the transition is the entire mechanism.
        row.applyToggle(archiveOff = false)
        assertEquals("pending", row.reviewStatus)
        assertTrue(row.eligible())
    }

    /** 3. Re-enabling and running analysis processes the entire backlog. */
    @Test
    fun nextNormalAnalysisTakesTheWholeBacklogExactlyOnce() {
        val processedBefore = Row("t-0", "processed", processedAt = "2026-08-01T00:00:00Z")
        val pausedOldPending = Row("t-1", "pending")   // captured while on
        val capturedWhileOff1 = Row("t-2", "excluded") // captured while off
        val capturedWhileOff2 = Row("t-3", "excluded")
        val rows = listOf(processedBefore, pausedOldPending, capturedWhileOff1, capturedWhileOff2)

        // Toggle off pauses the unprocessed pending row too.
        rows.forEach { it.applyToggle(archiveOff = true) }
        assertEquals("excluded", pausedOldPending.reviewStatus)
        assertTrue(rows.none { it.eligible() })
        // The bookmark did not move.
        assertEquals("processed", processedBefore.reviewStatus)

        // Toggle back on: every unprocessed row rejoins the queue.
        rows.forEach { it.applyToggle(archiveOff = false) }
        val backlog = rows.filter { it.eligible() }
        assertEquals(listOf("t-1", "t-2", "t-3"), backlog.map { it.id })

        // The normal run claims and completes the backlog.
        val runId = "run-1"
        backlog.forEach { it.claimRunId = runId }
        backlog.forEach {
            if (TranscriptReviewTransitions.advancesOnCompletion(it.claimRunId, runId)) {
                it.reviewStatus = "processed"
                it.processedAt = "2026-08-03T00:00:00Z"
                it.claimRunId = null
            }
        }
        // Exactly once: a repeat pass finds nothing left.
        assertTrue(rows.none { it.eligible() })
    }

    /** 4. The bookmark advances only through fully processed messages. */
    @Test
    fun bookmarkAdvancesOnlyThroughRowsTheRunClaimedAndRead() {
        assertTrue(TranscriptReviewTransitions.advancesOnCompletion("run-1", "run-1"))
        // Never claimed — the run never read it.
        assertFalse(TranscriptReviewTransitions.advancesOnCompletion(null, "run-1"))
        // Claimed by a different run — not this run's to advance.
        assertFalse(TranscriptReviewTransitions.advancesOnCompletion("run-2", "run-1"))
    }

    /** 5. Interrupted or partial processing does not falsely advance. */
    @Test
    fun interruptedRunLeavesUnfinishedRowsEligibleAndUnadvanced() {
        val finished = Row("t-1", "pending", claimRunId = "run-1")
        val unfinished = Row("t-2", "pending", claimRunId = "run-1")

        // The run completed t-1, then was interrupted before t-2.
        assertTrue(TranscriptReviewTransitions.advancesOnCompletion(finished.claimRunId, "run-1"))
        finished.reviewStatus = "processed"
        finished.processedAt = "2026-08-03T00:00:00Z"
        finished.claimRunId = null

        // Recovery releases the dead run's claim; t-2 must not have advanced
        // and must be waiting for the next run.
        unfinished.claimRunId = null
        assertNull(unfinished.processedAt)
        assertEquals("pending", unfinished.reviewStatus)
        assertTrue(unfinished.eligible())
        // And the released row can no longer be advanced under the dead run.
        assertFalse(TranscriptReviewTransitions.advancesOnCompletion(unfinished.claimRunId, "run-1"))
    }

    /** 6. Repeated analysis does not duplicate processing. */
    @Test
    fun processedRowsNeverReenterTheQueue() {
        val done = Row("t-1", "processed", processedAt = "2026-08-01T00:00:00Z")
        assertFalse(done.eligible())
        // Toggling archiving off and on around it changes nothing.
        done.applyToggle(archiveOff = true)
        done.applyToggle(archiveOff = false)
        assertEquals("processed", done.reviewStatus)
        assertFalse(done.eligible())
    }

    /** Rows claimed by a live run are not offered to a second selection. */
    @Test
    fun claimedRowsAreNotEligibleForAnotherRun() {
        assertFalse(Row("t-1", "pending", claimRunId = "run-1").eligible())
    }

    /** A row without a chat is never analyzed. */
    @Test
    fun rowsWithoutAChatAreNotEligible() {
        assertFalse(Row("t-1", "pending", chatId = null).eligible())
    }
}
