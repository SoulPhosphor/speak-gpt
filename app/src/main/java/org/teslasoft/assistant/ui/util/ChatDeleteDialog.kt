/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionDecision
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionDialogVariant
import org.teslasoft.assistant.preferences.chatdeletion.ChatDeletionPolicyResult

data class ChatDeletePrompt(
    @StringRes val titleRes: Int = R.string.chat_delete_title,
    @StringRes val ordinaryMessageRes: Int? = null
)

/** Shared ordinary/keep-images/three-choice chat deletion dialog family. */
object ChatDeleteDialog {

    fun show(
        context: Context,
        prompt: ChatDeletePrompt,
        policy: ChatDeletionPolicyResult,
        onDecision: (ChatDeletionDecision) -> Unit
    ) {
        when (policy.variant) {
            ChatDeletionDialogVariant.DELETE_ALL,
            ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES ->
                showThreeActions(context, prompt, policy, onDecision)
            else -> showTwoActions(context, prompt, policy, onDecision)
        }
    }

    fun showFolderWarning(
        context: Context,
        onOkay: () -> Unit,
        onCancel: () -> Unit
    ) {
        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val okay = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        cancel.setText(R.string.btn_cancel)
        okay.setText(R.string.btn_ok)

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.folder_delete_title)
            .setMessage(R.string.folder_delete_message)
            .setView(actions)
            .setCancelable(true)
            .create()
        cancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        okay.setOnClickListener {
            dialog.dismiss()
            onOkay()
        }
        dialog.setOnCancelListener { onCancel() }
        dialog.show()
    }

    private fun showTwoActions(
        context: Context,
        prompt: ChatDeletePrompt,
        policy: ChatDeletionPolicyResult,
        onDecision: (ChatDeletionDecision) -> Unit
    ) {
        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val okay = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        cancel.setText(R.string.btn_cancel)
        okay.setText(R.string.btn_ok)

        val builder = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(prompt.titleRes)
            .setView(actions)
            .setCancelable(true)
        message(context, prompt, policy)?.let { builder.setMessage(it) }
        val dialog = builder.create()
        cancel.setOnClickListener {
            dialog.dismiss()
            onDecision(ChatDeletionDecision.CANCEL)
        }
        okay.setOnClickListener {
            dialog.dismiss()
            onDecision(ChatDeletionDecision.DELETE_CHAT_ONLY)
        }
        dialog.setOnCancelListener { onDecision(ChatDeletionDecision.CANCEL) }
        dialog.show()
    }

    private fun showThreeActions(
        context: Context,
        prompt: ChatDeletePrompt,
        policy: ChatDeletionPolicyResult,
        onDecision: (ChatDeletionDecision) -> Unit
    ) {
        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_three_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_cancel_action)
        val chatOnly = actions.findViewById<MaterialButton>(R.id.btn_dialog_middle_action)
        val deleteAll = actions.findViewById<MaterialButton>(R.id.btn_dialog_final_action)
        cancel.setText(R.string.btn_cancel)
        chatOnly.setText(R.string.chat_delete_chat_only)
        deleteAll.setText(R.string.chat_delete_all)

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(prompt.titleRes)
            .setMessage(message(context, prompt, policy))
            .setView(actions)
            .setCancelable(true)
            .create()
        cancel.setOnClickListener {
            dialog.dismiss()
            onDecision(ChatDeletionDecision.CANCEL)
        }
        chatOnly.setOnClickListener {
            dialog.dismiss()
            onDecision(ChatDeletionDecision.DELETE_CHAT_ONLY)
        }
        deleteAll.setOnClickListener {
            dialog.dismiss()
            onDecision(ChatDeletionDecision.DELETE_ALL)
        }
        dialog.setOnCancelListener { onDecision(ChatDeletionDecision.CANCEL) }
        dialog.show()
    }

    private fun message(
        context: Context,
        prompt: ChatDeletePrompt,
        policy: ChatDeletionPolicyResult
    ): CharSequence? {
        val policyMessage = when (policy.variant) {
            ChatDeletionDialogVariant.ORDINARY -> null
            ChatDeletionDialogVariant.KEEP_IMAGES_NOTICE ->
                context.getText(R.string.chat_delete_images_keep_message)
            ChatDeletionDialogVariant.DELETE_ALL ->
                context.getText(R.string.chat_delete_images_all_message)
            ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES ->
                context.getText(R.string.chat_delete_images_unlocked_message)
        }
        // The three-choice warning is the complete approved message. Ordinary
        // and keep-images dialogs preserve any already-approved caller text.
        if (policy.variant == ChatDeletionDialogVariant.DELETE_ALL ||
            policy.variant == ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES
        ) return policyMessage
        val ordinary = prompt.ordinaryMessageRes?.let(context::getText)
        return when {
            ordinary == null -> policyMessage
            policyMessage == null -> ordinary
            else -> "$ordinary\n\n$policyMessage"
        }
    }
}
