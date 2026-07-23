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

/**
 * The PURE decision for WHAT the Automatic Backups status area shows (owner
 * ruling, July 23 2026). No Android types and no strings — it returns a
 * [View] of a [Kind] plus which lines to include; the screen
 * ([org.teslasoft.assistant.ui.activities.MemoryBackupRestoreActivity]) maps
 * that to owner-approved wording and the real Material spinner. Keeping the
 * decision here means every displayed state — creating, disabled, never-run,
 * paused, each failure reason, and success — is unit-tested without a device.
 *
 * The rules:
 *  - A pass in flight ⇒ [Kind.CREATING] (spinner + "Creating Automatic
 *    Backup…", nothing else).
 *  - Disabled ⇒ show the last successful backup + its size if one exists, else
 *    nothing ([Kind.HIDDEN]); never a next-due (nothing is scheduled).
 *  - A lost destination ⇒ [Kind.PAUSED]: the "access lost, choose the folder
 *    again" message and NO retry line — automatic backups stay paused until
 *    the user repairs the destination. Driven by the live SAF-access check OR
 *    a recorded permission failure (the two coincide in practice).
 *  - The last attempt failed (a non-permission reason, folder still reachable)
 *    ⇒ [Kind.FAILED]: the reason-specific message, the PREVIOUS successful
 *    backup + size still shown (a failure never replaces the last good info),
 *    and "Retrying Automatically" (the exact next-retry time is not reliably
 *    exposed by WorkManager, so it is not shown as a false precision).
 *  - Otherwise a prior success ⇒ [Kind.SUCCESS] (last backup + size + next
 *    due); no prior success ⇒ [Kind.NEVER].
 *
 * A failure never advances the schedule, so `lastAttemptMillis > lastSuccessMillis`
 * is the "the latest attempt failed" signal, and the recorded reason says how.
 */
object AutoBackupStatusPresenter {

    enum class Kind {
        /** A pass is running now: spinner + creating text, nothing else. */
        CREATING,
        /** Disabled but a prior success exists: last backup + size only. */
        DISABLED,
        /** Enabled, nothing to show yet (disabled with no prior success, or an
         *  enabled-but-no-destination defensive state). Render nothing. */
        HIDDEN,
        /** Enabled, reachable, never succeeded and no failure to report. */
        NEVER,
        /** Destination lost — paused until repaired, no auto-retry. */
        PAUSED,
        /** The latest attempt failed (non-permission); retrying automatically. */
        FAILED,
        /** A prior success and the latest attempt did not fail. */
        SUCCESS
    }

    /**
     * @param failureReason the last recorded failure reason drives [FAILED] /
     *        [PAUSED]'s message; ignored on a successful/never state.
     * @param showLastSuccess include the "Last (Successful) Backup" + "File
     *        Size" lines (uses the last-success label on SUCCESS/DISABLED, the
     *        "Last Successful Backup" label on PAUSED/FAILED).
     * @param showNextDue include the "Next Automatic Backup Due" line
     *        ([SUCCESS] only).
     * @param showRetrying include the "Retrying Automatically" line
     *        ([FAILED] only).
     */
    data class View(
        val kind: Kind,
        val failureReason: AutoBackupFailureReason?,
        val showLastSuccess: Boolean,
        val showNextDue: Boolean,
        val showRetrying: Boolean
    )

    fun present(
        enabled: Boolean,
        hasDestination: Boolean,
        destinationAccessible: Boolean,
        running: Boolean,
        lastSuccessMillis: Long,
        lastAttemptMillis: Long,
        failureReason: AutoBackupFailureReason?
    ): View {
        if (running) return View(Kind.CREATING, null, showLastSuccess = false, showNextDue = false, showRetrying = false)

        if (!enabled) {
            return if (lastSuccessMillis > 0L) {
                View(Kind.DISABLED, null, showLastSuccess = true, showNextDue = false, showRetrying = false)
            } else {
                View(Kind.HIDDEN, null, showLastSuccess = false, showNextDue = false, showRetrying = false)
            }
        }

        // Enabled but somehow no destination (UI prevents this): nothing useful.
        if (!hasDestination) {
            return View(Kind.HIDDEN, null, showLastSuccess = false, showNextDue = false, showRetrying = false)
        }

        val hasPriorSuccess = lastSuccessMillis > 0L
        val paused = !destinationAccessible || failureReason == AutoBackupFailureReason.DESTINATION_PERMISSION
        if (paused) {
            return View(
                Kind.PAUSED, AutoBackupFailureReason.DESTINATION_PERMISSION,
                showLastSuccess = hasPriorSuccess, showNextDue = false, showRetrying = false
            )
        }

        val latestAttemptFailed = lastAttemptMillis > lastSuccessMillis && failureReason != null
        if (latestAttemptFailed) {
            return View(
                Kind.FAILED, failureReason,
                showLastSuccess = hasPriorSuccess, showNextDue = false, showRetrying = true
            )
        }

        return if (hasPriorSuccess) {
            View(Kind.SUCCESS, null, showLastSuccess = true, showNextDue = true, showRetrying = false)
        } else {
            View(Kind.NEVER, null, showLastSuccess = false, showNextDue = false, showRetrying = false)
        }
    }
}
