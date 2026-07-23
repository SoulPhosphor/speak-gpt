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
 * The PURE decision core of the Automatic Backups controller (owner ruling,
 * July 23 2026 — completing the system behind the existing Backup & Restore
 * UI). No Android types, so every branch is unit-tested directly on the JVM
 * (this project runs no Robolectric).
 *
 * It answers three questions and nothing else:
 *  1. How long is a frequency's window? ([intervalMillis])
 *  2. When is the automatic set next due, and is it due now?
 *     ([nextDueMillis] / [isDue])
 *  3. Given the whole gate — enabled, destination present, destination still
 *     writable, a run already in flight, and the due decision — what should
 *     the controller DO? ([plan])
 *
 * Actually reading state, holding the SAF folder, running the snapshot engine
 * and persisting results is [AutoBackupController]'s job; it delegates every
 * yes/no here so the reliability rules are proven without a device.
 *
 * DUE WINDOW / NO DUPLICATES: "due" is measured from the last SUCCESSFUL
 * automatic pass. Once a pass succeeds the anchor advances by one interval, so
 * a second trigger in the same window sees [isDue] == false — the app-open
 * check and the WorkManager job can never both produce a backup for the same
 * window. A run in flight is additionally short-circuited by
 * [Decision.SKIP_ALREADY_RUNNING] so two triggers that fire before the first
 * has recorded success still cannot overlap.
 *
 * TIMING IS NOT EXACT (owner rule): a frequency's interval is the *minimum*
 * spacing between successful backups, never a promise Android will run the job
 * at that instant — background work may be deferred. The app-open check catches
 * up whenever the user next opens the app.
 */
object AutoBackupScheduler {

    /** One day in milliseconds — the smallest window and the DAILY interval. */
    const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

    /**
     * The minimum spacing between successful automatic backups for [frequency].
     * MONTHLY is a fixed 30-day window (a plain, predictable spacing — this is a
     * throttle, not a calendar), matching how the rest of the backup system
     * measures elapsed time in fixed millis.
     */
    fun intervalMillis(frequency: BackupFrequency): Long = when (frequency) {
        BackupFrequency.DAILY -> DAY_MILLIS
        BackupFrequency.WEEKLY -> 7L * DAY_MILLIS
        BackupFrequency.BIWEEKLY -> 14L * DAY_MILLIS
        BackupFrequency.MONTHLY -> 30L * DAY_MILLIS
    }

    /**
     * The epoch-millis at which the automatic set is next due, given the last
     * SUCCESSFUL automatic pass. A non-positive [lastSuccessMillis] means "never
     * succeeded" and yields 0 — due immediately.
     */
    fun nextDueMillis(lastSuccessMillis: Long, frequency: BackupFrequency): Long =
        if (lastSuccessMillis <= 0L) 0L else lastSuccessMillis + intervalMillis(frequency)

    /**
     * Whether a backup is due: never succeeded, or the last success is at least
     * one [frequency] interval old. Boundary is inclusive — exactly one interval
     * later IS due.
     */
    fun isDue(lastSuccessMillis: Long, nowMillis: Long, frequency: BackupFrequency): Boolean =
        nowMillis >= nextDueMillis(lastSuccessMillis, frequency)

    /** What the controller should do this trigger. */
    enum class Decision {
        /** The feature is off — do nothing. */
        SKIP_DISABLED,

        /** Enabled but no backup folder has ever been chosen — do nothing (the
         *  UI requires one before it lets the user enable, so this is a
         *  defensive state, e.g. prefs cleared). */
        SKIP_NO_DESTINATION,

        /** The chosen folder's persisted SAF grant is gone (moved / deleted /
         *  revoked). Future runs are BLOCKED until the user repairs the
         *  destination — the run must never silently fall back anywhere. */
        SKIP_PERMISSION_LOST,

        /** A run is already in flight — never start a second (no duplicate). */
        SKIP_ALREADY_RUNNING,

        /** Not yet due for the current window. */
        SKIP_NOT_DUE,

        /** All gates pass — run the backup now. */
        RUN
    }

    /**
     * The full gate, in strict priority order so each condition is unit-testable
     * in isolation:
     *
     *  disabled → no destination → permission lost → already running → not due
     *  → RUN.
     *
     * [destinationAccessible] is only meaningful when [hasDestination] is true;
     * callers pass the live SAF-permission check result. Permission-lost is
     * ranked ABOVE the due check on purpose: a lost folder must surface as its
     * own honest state regardless of whether a backup would otherwise be due.
     *
     * @param requireDue when false (the manual "Retry"/"Back up now" action)
     *        the due check is skipped — every other gate still applies, so a
     *        manual run still honours disabled/no-destination/permission-lost/
     *        already-running.
     */
    fun plan(
        enabled: Boolean,
        hasDestination: Boolean,
        destinationAccessible: Boolean,
        alreadyRunning: Boolean,
        lastSuccessMillis: Long,
        nowMillis: Long,
        frequency: BackupFrequency,
        requireDue: Boolean = true
    ): Decision {
        if (!enabled) return Decision.SKIP_DISABLED
        if (!hasDestination) return Decision.SKIP_NO_DESTINATION
        if (!destinationAccessible) return Decision.SKIP_PERMISSION_LOST
        if (alreadyRunning) return Decision.SKIP_ALREADY_RUNNING
        if (requireDue && !isDue(lastSuccessMillis, nowMillis, frequency)) return Decision.SKIP_NOT_DUE
        return Decision.RUN
    }

