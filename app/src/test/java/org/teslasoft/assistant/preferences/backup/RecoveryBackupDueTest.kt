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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure 24 h throttle decision (Build Phase 2 item 2). The trigger is checked
 * at app start / cheap foreground return and must allow at most one success per
 * type per day — never promise guaranteed daily backups.
 */
class RecoveryBackupDueTest {

    private val day = RecoveryBackupState.THROTTLE_MILLIS

    @Test
    fun neverBackedUpIsDue() {
        assertTrue(RecoveryBackupState.isBackupDue(lastSuccessMillis = 0L, nowMillis = 1_000_000L))
    }

    @Test
    fun negativeLastSuccessIsDue() {
        assertTrue(RecoveryBackupState.isBackupDue(lastSuccessMillis = -5L, nowMillis = 100L))
    }

    @Test
    fun withinThrottleIsNotDue() {
        val now = 100_000_000L
        assertFalse(RecoveryBackupState.isBackupDue(lastSuccessMillis = now - day / 2, nowMillis = now))
    }

    @Test
    fun exactlyThrottleIsDue() {
        val now = 100_000_000L
        assertTrue(RecoveryBackupState.isBackupDue(lastSuccessMillis = now - day, nowMillis = now))
    }

    @Test
    fun pastThrottleIsDue() {
        val now = 100_000_000L
        assertTrue(RecoveryBackupState.isBackupDue(lastSuccessMillis = now - (day * 2), nowMillis = now))
    }

    @Test
    fun oneMillisecondBeforeThrottleIsNotDue() {
        val now = 100_000_000L
        assertFalse(RecoveryBackupState.isBackupDue(lastSuccessMillis = now - (day - 1), nowMillis = now))
    }
}
