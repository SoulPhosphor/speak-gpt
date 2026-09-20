/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionCoordinator
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionDecision
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionDialogVariant
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionPreflight
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionPreflightResult
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionTarget

/** UI bridge used by every reachable deletion entry point. */
object ChatDeletionRequestCoordinator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-deletion")
    }
    private val main = Handler(Looper.getMainLooper())

    fun requestChats(
        activity: FragmentActivity,
        chatIds: Set<String>,
        prompt: ChatDeletePrompt = ChatDeletePrompt(),
        beforeExecution: () -> Unit = {},
        onCommitted: () -> Unit,
        onCancelled: () -> Unit = {},
        onFailed: () -> Unit = {}
    ) {
        request(
            activity = activity,
            target = ChatDeletionTarget.Chats(chatIds.toSet()),
            prompt = prompt,
            folderFlow = false,
            beforeExecution = beforeExecution,
            onCommitted = onCommitted,
            onCancelled = onCancelled,
            onFailed = onFailed
        )
    }

    /** Dormant until the Phase 6 folder menu calls it. Empty-folder deletion
     * commits directly; nonempty folders receive the exact folder warning
     * before any optional aggregate image choice. */
    fun requestFolder(
        activity: FragmentActivity,
        folderId: String,
        onCommitted: () -> Unit,
        onCancelled: () -> Unit = {},
        onFailed: () -> Unit = {}
    ) {
        request(
            activity = activity,
            target = ChatDeletionTarget.Folder(folderId),
            prompt = ChatDeletePrompt(R.string.folder_delete_title),
            folderFlow = true,
            beforeExecution = {},
            onCommitted = onCommitted,
            onCancelled = onCancelled,
            onFailed = onFailed
        )
    }

    fun recoverBlocking(context: Context) = ChatDeletionCoordinator.get(context).recover()

    private fun request(
        activity: FragmentActivity,
        target: ChatDeletionTarget,
        prompt: ChatDeletePrompt,
        folderFlow: Boolean,
        beforeExecution: () -> Unit,
        onCommitted: () -> Unit,
        onCancelled: () -> Unit,
        onFailed: () -> Unit
    ) {
        val coordinator = ChatDeletionCoordinator.get(activity.applicationContext)
        executor.execute {
            // Settle any older committed deletion before accepting another.
            coordinator.recover()
            val result = coordinator.preflight(target)
            main.post {
                if (!activityUsable(activity)) return@post
                when (result) {
                    is ChatDeletionPreflightResult.Ready -> {
                        if (folderFlow) showFolderFlow(
                            activity,
                            coordinator,
                            result.value,
                            prompt,
                            beforeExecution,
                            onCommitted,
                            onCancelled,
                            onFailed
                        ) else showPolicyDialog(
                            activity,
                            coordinator,
                            result.value,
                            prompt,
                            beforeExecution,
                            onCommitted,
                            onCancelled,
                            onFailed
                        )
                    }
                    is ChatDeletionPreflightResult.Failure -> fail(activity, onFailed)
                }
            }
        }
    }

    private fun showFolderFlow(
        activity: FragmentActivity,
        coordinator: ChatDeletionCoordinator,
        preflight: ChatDeletionPreflight,
        prompt: ChatDeletePrompt,
        beforeExecution: () -> Unit,
        onCommitted: () -> Unit,
        onCancelled: () -> Unit,
        onFailed: () -> Unit
    ) {
        if (preflight.chatIds.isEmpty()) {
            execute(
                activity, coordinator, preflight, ChatDeletionDecision.DELETE_CHAT_ONLY,
                beforeExecution, onCommitted, onFailed
            )
            return
        }
        ChatDeleteDialog.showFolderWarning(
            activity,
            onOkay = {
                when (preflight.policy.variant) {
                    ChatDeletionDialogVariant.DELETE_ALL,
                    ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES ->
                        showPolicyDialog(
                            activity, coordinator, preflight, prompt, beforeExecution,
                            onCommitted, onCancelled, onFailed
                        )
                    else -> execute(
                        activity, coordinator, preflight, ChatDeletionDecision.DELETE_CHAT_ONLY,
                        beforeExecution, onCommitted, onFailed
                    )
                }
            },
            onCancel = onCancelled
        )
    }

    private fun showPolicyDialog(
        activity: FragmentActivity,
        coordinator: ChatDeletionCoordinator,
        preflight: ChatDeletionPreflight,
        prompt: ChatDeletePrompt,
        beforeExecution: () -> Unit,
        onCommitted: () -> Unit,
        onCancelled: () -> Unit,
        onFailed: () -> Unit
    ) {
        ChatDeleteDialog.show(activity, prompt, preflight.policy) { decision ->
            if (decision == ChatDeletionDecision.CANCEL) {
                onCancelled()
            } else {
                execute(
                    activity, coordinator, preflight, decision, beforeExecution,
                    onCommitted, onFailed
                )
            }
        }
    }

    private fun execute(
        activity: FragmentActivity,
        coordinator: ChatDeletionCoordinator,
        preflight: ChatDeletionPreflight,
        decision: ChatDeletionDecision,
        beforeExecution: () -> Unit,
        onCommitted: () -> Unit,
        onFailed: () -> Unit
    ) {
        beforeExecution()
        executor.execute {
            val result = coordinator.execute(preflight, decision) {
                main.post { if (activityUsable(activity)) onCommitted() }
            }
            if (!result.cleanupComplete) {
                main.post {
                    if (!result.metadataCommitted && activityUsable(activity)) onFailed()
                    Toast.makeText(
                        activity.applicationContext,
                        R.string.label_sorry_action_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun fail(activity: FragmentActivity, onFailed: () -> Unit) {
        onFailed()
        Toast.makeText(activity, R.string.label_sorry_action_failed, Toast.LENGTH_LONG).show()
    }

    private fun activityUsable(activity: FragmentActivity): Boolean =
        !activity.isFinishing && !activity.isDestroyed
}
