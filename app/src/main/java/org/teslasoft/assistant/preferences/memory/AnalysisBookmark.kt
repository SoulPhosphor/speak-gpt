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

/**
 * Pure ordering and cutover rules for the Stage-B per-chat analysis bookmark.
 *
 * The permanent runtime boundary is a `(started_at, transcript_id)` pair. A
 * transcript id is only a tie-breaker; it is never treated as chronological by
 * itself. [skippedTranscriptIds] is a compact per-chat snapshot of terminal
 * rows that were found *after* the first pending migration gap (and of later
 * rows intentionally excluded while an earlier gap still exists). Keeping that
 * snapshot on the bookmark row lets the post-cutover runtime skip terminal
 * history without consulting mutable legacy `review_status` values.
 */
object AnalysisBookmark {

    data class Boundary(val startedAt: String?, val transcriptId: String)

    data class LegacyRow(
        val transcriptId: String,
        val startedAt: String?,
        val reviewStatus: String
    ) {
        val boundary: Boundary get() = Boundary(startedAt, transcriptId)
    }

    data class MigrationPlan(
        val boundary: Boundary?,
        val skippedTranscriptIds: List<String>
    )

    /** Null timestamps follow SQLite's ascending order and sort before text. */
    val boundaryComparator: Comparator<Boundary> =
        compareBy<Boundary>({ it.startedAt ?: "" }, { it.transcriptId })

    fun compare(a: Boundary, b: Boundary): Int = boundaryComparator.compare(a, b)

    fun isAfter(candidate: Boundary, boundary: Boundary?): Boolean =
        boundary == null || compare(candidate, boundary) > 0

    /**
     * Initialize through only the contiguous processed/excluded prefix. Rows
     * after the first pending gap are snapshotted as migration skips when they
     * were already terminal; their legacy columns are never consulted again.
     */
    fun planMigration(rows: List<LegacyRow>, archivePaused: Boolean = false): MigrationPlan {
        val ordered = rows.sortedWith(compareBy({ it.startedAt ?: "" }, { it.transcriptId }))
        var boundary: Boundary? = null
        var foundPendingGap = false
        val skipped = ArrayList<String>()
        for (row in ordered) {
            // The old Archive toggle represented its reversible pause by
            // writing `excluded`. If that chat is still paused at cutover,
            // those rows are waiting material, not terminal history. A chat
            // that is not paused keeps the contract's normal processed /
            // excluded terminal-prefix rule.
            val terminal = row.reviewStatus == "processed" ||
                (!archivePaused && row.reviewStatus == "excluded")
            if (!foundPendingGap && terminal) {
                boundary = row.boundary
            } else {
                if (!terminal) foundPendingGap = true
                if (foundPendingGap && terminal) skipped.add(row.transcriptId)
            }
        }
        return MigrationPlan(boundary, skipped)
    }

    /**
     * Runtime eligibility after cutover. Legacy review columns are absent from
     * this function by design. The next frozen range starts after [boundary]
     * and stops before the first snapshotted skip. A later range becomes
     * reachable after a successful commit advances over that skip.
     */
    fun <T> eligibleRange(
        rows: List<T>,
        boundary: Boundary?,
        skippedTranscriptIds: Set<String>,
        idOf: (T) -> String,
        boundaryOf: (T) -> Boundary
    ): List<T> {
        val out = ArrayList<T>()
        for (row in rows.sortedWith { a, b -> compare(boundaryOf(a), boundaryOf(b)) }) {
            if (!isAfter(boundaryOf(row), boundary)) continue
            if (idOf(row) in skippedTranscriptIds) break
            out.add(row)
        }
        return out
    }

    /**
     * Advance a successfully committed frozen end through immediately
     * following snapshotted skips. Returns the new boundary and the remaining
     * skip ids. This never reads legacy review state.
     */
    fun <T> advanceAfterCommit(
        rows: List<T>,
        frozenEnd: Boundary,
        skippedTranscriptIds: Set<String>,
        idOf: (T) -> String,
        boundaryOf: (T) -> Boundary
    ): Pair<Boundary, Set<String>> {
        var advanced = frozenEnd
        val remaining = skippedTranscriptIds.toMutableSet()
        for (row in rows.sortedWith { a, b -> compare(boundaryOf(a), boundaryOf(b)) }) {
            val rowBoundary = boundaryOf(row)
            if (!isAfter(rowBoundary, advanced)) continue
            if (idOf(row) !in remaining) break
            remaining.remove(idOf(row))
            advanced = rowBoundary
        }
        return advanced to remaining
    }
}

/**
 * A small testable execution seam for one frozen chat range. Every chunk is
 * analyzed into memory only; [commit] is called exactly once and only after all
 * chunks succeed. Cancellation is deliberately rethrown so the run's existing
 * cancellation/recovery path can release claims without filing the range.
 */
object FrozenChatRangeExecutor {

    suspend fun <Chunk, Output, CommitResult> execute(
        chunks: List<Chunk>,
        analyzeChunk: suspend (Chunk) -> Output,
        commit: (List<Output>) -> CommitResult
    ): CommitResult {
        val staged = ArrayList<Output>(chunks.size)
        for (chunk in chunks) staged.add(analyzeChunk(chunk))
        return commit(staged)
    }
}
