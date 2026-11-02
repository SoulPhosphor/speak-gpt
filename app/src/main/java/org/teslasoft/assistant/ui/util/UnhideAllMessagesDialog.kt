package org.teslasoft.assistant.ui.util

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

/**
 * Show All confirmation (owner wording, Nov 2 2026): makes every message that
 * is hidden from the AI visible again. Standard dialog theme, title and
 * message, and the shared Cancel-then-Okay action row.
 */
object UnhideAllMessagesDialog {

    fun show(context: Context, hiddenCount: Int, onConfirm: () -> Unit) {
        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val okay = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        cancel.setText(R.string.btn_cancel)
        okay.setText(R.string.btn_ok)

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.unhide_all_messages_title)
            .setMessage(context.getString(R.string.unhide_all_messages_body, hiddenCount))
            .setView(actions)
            .create()
        cancel.setOnClickListener { dialog.dismiss() }
        okay.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
    }
}
