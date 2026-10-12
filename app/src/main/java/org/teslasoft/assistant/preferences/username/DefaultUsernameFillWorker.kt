/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.username

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Background runner for [DefaultUsernameFill]: retries a few times when a
 *  chat could not be read or saved, then stops until the next trigger. */
class DefaultUsernameFillWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val complete = try {
            DefaultUsernameFill.runAll(applicationContext)
        } catch (_: Throwable) {
            false
        }
        return when {
            complete -> Result.success()
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
