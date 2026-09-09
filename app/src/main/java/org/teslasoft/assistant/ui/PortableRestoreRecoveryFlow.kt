/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui

import android.app.Activity
import android.app.Dialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.portable.UnifiedPortableRestore

/**
 * Fail-closed foreground notice for an outer restore transaction whose exact
 * rollback could not finish. The durable journal is the source of truth; no
 * diagnostic log or transient in-memory flag is required to discover it.
 */
object PortableRestoreRecoveryFlow {
    @Volatile private var dialogOpen = false

    fun showIfPending(activity: Activity): Boolean {
        if (activity.isFinishing || dialogOpen ||
            !UnifiedPortableRestore.journalRoot(activity).exists()
        ) return UnifiedPortableRestore.journalRoot(activity).exists()
        showRecoveryDialog(activity)
        return true
    }

    private fun showRecoveryDialog(activity: Activity) {
        if (activity.isFinishing || dialogOpen) return
        dialogOpen = true
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.portable_restore_recovery_title)
            .setMessage(R.string.portable_restore_pending_message)
            .setCancelable(false)
            .setPositiveButton(R.string.portable_restore_retry_recovery) { _, _ ->
                retry(activity)
            }
            .create()
        dialog.setOnDismissListener { dialogOpen = false }
        dialog.show()
    }

    private fun retry(activity: Activity) {
        if (activity.isFinishing) return
        val progress: Dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.portable_restore_retry_progress)
            .setCancelable(false)
            .create()
        progress.show()
        Thread {
            val recovered = try {
                UnifiedPortableRestore.recoverPending(activity.applicationContext)
            } catch (_: Exception) {
                false
            }
            activity.runOnUiThread {
                runCatching { progress.dismiss() }
                if (recovered && !activity.isFinishing) {
                    activity.recreate()
                } else if (!activity.isFinishing) {
                    showRecoveryDialog(activity)
                }
            }
        }.start()
    }
}
