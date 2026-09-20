/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink
import org.teslasoft.assistant.preferences.backup.portable.PortableChatRestorePlan
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreCategory
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreOutcome
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreOutcomeStore
import org.teslasoft.assistant.preferences.backup.portable.SelectedCategoryRestoreTransaction
import org.teslasoft.assistant.preferences.backup.portable.UnifiedPortableRestore

/** Displays the service-persisted restore result with the existing approved copy. */
object PortableRestoreOutcomeFlow {
    @Volatile private var dialogOpen = false
    @Volatile private var dialogToken = 0L
    @Volatile private var dialogOwner: WeakReference<Activity>? = null

    @Synchronized
    fun showIfPending(activity: Activity): Boolean {
        val owner = dialogOwner?.get()
        if (dialogOpen && owner != null && owner !== activity && owner.isDestroyed) {
            dialogOpen = false
            dialogOwner = null
        }
        if (activity.isFinishing || (dialogOpen && owner === activity)) {
            return PortableRestoreOutcomeStore.hasPending(activity)
        }
        if (dialogOpen) return PortableRestoreOutcomeStore.hasPending(activity)
        if (!PortableRestoreOutcomeStore.hasPending(activity)) return false
        dialogOpen = true
        dialogOwner = WeakReference(activity)
        PortableRestoreOutcomeStore.loadAsync(activity.applicationContext) { outcome ->
            activity.runOnUiThread {
                if (dialogOwner?.get() !== activity || activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                when (outcome) {
                    is PortableRestoreOutcome.Success -> showSuccess(activity, outcome.report)
                    is PortableRestoreOutcome.PackageFailure -> showFailure(
                        activity, portableErrorMessage(activity, outcome.error)
                    )
                    is PortableRestoreOutcome.BuildFailure -> showBuildFailure(activity, outcome)
                    PortableRestoreOutcome.NothingAvailable -> showFailure(
                        activity, activity.getString(R.string.portable_restore_nothing_available)
                    )
                    is PortableRestoreOutcome.TransactionFailure ->
                        showTransactionFailure(activity, outcome)
                    null -> onActivityDestroyed(activity)
                }
            }
        }
        return true
    }

    /** Rotation destroys the dialog but must not acknowledge its outcome. */
    @Synchronized
    fun onActivityDestroyed(activity: Activity) {
        if (dialogOwner?.get() === activity) {
            dialogOpen = false
            dialogOwner = null
            dialogToken++
        }
    }

    private fun showSuccess(activity: Activity, report: PortableRestoreOutcome.Report) {
        val token = beginDialog()
        MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.portable_success_title)
            .setMessage(R.string.portable_success_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                when {
                    report.lines.isNotEmpty() -> showReport(activity, report)
                    report.removedLorebookLinks.isNotEmpty() ->
                        showRemovedLinks(activity, report.removedLorebookLinks)
                    else -> finish(activity)
                }
            }
            .setOnDismissListener { endDialog(token) }
            .show()
    }