    /**
     * After a run completes, whether the schedule anchor should advance (closing
     * this window and recording the pass as the new "last success"). This is
     * true ONLY when the whole pass was genuinely clean — no per-type failure of
     * any kind (source, destination write, verify) and no lost destination
     * permission. A failed or partially-failed attempt must NEVER be treated as
     * though the scheduled backup succeeded: [hadAnyFailure] covers every real
     * failure category, not just a lost destination, so the schedule only ever
     * advances on a true success.
     */
    fun shouldAdvanceSchedule(hadAnyFailure: Boolean): Boolean = !hadAnyFailure

    /** The bounded ceiling on WorkManager backoff-retry attempts within a single
     *  due window, for a category [isRetryableFailure] allows retrying. Beyond
     *  this the worker gives up until the next natural trigger (the next
     *  periodic tick or an app-open catch-up) rather than retrying forever. */
    const val MAX_RETRY_ATTEMPTS = 3

    /**
     * Whether a failure [category] is worth a short, bounded WorkManager
     * backoff-retry within the current due window, as opposed to one that must
     * NOT be retried automatically:
     *  - [BackupFailureCategory.DESTINATION_PERMISSION] — retrying immediately
     *    would just hit the same wall; the fix requires the USER to repair the
     *    destination, so automatic backups pause without repeated retries until
     *    they do (the next natural trigger re-checks, it does not backoff-loop).
     *  - [BackupFailureCategory.SOURCE], [BackupFailureCategory.DESTINATION_WRITE]
     *    (covers both a generic transient destination failure and a full-disk
     *    "storage full" condition — see [BackupFailureClassifier]), and
     *    [BackupFailureCategory.VERIFY] are all conditions a short backoff-retry
     *    might clear (a transient IO hiccup, a momentarily busy source, freed-up
     *    space), so they ARE retried, bounded by [MAX_RETRY_ATTEMPTS].
     */
    fun isRetryableFailure(category: BackupFailureCategory): Boolean = when (category) {
        BackupFailureCategory.DESTINATION_PERMISSION -> false
        BackupFailureCategory.SOURCE,
        BackupFailureCategory.DESTINATION_WRITE,
        BackupFailureCategory.VERIFY -> true
    }

    /** Whether the worker should retry now, given how many times this due
     *  window's execution has already been attempted ([runAttemptCount], as
     *  reported by WorkManager — 0 on the first try). Bounded so a persistently
     *  broken destination doesn't retry forever between periodic ticks. */
    fun shouldRetryNow(runAttemptCount: Int, maxAttempts: Int = MAX_RETRY_ATTEMPTS): Boolean =
        runAttemptCount < maxAttempts

    /**
     * The automatic pass's reported "File Size": the sum of every
     * successfully-backed-up artifact's FINAL VERIFIED destination size
     * ([RecoveryBackupManager.TypeResult.sizeBytes]) — a result with nothing
     * to back up contributes none (it wrote no file). Pure so this is
     * unit-tested directly: [RecoveryBackupManager.TypeResult] carries no
     * Android types.
     *
     * Null (unavailable) when ANY successful result is missing its size —
     * that should not normally happen (a successful write always verifies
     * and returns one), but reporting a partial sum in that case would
     * itself be a form of estimating, which the file-size display must never
     * do. Only meaningful to call on a pass [shouldAdvanceSchedule] accepted;
     * callers pass the same `results` list either way.
     */
    fun totalVerifiedSize(results: List<RecoveryBackupManager.TypeResult>): Long? {
        val successfulSizes = results.filter { it.success }.map { it.sizeBytes }
        return if (successfulSizes.any { it == null }) null else successfulSizes.filterNotNull().sum()
    }

    /**
     * The DISPLAY reason a completed pass failed, from its per-type
     * [RecoveryBackupManager.TypeResult]s — the value the automatic-backup
     * status line shows a message for. Pure and unit-tested.
     *
     * Priority: a lost destination permission (anywhere in the pass) wins,
     * since it blocks everything and pauses the system; otherwise the FIRST
     * real per-type failure decides — a [BackupFailureCategory.DESTINATION_WRITE]
     * whose exception was recognized as out-of-space
     * ([RecoveryBackupManager.TypeResult.insufficientStorage]) becomes
     * [AutoBackupFailureReason.DESTINATION_FULL], everything else maps straight
     * across. Returns null when there was no real failure (a fully clean pass,
     * or only neutral "nothing to back up" results) — the caller records a
     * success in that case, never a failure. The controller's own defensive
     * catch (an unexpected throw from the engine, which is designed never to
     * happen) records [AutoBackupFailureReason.UNEXPECTED] directly and does
     * NOT go through here.
     */
    fun autoFailureReason(results: List<RecoveryBackupManager.TypeResult>): AutoBackupFailureReason? {
        if (results.any { it.category == BackupFailureCategory.DESTINATION_PERMISSION }) {
            return AutoBackupFailureReason.DESTINATION_PERMISSION
        }
        val firstFailure = results.firstOrNull { !it.success && it.category != null } ?: return null
        return when (firstFailure.category) {
            BackupFailureCategory.DESTINATION_PERMISSION -> AutoBackupFailureReason.DESTINATION_PERMISSION
            BackupFailureCategory.DESTINATION_WRITE ->
                if (firstFailure.insufficientStorage) AutoBackupFailureReason.DESTINATION_FULL
                else AutoBackupFailureReason.DESTINATION_WRITE
            BackupFailureCategory.SOURCE -> AutoBackupFailureReason.SOURCE
            BackupFailureCategory.VERIFY -> AutoBackupFailureReason.VERIFY
            null -> null
        }
    }
}
