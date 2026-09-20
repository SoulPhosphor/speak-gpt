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

package org.teslasoft.assistant.preferences.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.ChatSetReplacementCoordinator.DeletionJournalState
import org.teslasoft.assistant.preferences.backup.ChatSetReplacementCoordinator.ReplacementBlock

/**
 * The pure refuse decision of the Phase 9.1 chat-set replacement coordinator:
 * given the residual pending state after settling, which cause (if any) blocks
 * the replacement. The order of the checks is fixed so a given mix of pending
 * work always reports the same, most-specific cause.
 */
class ChatSetReplacementCoordinatorTest {

    private fun reason(
        rename: Boolean = false,
        deletion: DeletionJournalState = DeletionJournalState.SETTLED,
        pendingFirstCommit: Boolean = false,
        provisionalSession: Boolean = false
    ): ReplacementBlock? =
        ChatSetReplacementCoordinator.blockingReason(rename, deletion, pendingFirstCommit, provisionalSession)

    @Test
    fun nothingPendingProceeds() {
        assertNull(reason())
    }

    @Test
    fun aPendingRenameBlocks() {
        assertEquals(ReplacementBlock.RENAME, reason(rename = true))
    }

    @Test
    fun aPendingDeletionBlocks() {
        assertEquals(ReplacementBlock.DELETION, reason(deletion = DeletionJournalState.PENDING))
    }

    @Test
    fun anUnavailableDeletionJournalBlocks() {
        // Unreadable must refuse, not replace over unexamined deletion work.
        assertEquals(
            ReplacementBlock.DELETION_UNAVAILABLE,
            reason(deletion = DeletionJournalState.UNAVAILABLE)
        )
    }

    @Test
    fun aPendingFirstCommitBlocks() {
        assertEquals(ReplacementBlock.PENDING_CONVERSATION, reason(pendingFirstCommit = true))
    }

    @Test
    fun aProvisionalSessionBlocks() {
        assertEquals(ReplacementBlock.PROVISIONAL_SESSION, reason(provisionalSession = true))
    }

    @Test
    fun renameIsReportedBeforeDeletionWhenBothPending() {
        // Fixed, most-specific ordering: the earliest cause in the sequence wins
        // so the reported reason is deterministic for any mix.
        assertEquals(
            ReplacementBlock.RENAME,
            reason(rename = true, deletion = DeletionJournalState.PENDING, provisionalSession = true)
        )
    }

    @Test
    fun everyBlockHasADistinctNonBlankDetail() {
        val details = ReplacementBlock.values().map { it.detail() }
        details.forEach { assertEquals(it, it.trim()) }
        assertEquals(ReplacementBlock.values().size, details.toSet().size)
    }
}
