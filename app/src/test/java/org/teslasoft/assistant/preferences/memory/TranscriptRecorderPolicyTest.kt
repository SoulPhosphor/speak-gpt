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
 * Pins the Step 1.1 capture consent contract (counterplan §4(f), §5.4):
 * storage and injection are independent. Live-capture exclusion depends on
 * companion participation ONLY — the policy function has no injection-switch
 * parameter at all, so "Use memory in this chat" cannot influence whether a
 * captured turn is review-eligible. "Archive this chat" (excludedByUser)
 * stops capture entirely before this policy is even consulted.
 */
class TranscriptRecorderPolicyTest {

    @Test
    fun onlyNoneParticipationExcludesACapturedTurn() {
        assertTrue(TranscriptRecorder.liveCaptureMarkedExcluded("none"))
        assertFalse(TranscriptRecorder.liveCaptureMarkedExcluded("full"))
        assertFalse(TranscriptRecorder.liveCaptureMarkedExcluded("global_only"))
        assertFalse(TranscriptRecorder.liveCaptureMarkedExcluded(""))
    }
}
