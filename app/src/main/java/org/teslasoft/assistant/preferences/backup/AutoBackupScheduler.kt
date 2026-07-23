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
     * the current window). It advances unless the destination itself is the
     * problem: a lost/revoked folder leaves the set DUE so the very next trigger
     * re-surfaces the permission-lost state rather than sleeping a whole window
     * on a destination the user still needs to repair. Per-type SOURCE failures
     * (a degraded store) do NOT hold the window open — those are recorded and
     * shown per type, and hammering the schedule would not help them.
     */
    fun shouldAdvanceSchedule(destinationPermissionFailed: Boolean): Boolean =
        !destinationPermissionFailed
}
