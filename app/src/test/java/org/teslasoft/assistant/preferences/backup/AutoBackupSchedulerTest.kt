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

/**
 * The pure Automatic Backups decision core (owner ruling, July 23 2026). These
 * pin every reliability rule the task requires: all four frequencies, the due
 * calculation, duplicate prevention (a run already in flight AND a window
 * already satisfied), a missing destination, a lost SAF grant, and the
 * success/failure schedule-advance rule — all without Android.
 */
class AutoBackupSchedulerTest {

    private val day = AutoBackupScheduler.DAY_MILLIS

    /* -------------------- interval per frequency (all four) ---------------- */

    @Test
    fun intervalMillisForEveryFrequency() {
        assertEquals(day, AutoBackupScheduler.intervalMillis(BackupFrequency.DAILY))
        assertEquals(7L * day, AutoBackupScheduler.intervalMillis(BackupFrequency.WEEKLY))
        assertEquals(14L * day, AutoBackupScheduler.intervalMillis(BackupFrequency.BIWEEKLY))
        assertEquals(30L * day, AutoBackupScheduler.intervalMillis(BackupFrequency.MONTHLY))
    }

    /* -------------------- next-due / never ran ----------------------------- */

    @Test
    fun neverRanIsDueImmediatelyForEveryFrequency() {
        for (f in BackupFrequency.values()) {
            assertEquals("next due for never-ran should be 0 ($f)", 0L, AutoBackupScheduler.nextDueMillis(0L, f))
            assertTrue("never-ran should be due ($f)", AutoBackupScheduler.isDue(0L, 1L, f))
            // A negative anchor (corrupt/absent) is also treated as never.
            assertTrue("negative anchor should be due ($f)", AutoBackupScheduler.isDue(-5L, 1L, f))
        }
    }

    @Test
    fun nextDueIsLastSuccessPlusIntervalForEveryFrequency() {
        val last = 1_000_000_000L
        for (f in BackupFrequency.values()) {
            assertEquals(last + AutoBackupScheduler.intervalMillis(f), AutoBackupScheduler.nextDueMillis(last, f))
        }
    }

    /* -------------------- due calculation boundaries (all four) ------------ */

    @Test
    fun dueBoundariesForEveryFrequency() {
        val now = 500_000_000_000L
        for (f in BackupFrequency.values()) {
            val interval = AutoBackupScheduler.intervalMillis(f)
            // Half an interval in: not due.
            assertFalse("half-interval not due ($f)", AutoBackupScheduler.isDue(now - interval / 2, now, f))
            // One ms before the interval: not due.
            assertFalse("1ms-before not due ($f)", AutoBackupScheduler.isDue(now - (interval - 1), now, f))
            // Exactly one interval later: due (inclusive boundary).
            assertTrue("exact interval due ($f)", AutoBackupScheduler.isDue(now - interval, now, f))
            // Well past: due.
            assertTrue("past interval due ($f)", AutoBackupScheduler.isDue(now - interval * 3, now, f))
        }
    }

    /* -------------------- plan: the full gate ------------------------------ */

    private val now = 100_000_000_000L
    private val dueAnchor = now - (2 * day) // due for DAILY
    private val freshAnchor = now           // just backed up, not due for DAILY

    @Test
    fun disabledSkips() {
        assertEquals(
            AutoBackupScheduler.Decision.SKIP_DISABLED,
            AutoBackupScheduler.plan(
                enabled = false, hasDestination = true, destinationAccessible = true,
                alreadyRunning = false, lastSuccessMillis = dueAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY
            )
        )
    }

    @Test
    fun missingDestinationSkips() {
        assertEquals(
            AutoBackupScheduler.Decision.SKIP_NO_DESTINATION,
            AutoBackupScheduler.plan(
                enabled = true, hasDestination = false, destinationAccessible = false,
                alreadyRunning = false, lastSuccessMillis = dueAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY
            )
        )
    }

