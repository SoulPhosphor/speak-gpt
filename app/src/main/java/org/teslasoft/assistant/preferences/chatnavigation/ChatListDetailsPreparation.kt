/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Prepares the chat list's details for every existing chat right after the
 *  app is updated, before the chat list is next opened. */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "chat-list-details-preparation",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ChatListDetailsPreparationWorker>().build()
        )
    }
}

class ChatListDetailsPreparationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        ChatListIndex.prepareAll(applicationContext)
        return Result.success()
    }
}
