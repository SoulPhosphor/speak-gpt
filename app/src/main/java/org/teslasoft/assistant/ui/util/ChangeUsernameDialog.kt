/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R

/**
 * Per-chat Change Your Username pop-up. The field opens empty (someone
 * opening it wants a different name). Save hands back the trimmed name; an
 * empty save hands back "" so the chat returns to its normal names.
 */
object ChangeUsernameDialog {

    fun show(context: Context, onSave: (String) -> Unit) {
        val body = LayoutInflater.from(context).inflate(R.layout.dialog_change_username, null)
        val field = body.findViewById<TextInputEditText>(R.id.chat_username_edit_text)

        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val save = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        cancel.setText(R.string.btn_cancel)
        save.setText(R.string.btn_save)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
            addView(actions)
        }
        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.chat_change_username_title)
            .setView(container)
            .setCancelable(true)
            .create()

        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            onSave(field.text?.toString().orEmpty().trim())
            dialog.dismiss()
        }
        dialog.show()
    }
}
