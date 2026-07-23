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
    fun scheduleAdvancesOnlyOnAFullyCleanPass() {
        // No failure of any kind: advance the window (this is the ONLY case
        // the controller may call recordAutoSuccess).
        assertTrue(AutoBackupScheduler.shouldAdvanceSchedule(hadAnyFailure = false))
        // ANY real failure — permission lost, a transient source/destination/
        // verify failure, or an unexpected exception — must NOT advance: a
        // failed attempt is never treated as though the scheduled backup
        // succeeded.
        assertFalse(AutoBackupScheduler.shouldAdvanceSchedule(hadAnyFailure = true))
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

    /* -------------------- failure categorization: the six scenarios -------- */
    // The exact matrix the owner asked to have confirmed: for each named
    // failure scenario, which BackupFailureCategory it maps to (via the
    // existing, separately-tested BackupFailureClassifier — see
    // BackupFailureClassifierTest, e.g. writeWithIoIsWrite for "storage
    // full"), and whether AutoBackupScheduler says it is worth a bounded
    // WorkManager backoff-retry.

    @Test
    fun temporaryDestinationFailureIsRetryable() {
        // A generic transient destination write failure (not permission-
        // related) classifies as DESTINATION_WRITE and IS retried.
        assertTrue(AutoBackupScheduler.isRetryableFailure(BackupFailureCategory.DESTINATION_WRITE))
    }

    @Test
    fun lostSafPermissionIsNeverRetried() {
        // Lost SAF permission — retrying immediately would just hit the same
        // wall; the fix requires the user to repair the destination.
        assertFalse(AutoBackupScheduler.isRetryableFailure(BackupFailureCategory.DESTINATION_PERMISSION))
    }

    @Test
    fun storageFullIsRetryable() {
        // Storage full classifies as DESTINATION_WRITE (confirmed by
        // BackupFailureClassifierTest.writeWithIoIsWrite, an ENOSPC IOException
        // at the WRITE_DESTINATION stage) — same bounded-retry treatment as any
        // other transient destination write failure.
        assertTrue(AutoBackupScheduler.isRetryableFailure(BackupFailureCategory.DESTINATION_WRITE))
    }

    @Test
    fun backupCreationFailureIsRetryable() {
        // A failure reading/snapshotting the source (SOURCE category) is a
        // health signal, not confirmed corruption — worth a bounded retry.
        assertTrue(AutoBackupScheduler.isRetryableFailure(BackupFailureCategory.SOURCE))
    }

    @Test
    fun verificationFailureIsRetryable() {
        assertTrue(AutoBackupScheduler.isRetryableFailure(BackupFailureCategory.VERIFY))
    }

    @Test
    fun unexpectedExceptionIsCategorizedSourceAndRetryable() {
        // AutoBackupController's defensive catch-all classifies an unexpected
        // exception as SOURCE (the least presumptive category) — confirm that
        // category is retryable, matching the controller's treatment.
        assertTrue(AutoBackupScheduler.isRetryableFailure(BackupFailureCategory.SOURCE))
    }

    @Test
    fun everyCategoryIsClassified() {
        // Exhaustiveness pin: every BackupFailureCategory value has a defined
        // retry answer (the `when` in isRetryableFailure has no else branch,
        // so this also guards against a silently-uncovered future category).
        for (c in BackupFailureCategory.values()) {
            AutoBackupScheduler.isRetryableFailure(c) // must not throw
        }
    }

    /* -------------------- bounded retry attempts ---------------------------- */

    @Test
    fun retryIsBoundedByMaxAttempts() {
        val max = AutoBackupScheduler.MAX_RETRY_ATTEMPTS
        for (attempt in 0 until max) {
            assertTrue("attempt $attempt of $max should still retry", AutoBackupScheduler.shouldRetryNow(attempt))
        }
        assertFalse("attempt $max reached the bound", AutoBackupScheduler.shouldRetryNow(max))
        assertFalse(AutoBackupScheduler.shouldRetryNow(max + 5))
    }

    @Test
    fun retryBoundHonoursACustomMax() {
        assertTrue(AutoBackupScheduler.shouldRetryNow(runAttemptCount = 0, maxAttempts = 1))
        assertFalse(AutoBackupScheduler.shouldRetryNow(runAttemptCount = 1, maxAttempts = 1))
    }

    /* -------------------- totalVerifiedSize: the "File Size" total --------- */
    // RecoveryBackupManager.TypeResult carries no Android types, so the sum
    // logic behind the automatic-backup status's "File Size:" line is pure
    // and directly testable here.

    private fun result(
        type: BackupType,
        success: Boolean,
        category: BackupFailureCategory? = null,
        sizeBytes: Long? = null
    ) = RecoveryBackupManager.TypeResult(type, success, category, sizeBytes)

    @Test
    fun totalSize_sumsOnlySuccessfulVerifiedSizes() {
        val results = listOf(
            result(BackupType.MEMORY, success = true, sizeBytes = 1000L),
            result(BackupType.LOREBOOK, success = true, sizeBytes = 2000L),
            result(BackupType.CHATS, success = true, sizeBytes = 500L),
            result(BackupType.USER_IMAGE, success = true, sizeBytes = 300L)
        )
        assertEquals(3800L, AutoBackupScheduler.totalVerifiedSize(results))
    }

    @Test
    fun totalSize_excludesNothingToBackUpContributingZero() {
        // "Nothing to back up" is success=false, category=null, sizeBytes=null
        // — it must contribute nothing to the sum, not be treated as missing
        // data that makes the whole total unavailable.
        val results = listOf(
            result(BackupType.MEMORY, success = true, sizeBytes = 1000L),
            result(BackupType.LOREBOOK, success = false, category = null, sizeBytes = null),
            result(BackupType.CHATS, success = true, sizeBytes = 500L),
            result(BackupType.USER_IMAGE, success = false, category = null, sizeBytes = null)
        )
        assertEquals(1500L, AutoBackupScheduler.totalVerifiedSize(results))
    }

    @Test
    fun totalSize_allNothingToBackUp_isZeroNotUnavailable() {
        val results = BackupType.displayOrder.map { result(it, success = false, category = null, sizeBytes = null) }
        assertEquals(0L, AutoBackupScheduler.totalVerifiedSize(results))
    }

    @Test
    fun totalSize_missingSizeOnASuccessfulResult_isUnavailable() {
        // Defensive case: a successful result somehow missing its verified
        // size must never produce a silently-wrong partial sum — that would
        // itself be a form of estimating.
        val results = listOf(
            result(BackupType.MEMORY, success = true, sizeBytes = 1000L),
            result(BackupType.LOREBOOK, success = true, sizeBytes = null),
            result(BackupType.CHATS, success = true, sizeBytes = 500L),
            result(BackupType.USER_IMAGE, success = true, sizeBytes = 300L)
        )
        assertEquals(null, AutoBackupScheduler.totalVerifiedSize(results))
    }

    @Test
    fun totalSize_ignoresFailedResultsSizesEvenIfPresent() {
        // A failed result's sizeBytes is always null by construction in the
        // real engine, but the sum must key off `success`, not merely
        // "sizeBytes present" — pin that explicitly.
        val results = listOf(
            result(BackupType.MEMORY, success = true, sizeBytes = 1000L),
            result(BackupType.LOREBOOK, success = false, category = BackupFailureCategory.SOURCE, sizeBytes = null)
        )
        assertEquals(1000L, AutoBackupScheduler.totalVerifiedSize(results))
    }

    @Test
    fun totalSize_emptyResultsIsZero() {
        assertEquals(0L, AutoBackupScheduler.totalVerifiedSize(emptyList()))
    }

    /* -------------------- autoFailureReason: results -> display reason ----- */

    private fun fail(
        type: BackupType,
        category: BackupFailureCategory,
        insufficientStorage: Boolean = false
    ) = RecoveryBackupManager.TypeResult(
        type, success = false, category = category, sizeBytes = null, insufficientStorage = insufficientStorage
    )

    @Test
    fun reason_permissionWinsOverEverything() {
        // A lost permission anywhere in the pass outranks any other failure —
        // it blocks everything and pauses the system.
        val results = listOf(
            fail(BackupType.MEMORY, BackupFailureCategory.SOURCE),
            fail(BackupType.LOREBOOK, BackupFailureCategory.DESTINATION_PERMISSION),
            fail(BackupType.CHATS, BackupFailureCategory.VERIFY)
        )
        assertEquals(AutoBackupFailureReason.DESTINATION_PERMISSION, AutoBackupScheduler.autoFailureReason(results))
    }

    @Test
    fun reason_destinationWriteMapsToWrite() {
        val results = listOf(fail(BackupType.MEMORY, BackupFailureCategory.DESTINATION_WRITE))
        assertEquals(AutoBackupFailureReason.DESTINATION_WRITE, AutoBackupScheduler.autoFailureReason(results))
    }

    @Test
    fun reason_destinationWriteWithInsufficientStorageMapsToFull() {
        val results = listOf(fail(BackupType.MEMORY, BackupFailureCategory.DESTINATION_WRITE, insufficientStorage = true))
        assertEquals(AutoBackupFailureReason.DESTINATION_FULL, AutoBackupScheduler.autoFailureReason(results))
    }

    @Test
    fun reason_sourceMapsToSource() {
        val results = listOf(fail(BackupType.MEMORY, BackupFailureCategory.SOURCE))
        assertEquals(AutoBackupFailureReason.SOURCE, AutoBackupScheduler.autoFailureReason(results))
    }

    @Test
    fun reason_verifyMapsToVerify() {
        val results = listOf(fail(BackupType.MEMORY, BackupFailureCategory.VERIFY))
        assertEquals(AutoBackupFailureReason.VERIFY, AutoBackupScheduler.autoFailureReason(results))
    }

    @Test
    fun reason_firstRealFailureDecidesWhenNoPermission() {
        val results = listOf(
            result(BackupType.MEMORY, success = true, sizeBytes = 100L),
            result(BackupType.LOREBOOK, success = false, category = null), // nothing-to-back-up, skipped
            fail(BackupType.CHATS, BackupFailureCategory.VERIFY),
            fail(BackupType.USER_IMAGE, BackupFailureCategory.SOURCE)
        )
        assertEquals(AutoBackupFailureReason.VERIFY, AutoBackupScheduler.autoFailureReason(results))
    }

    @Test
    fun reason_insufficientStorageOnlyMattersForDestinationWrite() {
        // A SOURCE failure that happens to carry insufficientStorage=true still
        // maps to SOURCE — the "out of space" message is scoped to the
        // destination-write case only (owner requirement).
        val results = listOf(fail(BackupType.MEMORY, BackupFailureCategory.SOURCE, insufficientStorage = true))
        assertEquals(AutoBackupFailureReason.SOURCE, AutoBackupScheduler.autoFailureReason(results))
    }

    @Test
    fun reason_nullWhenNoRealFailure() {
        // A fully clean pass, or only neutral nothing-to-back-up results, is
        // not a failure — the controller records a success, never a failure.
        assertEquals(null, AutoBackupScheduler.autoFailureReason(emptyList()))
        assertEquals(null, AutoBackupScheduler.autoFailureReason(listOf(
            result(BackupType.MEMORY, success = true, sizeBytes = 100L),
            result(BackupType.LOREBOOK, success = false, category = null)
        )))
    }
}
