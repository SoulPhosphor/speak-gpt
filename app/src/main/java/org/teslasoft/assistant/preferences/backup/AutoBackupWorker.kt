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
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * The WorkManager job that gives Automatic Backups a RELIABLE trigger — one
 * that survives the app being closed, unlike the app-open catch-up check
 * (owner ruling, July 23 2026). It is enqueued as UNIQUE periodic work
 * ([AutoBackupScheduling]) so duplicate jobs can never pile up, and it does the
 * bare minimum here: hand off to [AutoBackupController.runIfDue], which owns the
 * enabled/destination/permission/due gate and all state recording, then maps
 * the outcome to the [Result] WorkManager needs to decide whether to retry.
 *
 * A [Worker] (not CoroutineWorker) runs [doWork] on WorkManager's background
 * executor, which is exactly where the SQLCipher + SAF work belongs — never the
 * main thread. Android may DEFER a run to save battery — the interval is a
 * minimum spacing, never an exact-timing promise.
 *
 * Result mapping (owner ruling, July 23 2026):
 *  - [AutoBackupController.Outcome.PERMISSION_LOST] → [Result.failure] — a lost
 *    SAF grant is never retried automatically (an immediate retry would just
 *    hit the same wall); the fix requires the user to repair the destination,
 *    and the next natural trigger (periodic tick or app-open) re-checks.
 *  - [AutoBackupController.Outcome.RETRYABLE_FAILURE] → [Result.retry], bounded
 *    by [AutoBackupScheduler.shouldRetryNow] against [runAttemptCount] — a
 *    transient per-type failure (source/destination-write/verify) gets a short
 *    WorkManager backoff-retry within this due window; once the bound is
 *    exceeded it becomes [Result.failure] until the next natural trigger.
 *  - Everything else (disabled, no destination, not due, already running, or a
 *    fully successful pass) → [Result.success] — the worker instance had
 *    nothing more to do; this is not a worker failure.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            when (AutoBackupController.runIfDue(applicationContext)) {
                AutoBackupController.Outcome.PERMISSION_LOST -> Result.failure()
                AutoBackupController.Outcome.RETRYABLE_FAILURE ->
                    if (AutoBackupScheduler.shouldRetryNow(runAttemptCount)) Result.retry() else Result.failure()
                else -> Result.success()
            }
        } catch (_: Throwable) {
            // The controller already swallows and records its own failures; this
            // is a last-resort guard so an unexpected exception here never
            // crashes the worker. Treat it the same as any other transient
            // failure: a bounded backoff-retry, then give up for this window.
            if (AutoBackupScheduler.shouldRetryNow(runAttemptCount)) Result.retry() else Result.failure()
        }
    }
}