    @Test
    fun lostPermissionSkipsAndOutranksDue() {
        // Destination present but its grant is gone: this must surface as
        // PERMISSION_LOST even though a backup is otherwise due.
        assertEquals(
            AutoBackupScheduler.Decision.SKIP_PERMISSION_LOST,
            AutoBackupScheduler.plan(
                enabled = true, hasDestination = true, destinationAccessible = false,
                alreadyRunning = false, lastSuccessMillis = dueAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY
            )
        )
    }

    @Test
    fun alreadyRunningSkips_duplicatePreventionInFlight() {
        // A run in flight blocks a second even when due — no overlapping runs.
        assertEquals(
            AutoBackupScheduler.Decision.SKIP_ALREADY_RUNNING,
            AutoBackupScheduler.plan(
                enabled = true, hasDestination = true, destinationAccessible = true,
                alreadyRunning = true, lastSuccessMillis = dueAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY
            )
        )
    }

    @Test
    fun notDueSkips_duplicatePreventionSameWindow() {
        // Just backed up: a second trigger in the same window is NOT due, so no
        // duplicate backup is produced.
        assertEquals(
            AutoBackupScheduler.Decision.SKIP_NOT_DUE,
            AutoBackupScheduler.plan(
                enabled = true, hasDestination = true, destinationAccessible = true,
                alreadyRunning = false, lastSuccessMillis = freshAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY
            )
        )
    }

    @Test
    fun allGatesPassRuns_successPath() {
        assertEquals(
            AutoBackupScheduler.Decision.RUN,
            AutoBackupScheduler.plan(
                enabled = true, hasDestination = true, destinationAccessible = true,
                alreadyRunning = false, lastSuccessMillis = dueAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY
            )
        )
    }

    @Test
    fun manualRunSkipsDueButHonoursOtherGates() {
        // requireDue = false (the "Retry / Back up now" action): a not-due
        // window still RUNs...
        assertEquals(
            AutoBackupScheduler.Decision.RUN,
            AutoBackupScheduler.plan(
                enabled = true, hasDestination = true, destinationAccessible = true,
                alreadyRunning = false, lastSuccessMillis = freshAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY, requireDue = false
            )
        )
        // ...but a lost destination still blocks it.
        assertEquals(
            AutoBackupScheduler.Decision.SKIP_PERMISSION_LOST,
            AutoBackupScheduler.plan(
                enabled = true, hasDestination = true, destinationAccessible = false,
                alreadyRunning = false, lastSuccessMillis = freshAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY, requireDue = false
            )
        )
        // ...and disabled still wins.
        assertEquals(
            AutoBackupScheduler.Decision.SKIP_DISABLED,
            AutoBackupScheduler.plan(
                enabled = false, hasDestination = true, destinationAccessible = true,
                alreadyRunning = false, lastSuccessMillis = freshAnchor, nowMillis = now,
                frequency = BackupFrequency.DAILY, requireDue = false
            )
        )
    }

    /* -------------------- schedule advance: success vs failure ------------- */

    @Test
    fun scheduleAdvancesOnSuccessNotOnPermissionFailure() {
        // Success (no destination-permission failure): advance the window.
        assertTrue(AutoBackupScheduler.shouldAdvanceSchedule(destinationPermissionFailed = false))
        // A destination/permission failure: DON'T advance — stay due so the
        // next trigger re-surfaces the lost-folder state and it blocks honestly.
        assertFalse(AutoBackupScheduler.shouldAdvanceSchedule(destinationPermissionFailed = true))
    }

    @Test
    fun advancingClosesTheWindow_noDuplicateNextTrigger() {
        // Simulate a successful run at `now`: the new anchor is `now`, and the
        // recorded next-due is now + interval. An immediately-following trigger
        // must see NOT_DUE for every frequency (no duplicate in the same window).
        for (f in BackupFrequency.values()) {
            val nextDue = AutoBackupScheduler.nextDueMillis(now, f)
            assertEquals(now + AutoBackupScheduler.intervalMillis(f), nextDue)
            assertEquals(
                AutoBackupScheduler.Decision.SKIP_NOT_DUE,
                AutoBackupScheduler.plan(
                    enabled = true, hasDestination = true, destinationAccessible = true,
                    alreadyRunning = false, lastSuccessMillis = now, nowMillis = now + 1_000L,
                    frequency = f
                )
            )
        }
    }
}
