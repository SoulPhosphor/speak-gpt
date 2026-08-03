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
 * The approved "Archive this chat" pause semantics as pure per-row rules,
 * consulted by [MemoryStore] wherever a transcript row's review state
 * changes hands. The archive bookmark in this system is the set of rows
 * already marked processed (there is no stored watermark — eligibility is
 * a live query), so the ruled guarantees reduce to three row-level laws:
 *
 *  - A processed row is history: NO transition may touch it. Toggling the
 *    archive switch in either direction never erases, resets, advances,
 *    replaces, or otherwise alters it.
 *  - An unprocessed row only moves between 'pending' (review-eligible) and
 *    'excluded' (paused, do-not-review). Turning archiving back on returns
 *    the paused backlog to 'pending' — silently, with no prompt and no
 *    per-message choice. A companion opt-out (memory_participation 'none')
 *    is its own exclusion, not an archive pause: the archive toggle never
 *    re-queues a row whose companion currently blocks review.
 *  - A row advances to processed ONLY while it still carries the claim
 *    stamp of the run that read it. A released, reclaimed, or never-taken
 *    claim (an interrupted or partial run) can never mark unseen text.
 */
object TranscriptReviewTransitions {

    /**
     * Row transition for the "Archive this chat" toggle. Returns the row's
     * new review_status, or null when the toggle must leave the row alone.
     * [processed] outranks everything: a processed row is the bookmark and
     * never changes, whichever way the toggle moves.
     * [companionBlocksReview] is the row's companion's CURRENT
     * memory_participation == 'none': such a row is excluded by the
     * companion's own opt-out, so re-enabling archiving must not re-queue
     * it — only rows the archive pause excluded return to pending.
     */
    fun statusAfterArchiveToggle(
        archiveOff: Boolean,
        reviewStatus: String,
        processed: Boolean,
        companionBlocksReview: Boolean
    ): String? {
        if (processed || reviewStatus == "processed") return null
        return when {
            archiveOff && reviewStatus == "pending" -> "excluded"
            !archiveOff && reviewStatus == "excluded" && !companionBlocksReview -> "pending"
            else -> null
        }
    }

    /**
     * What the normal analysis may select: pending, never processed, not
     * claimed by another run, and tied to a chat. Processed rows can never
     * re-enter (repeated analysis is a no-op on them); excluded rows wait
     * for the toggle; claimed rows belong to the run holding the claim.
     */
    fun eligibleForAnalysis(
        reviewStatus: String,
        processedAt: String?,
        claimRunId: String?,
        chatId: String?
    ): Boolean =
        reviewStatus == "pending" && processedAt == null &&
            claimRunId == null && chatId != null

    /**
     * Whether a completing run may advance a row to processed: only while
     * the row still carries THAT run's claim stamp. A null claim (released
     * after interruption) or another run's claim means this run did not
     * read the row's current text, so the bookmark must not move past it.
     */
    fun advancesOnCompletion(claimRunId: String?, completingRunId: String): Boolean =
        claimRunId == completingRunId
}
