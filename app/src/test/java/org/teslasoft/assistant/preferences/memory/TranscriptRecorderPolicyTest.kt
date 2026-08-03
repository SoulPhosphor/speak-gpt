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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the live-capture consent contract: storage and injection are
 * independent — the policy function has no injection-switch parameter, so
 * "Use memory in this chat" cannot influence whether a captured turn is
 * review-eligible. "Archive this chat" off PAUSES archiving (the ruled
 * Feature 1A semantics): the turn is still captured, marked excluded, so
 * messages sent while the toggle is off stay stored, unprocessed, and
 * recoverable when archiving is turned back on. Capture is never skipped
 * for the archive toggle and there is no prompt of any kind.
 */
class TranscriptRecorderPolicyTest {

    @Test
    fun archiveOffMarksTheCapturedTurnExcludedNotSkipped() {
        // Whatever the companion participation, an archive-off turn is
        // captured excluded — paused, not discarded.
        assertTrue(TranscriptRecorder.liveCaptureMarkedExcluded(true, "full"))
        assertTrue(TranscriptRecorder.liveCaptureMarkedExcluded(true, "global_only"))
        assertTrue(TranscriptRecorder.liveCaptureMarkedExcluded(true, "none"))
        assertTrue(TranscriptRecorder.liveCaptureMarkedExcluded(true, ""))
    }

    @Test
    fun withArchivingOnOnlyNoneParticipationExcludesACapturedTurn() {
        assertTrue(TranscriptRecorder.liveCaptureMarkedExcluded(false, "none"))
        assertFalse(TranscriptRecorder.liveCaptureMarkedExcluded(false, "full"))
        assertFalse(TranscriptRecorder.liveCaptureMarkedExcluded(false, "global_only"))
        assertFalse(TranscriptRecorder.liveCaptureMarkedExcluded(false, ""))
    }
}
