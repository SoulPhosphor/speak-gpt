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
 * enabled/destination/permission/due gate and all state recording.
 *
 * A [Worker] (not CoroutineWorker) runs [doWork] on WorkManager's background
 * executor, which is exactly where the SQLCipher + SAF work belongs — never the
 * main thread. It always returns success: periodic work reschedules itself for
 * the next interval regardless, and a transient miss is caught either by the
 * next periodic fire or by the app-open check. Android may DEFER a run to save
 * battery — the interval is a minimum spacing, never an exact-timing promise.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        try {
            AutoBackupController.runIfDue(applicationContext)
        } catch (_: Throwable) {
            // The controller already swallows and records its own failures; this
            // is a last-resort guard so a backup miss never crashes the worker.
        }
        return Result.success()
    }
}
