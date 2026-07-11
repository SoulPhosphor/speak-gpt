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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion-state rules that guard against a partial streamed reply
 * masquerading as a finished one (Round 3). The load-bearing invariants:
 *
 *  - Legacy messages (no state) and the explicit "done" state are the ONLY
 *    complete states — historical replies must never suddenly look incomplete.
 *  - Any other non-blank value — including an unrecognized future one — is
 *    treated as NOT complete and preserved as-is, never silently upgraded.
 *  - A stale "streaming" row found at load reconciles to "interrupted"
 *    (idempotently); everything else is left untouched.
 */
class MessageCompletionStateTest {

    @Test
    fun legacyAbsentStateIsComplete() {
        // The safe default: a message written before this feature (no state)
        // and a blank value both read as a finished reply.
        assertTrue(MessageCompletionState.isComplete(null))
        assertTrue(MessageCompletionState.isComplete(""))
        assertTrue(MessageCompletionState.isComplete("   "))
    }

    @Test
    fun doneIsTheOnlyCompleteExplicitState() {
        assertTrue(MessageCompletionState.isComplete(MessageCompletionState.DONE))
    }

    @Test
    fun everyNonCompleteStateIsIncomplete() {
        assertFalse(MessageCompletionState.isComplete(MessageCompletionState.STREAMING))
        assertFalse(MessageCompletionState.isComplete(MessageCompletionState.STOPPED))
        assertFalse(MessageCompletionState.isComplete(MessageCompletionState.FAILED))
        assertFalse(MessageCompletionState.isComplete(MessageCompletionState.INTERRUPTED))
    }

    @Test
    fun unrecognizedStateIsTreatedAsIncomplete() {
        // A value written by some future build must be preserved and gated
        // conservatively, never assumed finished.
        assertFalse(MessageCompletionState.isComplete("some_future_state"))
        assertTrue(MessageCompletionState.isIncomplete("some_future_state"))
    }

    @Test
    fun isIncompleteIsTheInverseOfIsComplete() {
        val samples = listOf(
            null, "", MessageCompletionState.DONE, MessageCompletionState.STREAMING,
            MessageCompletionState.STOPPED, MessageCompletionState.FAILED,
            MessageCompletionState.INTERRUPTED, "weird"
        )
        for (s in samples) {
            assertEquals(!MessageCompletionState.isComplete(s), MessageCompletionState.isIncomplete(s))
        }
    }

    @Test
    fun onlyStreamingReconcilesAtLoad() {
        assertEquals(
            MessageCompletionState.INTERRUPTED,
            MessageCompletionState.reconcileOnLoad(MessageCompletionState.STREAMING)
        )
    }

    @Test
    fun terminalAndLegacyStatesNeverReconcile() {
        // null return = "leave this row alone".
        assertNull(MessageCompletionState.reconcileOnLoad(null))
        assertNull(MessageCompletionState.reconcileOnLoad(""))
        assertNull(MessageCompletionState.reconcileOnLoad(MessageCompletionState.DONE))
        assertNull(MessageCompletionState.reconcileOnLoad(MessageCompletionState.STOPPED))
        assertNull(MessageCompletionState.reconcileOnLoad(MessageCompletionState.FAILED))
        assertNull(MessageCompletionState.reconcileOnLoad(MessageCompletionState.INTERRUPTED))
        assertNull(MessageCompletionState.reconcileOnLoad("some_future_state"))
    }

    @Test
    fun reconcileIsIdempotent() {
        // Reconciling once yields "interrupted"; reconciling that result is a
        // no-op, so a repeatedly-loaded chat never churns.
        val once = MessageCompletionState.reconcileOnLoad(MessageCompletionState.STREAMING)
        assertEquals(MessageCompletionState.INTERRUPTED, once)
        assertNull(MessageCompletionState.reconcileOnLoad(once))
    }
}
