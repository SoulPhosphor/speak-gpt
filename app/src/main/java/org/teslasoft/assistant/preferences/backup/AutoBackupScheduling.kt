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

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues / cancels the single periodic WorkManager job that drives Automatic
 * Backups (owner ruling, July 23 2026). Everything goes through ONE unique work
 * name so a duplicate job can never be scheduled — [WorkManager
 * .enqueueUniquePeriodicWork] with [ExistingPeriodicWorkPolicy.UPDATE] keeps a
 * single job whose period tracks the chosen frequency.
 *
 * The job period is the frequency's interval, floored at WorkManager's 15-minute
 * minimum (all four frequencies exceed it, so the floor is only a safety net).
 * The period is a MINIMUM spacing the OS may defer, not an exact-timing promise;
 * the worker's own due gate ([AutoBackupController]) makes an early or late fire
 * harmless, and the app-open check catches up whatever the job misses.
 *
 * All calls are best-effort: if WorkManager is unavailable for any reason,
 * failing to schedule must never crash the app — the app-open check still runs.
 */
object AutoBackupScheduling {

    /** The one unique work name — the whole point is that only ONE job exists. */
    const val UNIQUE_WORK = "auto_recovery_backup"

    /**
     * Reconcile the scheduled job with the current settings: enqueue (or update)
     * the unique periodic job when Automatic Backups are enabled AND a
     * destination folder is chosen; cancel it otherwise. Idempotent — safe to
     * call from the UI on every relevant change and from startup.
     */
    fun sync(context: Context) {
        try {
            val enabled = RecoveryBackupState.isEnabled(context)
            val hasDestination = RecoveryBackupState.getAutoFolderUri(context) != null
            if (enabled && hasDestination) {
                enqueue(context, RecoveryBackupState.getAutoFrequency(context))
            } else {
                cancel(context)
            }
        } catch (_: Throwable) { /* scheduling is best-effort; app-open check still runs */ }
    }

    private fun enqueue(context: Context, frequency: BackupFrequency) {
        val floorMinutes = PERIODIC_MIN_MINUTES
        val requestedMinutes = AutoBackupScheduler.intervalMillis(frequency) / 60_000L
        val periodMinutes = maxOf(requestedMinutes, floorMinutes)
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(periodMinutes, TimeUnit.MINUTES)
            .build()
        // UPDATE (not KEEP) so a frequency change re-periods the SAME unique job
        // instead of leaving the old cadence in place; uniqueness still holds.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
    }

    /** WorkManager's floor for a periodic interval, in minutes. */
    private const val PERIODIC_MIN_MINUTES = 15L
}