    private fun showReport(activity: Activity, report: PortableRestoreOutcome.Report) {
        val token = beginDialog()
        val lines = report.lines.map { line ->
            when (line) {
                is PortableRestoreOutcome.Report.Line.ConflictCount -> activity.getString(
                    R.string.portable_report_conflict_count,
                    categoryName(activity, line.category),
                    line.count
                )
                is PortableRestoreOutcome.Report.Line.NamedConflicts -> activity.getString(
                    R.string.portable_report_conflicts,
                    categoryName(activity, line.category),
                    if (line.names.isEmpty()) line.fallbackCount.toString()
                    else line.names.joinToString(", ")
                )
                is PortableRestoreOutcome.Report.Line.LongerChats ->
                    activity.resources.getQuantityString(
                        R.plurals.portable_report_longer_chats, line.count, line.count
                    )
                is PortableRestoreOutcome.Report.Line.ProtectedImages ->
                    activity.resources.getQuantityString(
                        R.plurals.portable_report_protected_images, line.count, line.count
                    )
            }
        }
        MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.portable_report_title)
            .setMessage(activity.getString(R.string.portable_report_intro) +
                "\n\n" + lines.joinToString("\n"))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                if (report.removedLorebookLinks.isNotEmpty()) {
                    showRemovedLinks(activity, report.removedLorebookLinks)
                } else finish(activity)
            }
            .setOnDismissListener { endDialog(token) }
            .show()
    }

    private fun showRemovedLinks(activity: Activity, links: List<RemovedLorebookLink>) {
        val token = beginDialog()
        val lines = links.joinToString("\n") {
            activity.getString(
                R.string.companion_backup_report_line, it.companionLabel, it.lorebookName
            )
        }
        val body = activity.getString(R.string.companion_backup_report_intro) +
            "\n\n" + lines + "\n\n" +
            activity.getString(R.string.companion_backup_report_footer)
        MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.companion_backup_report_title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ -> finish(activity) }
            .setOnDismissListener { endDialog(token) }
            .show()
    }

    private fun showBuildFailure(
        activity: Activity,
        failed: PortableRestoreOutcome.BuildFailure
    ) {
        val reason = activity.getString(when (failed.reason) {
            UnifiedPortableRestore.BuildFailure.EMPTY_SELECTION ->
                R.string.portable_restore_reason_empty_selection
            UnifiedPortableRestore.BuildFailure.MISSING_CATEGORY_ARTIFACT ->
                R.string.portable_restore_reason_missing_artifact
            UnifiedPortableRestore.BuildFailure.DEPENDENCY_VALIDATION_FAILED ->
                R.string.portable_restore_reason_dependency_validation
        })
        val detail = failed.category?.let {
            activity.getString(
                R.string.portable_restore_category_failure, categoryName(activity, it), reason
            )
        } ?: activity.getString(
            R.string.portable_restore_failure_reason, reason.removeSuffix(".")
        )
        showFailure(activity, detail)
    }

    private fun showTransactionFailure(
        activity: Activity,
        failed: PortableRestoreOutcome.TransactionFailure
    ) {
        when (failed.dataState) {
            SelectedCategoryRestoreTransaction.DataState.RESTORED_CLEANUP_PENDING ->
                showOutcome(
                    activity,
                    R.string.portable_success_title,
                    activity.getString(R.string.portable_restore_cleanup_pending),
                    retryRecovery = true
                )
            SelectedCategoryRestoreTransaction.DataState.RECOVERY_REQUIRED ->
                showOutcome(
                    activity,
                    R.string.portable_restore_recovery_title,
                    activity.getString(R.string.portable_restore_recovery_message),
                    retryRecovery = true
                )
            SelectedCategoryRestoreTransaction.DataState.UNCHANGED -> {
                val category = PortableRestoreCategory.entries.firstOrNull {
                    it.key == failed.categoryKey
                }
                val reason = if (
                    failed.reason == SelectedCategoryRestoreTransaction.Failure.VALIDATION_FAILED &&
                    category == PortableRestoreCategory.CHATS
                ) chatValidationMessage(activity, failed.chatValidationFailure)
                else transactionReason(activity, failed.reason)
                val detail = category?.let {
                    activity.getString(
                        R.string.portable_restore_category_failure,
                        categoryName(activity, it),
                        reason
                    )
                } ?: activity.getString(
                    R.string.portable_restore_failure_reason, reason.removeSuffix(".")
                )
                showFailure(activity, detail)
            }
        }
    }

    private fun showFailure(activity: Activity, detail: String) {
        showOutcome(
            activity,
            R.string.portable_failure_title,
            activity.getString(R.string.portable_failure_message) + "\n\n" + detail
        )
    }

    private fun showOutcome(
        activity: Activity,
        title: Int,
        message: String,
        retryRecovery: Boolean = false
    ) {
        val token = beginDialog()
        MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                finish(activity)
                if (retryRecovery) PortableRestoreRecoveryFlow.showIfPending(activity)
            }
            .setOnDismissListener { endDialog(token) }
            .show()
    }

    private fun finish(activity: Activity) {
        PortableRestoreOutcomeStore.acknowledge(activity)
        dialogOpen = false
        dialogOwner = null
    }

    @Synchronized
    private fun beginDialog(): Long {
        dialogOpen = true
        return ++dialogToken
    }

    @Synchronized
    private fun endDialog(token: Long) {
        if (dialogToken == token) {
            dialogOpen = false
            dialogOwner = null
        }
    }

    private fun categoryName(activity: Activity, category: PortableRestoreCategory): String =
        activity.getString(when (category) {
            PortableRestoreCategory.CHATS -> R.string.restore_category_chats
            PortableRestoreCategory.GENERATED_IMAGES -> R.string.restore_category_generated_images
            PortableRestoreCategory.COMPANIONS -> R.string.restore_category_companions
            PortableRestoreCategory.GLAMOURS -> R.string.restore_category_glamours
            PortableRestoreCategory.ROLEPLAY -> R.string.restore_category_roleplay
            PortableRestoreCategory.PROFILE_IMAGES -> R.string.restore_category_profile_images
            PortableRestoreCategory.ACTIVATION_PROMPTS -> R.string.restore_category_activation_prompts
            PortableRestoreCategory.SYSTEM_PROMPTS -> R.string.restore_category_system_prompts
            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS -> R.string.restore_category_model_settings
            PortableRestoreCategory.MODEL_RULES -> R.string.restore_category_model_rules
            PortableRestoreCategory.MEMORIES -> R.string.restore_category_memories
            PortableRestoreCategory.LOREBOOKS -> R.string.restore_category_lorebooks
        })

    private fun portableErrorMessage(
        activity: Activity,
        error: PortablePackageFormat.RestoreError
    ): String = activity.getString(when (error) {
        PortablePackageFormat.RestoreError.MISTYPED_CODE -> R.string.backup_err_mistyped_code
        PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER -> R.string.backup_err_wrong_key_or_header
        PortablePackageFormat.RestoreError.UNSUPPORTED_PROTECTION -> R.string.backup_err_unsupported_protection
        PortablePackageFormat.RestoreError.TOO_LARGE -> R.string.portable_restore_too_large
        PortablePackageFormat.RestoreError.NOT_A_V2_PACKAGE,
        PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED -> R.string.backup_err_damaged
    })

    private fun transactionReason(
        activity: Activity,
        reason: SelectedCategoryRestoreTransaction.Failure
    ): String = activity.getString(when (reason) {
        SelectedCategoryRestoreTransaction.Failure.EMPTY_SELECTION ->
            R.string.portable_restore_reason_empty_selection
        SelectedCategoryRestoreTransaction.Failure.DUPLICATE_CATEGORY ->
            R.string.portable_restore_reason_duplicate_category
        SelectedCategoryRestoreTransaction.Failure.PENDING_RECOVERY ->
            R.string.portable_restore_reason_pending_recovery
        SelectedCategoryRestoreTransaction.Failure.VALIDATION_FAILED ->
            R.string.portable_restore_reason_validation_failed
        SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED ->
            R.string.portable_restore_reason_staging_failed
        SelectedCategoryRestoreTransaction.Failure.JOURNAL_FAILED ->
            R.string.portable_restore_reason_journal_failed
        SelectedCategoryRestoreTransaction.Failure.APPLY_FAILED ->
            R.string.portable_restore_reason_apply_failed
        SelectedCategoryRestoreTransaction.Failure.ROLLBACK_FAILED ->
            R.string.portable_restore_reason_rollback_failed
    })

    private fun chatValidationMessage(
        activity: Activity,
        reason: PortableChatRestorePlan.Reason?
    ): String = activity.getString(when (reason) {
        PortableChatRestorePlan.Reason.NOT_A_CHATS_ARTIFACT -> R.string.portable_chat_invalid_file_type
        PortableChatRestorePlan.Reason.UNSUPPORTED_FORMAT -> R.string.portable_chat_unsupported_format
        PortableChatRestorePlan.Reason.INCOMPLETE_ARTIFACT -> R.string.portable_chat_incomplete
        PortableChatRestorePlan.Reason.MALFORMED -> R.string.portable_chat_malformed
        PortableChatRestorePlan.Reason.UNSAFE_CHAT_ID -> R.string.portable_chat_unsafe_id
        PortableChatRestorePlan.Reason.IDENTITY_MISMATCH -> R.string.portable_chat_identity_mismatch
        PortableChatRestorePlan.Reason.DUPLICATE_CHAT_ID -> R.string.portable_chat_duplicate_id
        PortableChatRestorePlan.Reason.MALFORMED_MESSAGE_ID -> R.string.portable_chat_malformed_message_id
        PortableChatRestorePlan.Reason.DUPLICATE_MESSAGE_ID -> R.string.portable_chat_duplicate_message_id
        PortableChatRestorePlan.Reason.MALFORMED_FOLDERS -> R.string.portable_chat_malformed_folders
        PortableChatRestorePlan.Reason.MISSING_FOLDER -> R.string.portable_chat_missing_folder
        PortableChatRestorePlan.Reason.UNREFERENCED_FOLDER -> R.string.portable_chat_unreferenced_folder
        PortableChatRestorePlan.Reason.UNSUPPORTED_SETTING_TYPE -> R.string.portable_chat_unsupported_setting
        PortableChatRestorePlan.Reason.FORBIDDEN_CREDENTIAL -> R.string.portable_chat_forbidden_credential
        null -> R.string.portable_restore_validation_failed
    })
}
