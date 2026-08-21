/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

object ChatDeleteDialog {

    fun show(context: Context, onDelete: () -> Unit) {
        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val okay = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        cancel.setText(R.string.btn_cancel)
        okay.setText(R.string.okay)

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.chat_delete_title)
            .setView(actions)
            .setCancelable(true)
            .create()

        cancel.setOnClickListener { dialog.dismiss() }
        okay.setOnClickListener {
            dialog.dismiss()
            onDelete()
        }

        dialog.show()
    }
}
