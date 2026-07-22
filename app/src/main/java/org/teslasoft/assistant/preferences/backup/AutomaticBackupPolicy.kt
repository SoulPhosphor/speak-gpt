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
 * The pure automatic-backup gate (unit-tested). Every condition the runner
 * must satisfy BEFORE any heavy work is decided here from cheap inputs, in a
 * fixed precedence, so the expensive package build can never start for a
 * run that was never allowed:
 *
 *  1. DISABLED             — the switch is off (the default; never on by itself).
 *  2. NO_FOLDER            — no automatic backup folder has been chosen
 *                            (owner rule: never enable/run without one).
 *  3. FOLDER_INACCESSIBLE  — a folder was chosen but its persisted permission
 *                            is gone (moved/deleted/revoked). Visible state,
 *                            not a silent skip.
 *  4. SETUP_NOT_CONFIRMED  — automatic packages are ALWAYS protected, and the
 *                            Recovery Code setup must be CONFIRMED first: an
 *                            automatic run has no user present to choose, so
 *                            it must never silently produce an unencrypted
 *                            package (standing owner rule) and never operate
 *                            on an unconfirmed secret.
 *  5. NOT_DUE              — the last verified automatic backup is younger
 *                            than the chosen frequency interval.
 *  6. RETRY_TOO_SOON       — the last attempt FAILED and less than
 *                            [MIN_RETRY_MILLIS] has passed: a broken setup
 *                            must not rebuild a multi-store package on every
 *                            single app open.
 *  7. RUN.
 */
object AutomaticBackupPolicy {

    /** Minimum wait between a FAILED attempt and the next attempt. */
    const val MIN_RETRY_MILLIS: Long = 60L * 60L * 1000L

    enum class Gate {
        RUN, DISABLED, NO_FOLDER, FOLDER_INACCESSIBLE,
        SETUP_NOT_CONFIRMED, NOT_DUE, RETRY_TOO_SOON
    }

    /** True when a backup is due under [frequency]: never succeeded, or the
     *  last success is at least one interval old. */
    fun isDue(lastSuccessMillis: Long, nowMillis: Long, frequency: AutoBackupFrequency): Boolean =
        lastSuccessMillis <= 0L || nowMillis - lastSuccessMillis >= frequency.intervalMillis

    fun evaluate(
        enabled: Boolean,
        hasFolder: Boolean,
        folderAccessible: Boolean,
        setupConfirmed: Boolean,
        lastSuccessMillis: Long,
        lastAttemptMillis: Long,
        nowMillis: Long,
        frequency: AutoBackupFrequency
    ): Gate {
        if (!enabled) return Gate.DISABLED
        if (!hasFolder) return Gate.NO_FOLDER
        if (!folderAccessible) return Gate.FOLDER_INACCESSIBLE
        if (!setupConfirmed) return Gate.SETUP_NOT_CONFIRMED
        if (!isDue(lastSuccessMillis, nowMillis, frequency)) return Gate.NOT_DUE
        val lastAttemptFailed = lastAttemptMillis > lastSuccessMillis
        if (lastAttemptFailed && nowMillis - lastAttemptMillis < MIN_RETRY_MILLIS) return Gate.RETRY_TOO_SOON
        return Gate.RUN
    }
}
