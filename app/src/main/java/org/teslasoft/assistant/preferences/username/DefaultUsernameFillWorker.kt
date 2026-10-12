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

/** Background runner for [DefaultUsernameFill]. Unfinished work is kept as a
 *  pending retry for the next app start (UsernameFillState), not retried
 *  here: a locked or full store does not recover within the same process. */
class DefaultUsernameFillWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        try {
            DefaultUsernameFill.runAll(applicationContext)
        } catch (_: Throwable) {
            UsernameFillState.finish(
                applicationContext,
                DefaultUsernameFill.RunOutcome(otherFailure = true),
                complete = false
            )
        }
        return Result.success()
    }
}
