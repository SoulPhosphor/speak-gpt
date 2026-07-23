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
import org.teslasoft.assistant.preferences.backup.AutoBackupStatusPresenter.Kind

/**
 * Every displayed state of the Automatic Backups status area (owner ruling,
 * July 23 2026). Pure, so each state — creating, disabled, never, paused, each
 * failure reason, and success — plus which lines it shows, is pinned without a
 * device.
 */
class AutoBackupStatusPresenterTest {

    private val ok = 1_000_000L   // a "last success" timestamp
    private val later = 2_000_000L // a "last attempt" AFTER the success (failed)

    private fun present(
        enabled: Boolean = true,
        hasDestination: Boolean = true,
        destinationAccessible: Boolean = true,
        running: Boolean = false,
        lastSuccessMillis: Long = ok,
        lastAttemptMillis: Long = ok,
        failureReason: AutoBackupFailureReason? = null
    ) = AutoBackupStatusPresenter.present(
        enabled, hasDestination, destinationAccessible, running,
        lastSuccessMillis, lastAttemptMillis, failureReason
    )

    /* -------------------- creating outranks everything --------------------- */

    @Test
    fun running_isCreating_andNothingElse() {
        val v = present(running = true, failureReason = AutoBackupFailureReason.SOURCE, destinationAccessible = false)
        assertEquals(Kind.CREATING, v.kind)
        assertFalse(v.showLastSuccess)
        assertFalse(v.showNextDue)
        assertFalse(v.showRetrying)
    }

    /* -------------------- disabled ----------------------------------------- */

    @Test
    fun disabledWithPriorSuccess_showsLastSuccessOnly() {
        val v = present(enabled = false, lastSuccessMillis = ok)
        assertEquals(Kind.DISABLED, v.kind)
        assertTrue(v.showLastSuccess)
        assertFalse(v.showNextDue)   // nothing scheduled while disabled
        assertFalse(v.showRetrying)
    }

    @Test
    fun disabledWithNoPriorSuccess_isHidden() {
        val v = present(enabled = false, lastSuccessMillis = 0L)
        assertEquals(Kind.HIDDEN, v.kind)
        assertFalse(v.showLastSuccess)
    }

    /* -------------------- never / success ---------------------------------- */

    @Test
    fun enabledCleanNeverRun_isNever() {
        val v = present(lastSuccessMillis = 0L, lastAttemptMillis = 0L, failureReason = null)
        assertEquals(Kind.NEVER, v.kind)
        assertFalse(v.showLastSuccess)
    }

    @Test
    fun enabledCleanSuccess_showsLastSizeAndNextDue() {
        val v = present(lastSuccessMillis = ok, lastAttemptMillis = ok, failureReason = null)
        assertEquals(Kind.SUCCESS, v.kind)
        assertTrue(v.showLastSuccess)
        assertTrue(v.showNextDue)
        assertFalse(v.showRetrying)
    }

    /* -------------------- paused (lost permission) ------------------------- */

    @Test
    fun destinationInaccessible_isPaused_noRetry() {
        val v = present(destinationAccessible = false, lastSuccessMillis = ok)
        assertEquals(Kind.PAUSED, v.kind)
        assertEquals(AutoBackupFailureReason.DESTINATION_PERMISSION, v.failureReason)
        assertTrue(v.showLastSuccess)     // previous good backup stays visible
        assertFalse(v.showRetrying)       // paused: NO scheduled retry
        assertFalse(v.showNextDue)
    }

    @Test
    fun recordedPermissionReason_isPaused_evenIfAccessibleFlagTrue() {
        // Defensive: a recorded permission failure also pauses, so the two
        // signals can't disagree into showing a retry for a lost folder.
        val v = present(
            destinationAccessible = true, lastAttemptMillis = later,
            failureReason = AutoBackupFailureReason.DESTINATION_PERMISSION
        )
        assertEquals(Kind.PAUSED, v.kind)
        assertFalse(v.showRetrying)
    }

    @Test
    fun paused_withNoPriorSuccess_hidesLastSuccessLine() {
        val v = present(destinationAccessible = false, lastSuccessMillis = 0L)
        assertEquals(Kind.PAUSED, v.kind)
        assertFalse(v.showLastSuccess)
    }

    /* -------------------- failed (non-permission) -------------------------- */

    @Test
    fun writeFailure_isFailed_withRetryAndPriorSuccess() {
        val v = present(
            lastSuccessMillis = ok, lastAttemptMillis = later,
            failureReason = AutoBackupFailureReason.DESTINATION_WRITE
        )
        assertEquals(Kind.FAILED, v.kind)
        assertEquals(AutoBackupFailureReason.DESTINATION_WRITE, v.failureReason)
        assertTrue(v.showLastSuccess)   // previous good backup still shown
        assertTrue(v.showRetrying)      // will retry automatically
        assertFalse(v.showNextDue)
    }

    @Test
    fun everyNonPermissionReason_isFailedWithThatReason() {
        for (reason in listOf(
            AutoBackupFailureReason.DESTINATION_WRITE,
            AutoBackupFailureReason.DESTINATION_FULL,
            AutoBackupFailureReason.SOURCE,
            AutoBackupFailureReason.VERIFY,
            AutoBackupFailureReason.UNEXPECTED
        )) {
            val v = present(lastSuccessMillis = ok, lastAttemptMillis = later, failureReason = reason)
            assertEquals("reason $reason should be FAILED", Kind.FAILED, v.kind)
            assertEquals(reason, v.failureReason)
            assertTrue(v.showRetrying)
        }
    }

    @Test
    fun failedFirstEverAttempt_noPriorSuccess_stillFailedNoSuccessLine() {
        val v = present(
            lastSuccessMillis = 0L, lastAttemptMillis = later,
            failureReason = AutoBackupFailureReason.SOURCE
        )
        assertEquals(Kind.FAILED, v.kind)
        assertFalse(v.showLastSuccess)  // there is no previous success to show
        assertTrue(v.showRetrying)
    }

    @Test
    fun staleReasonButAttemptNotAfterSuccess_isSuccessNotFailed() {
        // If the last attempt is not newer than the last success (the failure
        // predates the most recent success), it is not the "latest" state —
        // show success, not a failure.
        val v = present(
            lastSuccessMillis = later, lastAttemptMillis = ok,
            failureReason = AutoBackupFailureReason.SOURCE
        )
        assertEquals(Kind.SUCCESS, v.kind)
    }

    /* -------------------- enabled but no destination (defensive) ----------- */

    @Test
    fun enabledNoDestination_isHidden() {
        val v = present(hasDestination = false, destinationAccessible = false, lastSuccessMillis = 0L)
        assertEquals(Kind.HIDDEN, v.kind)
    }
}
