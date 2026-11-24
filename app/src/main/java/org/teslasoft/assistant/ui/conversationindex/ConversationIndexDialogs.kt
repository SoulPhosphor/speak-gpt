/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.conversationindex

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.IndexNotice
import org.teslasoft.assistant.ui.activities.EmbeddingModelActivity
import org.teslasoft.assistant.ui.activities.MemoryControlsActivity

/**
 * The conversation index's pop-ups (G.16a and G20, wording approved by the
 * owner on 2026-11-24). Each is a standard dialog with the shared two-action
 * row: the back-out action on the left, the action on the right.
 */
object ConversationIndexDialogs {

    /** Turning Index Conversations on with no on-device search model installed (G31). */
    fun showModelNeeded(activity: Activity): AlertDialog =
        show(
            activity,
            R.string.conversation_index_model_needed_title,
            activity.getString(R.string.conversation_index_model_needed_text),
            R.string.btn_close,
            R.string.conversation_index_open_model_settings
        ) { openModelSettings(activity) }

    /** One notice for an ongoing condition (G20). */
    fun showNotice(activity: Activity, notice: IndexNotice): AlertDialog = when (notice) {
        IndexNotice.MODEL_REMOVED, IndexNotice.MODEL_CANNOT_LOAD -> show(
            activity,
            R.string.conversation_index_paused_title,
            activity.getString(
                if (notice == IndexNotice.MODEL_REMOVED) R.string.conversation_index_paused_model_removed
                else R.string.conversation_index_paused_model_cannot_load
            ),
            R.string.btn_close,
            R.string.conversation_index_open_model_settings
        ) { openModelSettings(activity) }
        IndexNotice.STOPPED -> show(
            activity,
            R.string.conversation_index_stopped_title,
            activity.getString(R.string.conversation_index_stopped_text),
            R.string.btn_close,
            R.string.conversation_index_open_rag_settings
        ) {
            // Already on RAG Active Memory Settings: closing the notice shows it.
            if (activity !is MemoryControlsActivity) activity.startActivity(Intent(activity, MemoryControlsActivity::class.java))
        }
    }

    /** Delete Conversation Index's confirmation (G.16a, revised by the owner on 2026-11-24). */
    fun showDeleteConfirmation(activity: Activity, onDelete: () -> Unit): AlertDialog =
        show(
            activity,
            R.string.conversation_index_delete_title,
            activity.getString(R.string.conversation_index_delete_text),
            R.string.btn_cancel,
            R.string.btn_delete,
            onDelete
        )

    private fun openModelSettings(activity: Activity) {
        activity.startActivity(Intent(activity, EmbeddingModelActivity::class.java))
    }

    private fun show(
        activity: Activity,
        titleRes: Int,
        message: String,
        backOutRes: Int,
        actionRes: Int,
        onAction: () -> Unit
    ): AlertDialog {
        val actions = LayoutInflater.from(activity).inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setMessage(message)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(backOutRes)
            setOnClickListener { dialog.dismiss() }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(actionRes)
            setOnClickListener {
                dialog.dismiss()
                onAction()
            }
        }
        dialog.show()
        return dialog
    }
}
