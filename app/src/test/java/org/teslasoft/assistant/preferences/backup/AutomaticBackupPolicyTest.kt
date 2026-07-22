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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.AutomaticBackupPolicy.Gate

/**
 * The automatic-backup gate (owner directive, July 22 2026). The precedence
 * IS the safety argument: disabled beats everything; no run without a
 * folder; no run when the folder grant is gone; no run — ever — without a
 * confirmed Recovery Code (automatic packages are always protected and must
 * never silently fall back to unencrypted); then the schedule; then the
 * failed-attempt backoff.
 */
class AutomaticBackupPolicyTest {

    private val now = 1_000_000_000_000L

    private fun eval(
        enabled: Boolean = true,
        hasFolder: Boolean = true,
        folderAccessible: Boolean = true,
        setupConfirmed: Boolean = true,
        lastSuccess: Long = 0L,
        lastAttempt: Long = 0L,
        at: Long = now,
        frequency: AutoBackupFrequency = AutoBackupFrequency.DAILY
    ): Gate = AutomaticBackupPolicy.evaluate(
        enabled, hasFolder, folderAccessible, setupConfirmed,
        lastSuccess, lastAttempt, at, frequency
    )

    // ---- gate precedence ----

    @Test
    fun disabledBeatsEverything() {
        assertEquals(Gate.DISABLED, eval(enabled = false, hasFolder = false, setupConfirmed = false))
    }

    @Test
    fun noFolderBlocksTheRun() {
        assertEquals(Gate.NO_FOLDER, eval(hasFolder = false))
    }

    @Test
    fun lostFolderGrantBlocksVisibly() {
        assertEquals(Gate.FOLDER_INACCESSIBLE, eval(folderAccessible = false))
    }

    @Test
    fun unconfirmedRecoverySetupNeverRuns_protectedOnlyRule() {
        assertEquals(Gate.SETUP_NOT_CONFIRMED, eval(setupConfirmed = false))
    }

    @Test
    fun allPrerequisitesAndNeverBackedUpRuns() {
        assertEquals(Gate.RUN, eval())
    }

    // ---- schedule for all four frequencies ----

    @Test
    fun dueExactlyAtEachFrequencyInterval() {
        for (f in AutoBackupFrequency.values()) {
            val last = now - f.intervalMillis
            assertEquals("frequency $f", Gate.RUN,
                eval(lastSuccess = last, lastAttempt = last, frequency = f))
            assertEquals("frequency $f", Gate.NOT_DUE,
                eval(lastSuccess = last + 1, lastAttempt = last + 1, frequency = f))
        }
    }

    @Test
    fun neverSucceededIsAlwaysDue() {
        for (f in AutoBackupFrequency.values()) {
            assertTrue(AutomaticBackupPolicy.isDue(0L, now, f))
            assertTrue(AutomaticBackupPolicy.isDue(-1L, now, f))
        }
    }

    // ---- failed-attempt backoff ----

    @Test
    fun failedAttemptBacksOffThenRetries() {
        val lastSuccess = now - 3 * AutoBackupFrequency.DAILY.intervalMillis
        val failedAttempt = now - AutomaticBackupPolicy.MIN_RETRY_MILLIS + 1
        assertEquals(Gate.RETRY_TOO_SOON,
            eval(lastSuccess = lastSuccess, lastAttempt = failedAttempt))
        assertEquals(Gate.RUN,
            eval(lastSuccess = lastSuccess, lastAttempt = now - AutomaticBackupPolicy.MIN_RETRY_MILLIS))
    }

    @Test
    fun successfulLastAttemptDoesNotBackOff() {
        // lastAttempt == lastSuccess means the last attempt SUCCEEDED: only
        // the frequency schedule applies, never the failure backoff.
        val last = now - AutoBackupFrequency.DAILY.intervalMillis
        assertEquals(Gate.RUN, eval(lastSuccess = last, lastAttempt = last))
    }

    // ---- frequency parsing ----

    @Test
    fun frequencyKeysRoundTripAndUnknownFallsBackToDaily() {
        for (f in AutoBackupFrequency.values()) {
            assertEquals(f, AutoBackupFrequency.fromKey(f.key))
        }
        assertEquals(AutoBackupFrequency.DAILY, AutoBackupFrequency.fromKey(null))
        assertEquals(AutoBackupFrequency.DAILY, AutoBackupFrequency.fromKey("garbage"))
    }

    @Test
    fun intervalsAreTheOwnerChoices() {
        assertEquals(1L, AutoBackupFrequency.DAILY.intervalMillis / (24L * 60 * 60 * 1000))
        assertEquals(7L, AutoBackupFrequency.WEEKLY.intervalMillis / (24L * 60 * 60 * 1000))
        assertEquals(14L, AutoBackupFrequency.BIWEEKLY.intervalMillis / (24L * 60 * 60 * 1000))
        assertEquals(30L, AutoBackupFrequency.MONTHLY.intervalMillis / (24L * 60 * 60 * 1000))
        assertFalse(AutoBackupFrequency.values().any { it.intervalMillis <= 0 })
    }
}
