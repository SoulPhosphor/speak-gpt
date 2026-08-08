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
import org.junit.Test

/**
 * Pins the cancellation truth table (owner ruling, Aug 8 2026): only a real Stop
 * counts as a user stop; every other cancellation is a visible failure so a
 * turn is never left with a saved message and no explanation.
 */
class CancellationClassificationTest {

    private fun classify(userStop: Boolean, destroying: Boolean, replyStarted: Boolean) =
        MessageCompletionState.classifyCancellation(userStop, destroying, replyStarted)

    @Test fun realStopIsAUserStopRegardlessOfEverythingElse() {
        // A user stop wins over teardown and over whether a reply had started.
        for (destroying in listOf(false, true)) {
            for (started in listOf(false, true)) {
                assertEquals(
                    MessageCompletionState.STOPPED to MessageCompletionState.DETAIL_USER_STOP,
                    classify(userStop = true, destroying = destroying, replyStarted = started)
                )
            }
        }
    }

    @Test fun cancellationWithoutAUserStopIsNeverAUserStop() {
        // The core rule: a cancelled coroutine that the user did not stop must
        // not be recorded as a user stop.
        val (state, detail) = classify(userStop = false, destroying = false, replyStarted = true)
        assertEquals(MessageCompletionState.FAILED, state)
        assertEquals(MessageCompletionState.DETAIL_UNKNOWN_END, detail)
    }

    @Test fun noReplyStartedIsStartFailed() {
        assertEquals(
            MessageCompletionState.FAILED to MessageCompletionState.DETAIL_START_FAILED,
            classify(userStop = false, destroying = false, replyStarted = false)
        )
        // Even during teardown, if nothing streamed the reply never started.
        assertEquals(
            MessageCompletionState.FAILED to MessageCompletionState.DETAIL_START_FAILED,
            classify(userStop = false, destroying = true, replyStarted = false)
        )
    }

    @Test fun teardownMidReplyIsAppInterrupted() {
        assertEquals(
            MessageCompletionState.INTERRUPTED to MessageCompletionState.DETAIL_SCREEN_CLOSED,
            classify(userStop = false, destroying = true, replyStarted = true)
        )
    }

    @Test fun everyNonUserOutcomeIsAVisibleFailureState() {
        // Nothing but the user stop is allowed to resolve to STOPPED — that is
        // what guarantees a non-user cancellation always leaves a visible bubble.
        val nonUser = listOf(
            classify(false, false, false),
            classify(false, false, true),
            classify(false, true, false),
            classify(false, true, true)
        )
        for ((state, _) in nonUser) {
            assertEquals(false, state == MessageCompletionState.STOPPED)
            assertEquals(true, MessageCompletionState.isIncomplete(state))
        }
    }
}
