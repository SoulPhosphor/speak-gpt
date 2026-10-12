/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.damaged.DamagedChatLog
import org.teslasoft.assistant.preferences.username.UsernameFillState

/**
 * Shows the notices queued by Apply to Previous Chats with No Name the next
 * time a screen is available, combined into one pop-up (owner rulings, Oct 12
 * 2026). A notice is marked shown only once its pop-up is on screen, so one
 * whose screen closed first is shown on the next screen instead.
 */
object UsernameFillNoticeDialog {

    @Volatile private var showing = false

    fun showIfPending(activity: Activity) {
        if (showing) return
        val app = activity.applicationContext
        Thread {
            val pending = UsernameFillState.peekPending(app) ?: return@Thread
            val damagedTitles = if (UsernameFillState.Notice.DAMAGED in pending.notices) {
                val titles = DamagedChatLog.detections(app)
                    .flatMap { it.chats }
                    .associate { it.chatId to it.title }
                // A chat deleted since is no longer in the log, so it is left out.
                pending.damagedChatIds.filter { it in titles }.map { id ->
                    titles[id] ?: app.getString(R.string.damaged_value_unavailable)
                }
            } else {
                emptyList()
            }
            val parts = ArrayList<String>()
            if (UsernameFillState.Notice.RESTORE in pending.notices) parts.add(app.getString(R.string.username_fill_notice_restore))
            if (UsernameFillState.Notice.STORAGE in pending.notices) parts.add(app.getString(R.string.username_fill_notice_storage))
            if (UsernameFillState.Notice.LOCKED in pending.notices) parts.add(app.getString(R.string.username_fill_notice_locked))
            if (UsernameFillState.Notice.DAMAGED in pending.notices && damagedTitles.isNotEmpty()) {
                parts.add(app.getString(R.string.username_fill_notice_damaged) + "\n\n" + damagedTitles.joinToString("\n"))
            }
            if (parts.isEmpty()) {
                UsernameFillState.markShown(app, pending)
                return@Thread
            }
            activity.runOnUiThread {
                if (showing || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                showing = true
                MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
                    .setMessage(parts.joinToString("\n\n"))
                    .setPositiveButton(R.string.btn_ok) { _, _ -> }
                    .setOnDismissListener { showing = false }
                    .show()
                Thread { UsernameFillState.markShown(app, pending) }.start()
            }
        }.start()
    }
}
