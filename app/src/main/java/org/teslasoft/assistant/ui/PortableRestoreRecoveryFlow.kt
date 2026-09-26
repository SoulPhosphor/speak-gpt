/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.portable.SelectedCategoryRestoreTransaction
import org.teslasoft.assistant.preferences.backup.portable.UnifiedPortableRestore
import org.teslasoft.assistant.preferences.backup.portable.UnifiedPortableRestoreCoordinator
import org.teslasoft.assistant.ui.activities.LogsActivity

/**
 * Fail-closed foreground notice for an outer restore transaction whose exact
 * rollback could not finish. The durable journal remains authoritative.
 */
object PortableRestoreRecoveryFlow {
    @Volatile private var dialogOpen = false

    fun showIfPending(activity: Activity): Boolean {
        if (UnifiedPortableRestoreCoordinator.isActive()) return true
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
        val owner = WeakReference(activity)
        UnifiedPortableRestoreCoordinator.recoverPendingAsyncDetailed(
            activity.applicationContext
        ) { result ->
            val current = owner.get() ?: return@recoverPendingAsyncDetailed
            current.runOnUiThread {
                runCatching { progress.dismiss() }
                if (result == SelectedCategoryRestoreTransaction.RecoveryResult.Success) {
                    if (!current.isFinishing) current.recreate()
                } else if (!current.isFinishing) {
                    showRecoveryFailedDialog(
                        current,
                        result as SelectedCategoryRestoreTransaction.RecoveryResult.Failed
                    )
                }
            }
        }
    }

    private fun showRecoveryFailedDialog(
        activity: Activity,
        failure: SelectedCategoryRestoreTransaction.RecoveryResult.Failed
    ) {
        if (activity.isFinishing || dialogOpen) return
        dialogOpen = true
        val problem = recoveryProblem(activity, failure)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.portable_restore_recovery_failed_title)
            .setMessage(activity.getString(R.string.portable_restore_recovery_failed_message, problem))
            .setCancelable(false)
            .setPositiveButton(R.string.portable_restore_exit_backup_restore) { _, _ ->
                activity.finish()
            }
            .setNeutralButton(R.string.health_btn_view_log, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                openErrorLog(activity)
            }
        }
        dialog.setOnDismissListener { dialogOpen = false }
        dialog.show()
    }

    private fun recoveryProblem(
        activity: Activity,
        failure: SelectedCategoryRestoreTransaction.RecoveryResult.Failed
    ): String = when (failure.step) {
        SelectedCategoryRestoreTransaction.RecoveryStep.READ_JOURNAL ->
            activity.getString(R.string.portable_restore_recovery_reason_unreadable)
        SelectedCategoryRestoreTransaction.RecoveryStep.MISSING_PARTICIPANT ->
            activity.getString(R.string.portable_restore_recovery_reason_unsupported)
        SelectedCategoryRestoreTransaction.RecoveryStep.ROLLBACK ->
            activity.getString(
                R.string.portable_restore_recovery_reason_rollback,
                recoveryArea(activity, failure.categoryKey)
            )
        SelectedCategoryRestoreTransaction.RecoveryStep.DELETE_JOURNAL ->
            activity.getString(R.string.portable_restore_recovery_reason_cleanup)
    }

    private fun recoveryArea(activity: Activity, key: String?): String = when (key) {
        "chats", "chat_image_dependencies" ->
            activity.getString(R.string.restore_category_chats)
        "generated_images" ->
            activity.getString(R.string.restore_category_generated_images)
        "identity_bundle" ->
            activity.getString(R.string.portable_restore_recovery_area_identity)
        "companion_memory_store" ->
            activity.getString(R.string.portable_restore_recovery_area_shared_memory)
        "profile_images" ->
            activity.getString(R.string.restore_category_profile_images)
        "model_endpoint_settings" ->
            activity.getString(R.string.restore_category_model_settings)
        "lorebooks" ->
            activity.getString(R.string.restore_category_lorebooks)
        "settings" ->
            activity.getString(R.string.restore_category_settings)
        else ->
            activity.getString(R.string.portable_restore_recovery_area_other)
    }

    private fun openErrorLog(activity: Activity) {
        try {
            activity.startActivity(Intent(activity, LogsActivity::class.java).putExtra("type", "crash"))
        } catch (_: Exception) { }
    }
}
