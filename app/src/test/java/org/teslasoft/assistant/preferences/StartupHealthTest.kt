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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Build Phase 1 crash-triggered integrity-check gate. Pure decision, so it
 * is exercised directly without Android — the every-launch behavior (skip on a
 * clean exit) is exactly what these cases pin down.
 */
class StartupHealthTest {

    @Test
    fun cleanExitAndNothingPendingSkipsTheCheck() {
        // The regression Build Phase 1 removes: a healthy app after a clean exit
        // must NOT run the whole-database integrity check.
        assertFalse(
            StartupHealth.shouldRunIntegrityCheck(
                previousExitAbnormal = false,
                checkOrRepairPending = false
            )
        )
    }

    @Test
    fun abnormalPreviousExitRunsTheCheck() {
        assertTrue(
            StartupHealth.shouldRunIntegrityCheck(
                previousExitAbnormal = true,
                checkOrRepairPending = false
            )
        )
    }

    @Test
    fun explicitPendingForcesTheCheckEvenAfterACleanExit() {
        // Build Phase 3 sets this after a requested/interrupted repair; a clean
        // exit must not let it slip past.
        assertTrue(
            StartupHealth.shouldRunIntegrityCheck(
                previousExitAbnormal = false,
                checkOrRepairPending = true
            )
        )
    }

    @Test
    fun unknownPreviousExitRunsTheCheckAsTheSafeDirection() {
        // API < 30 cannot report the last exit reason; when we cannot prove the
        // exit was clean we run the check rather than risk using a bad database.
        assertTrue(
            StartupHealth.shouldRunIntegrityCheck(
                previousExitAbnormal = null,
                checkOrRepairPending = false
            )
        )
    }
}
