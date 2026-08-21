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
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.chat.ChatExportFormat
import org.teslasoft.assistant.ui.chat.ChatExportOptions
import org.teslasoft.assistant.ui.widgets.AppDropdown

object ChatExportDialog {

    fun show(context: Context, onExport: (ChatExportOptions) -> Unit) {
        val body = LayoutInflater.from(context).inflate(R.layout.dialog_chat_export, null)
        val formatValue = body.findViewById<TextView>(R.id.chat_export_format_value)
        val formats = ChatExportFormat.values()
        var selectedFormat = formats.first()

        formatValue.text = selectedFormat.label
        formatValue.setOnClickListener {
            AppDropdown.show(
                anchor = formatValue,
                labels = formats.map { it.label },
                selectedIndex = formats.indexOf(selectedFormat)
            ) { index ->
                selectedFormat = formats[index]
                formatValue.text = selectedFormat.label
            }
        }

        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions, null)
        val export = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        cancel.setText(R.string.chat_export_cancel)
        export.setText(R.string.chat_export_action)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
            addView(actions)
        }
        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setView(container)
            .setCancelable(true)
            .create()

        cancel.setOnClickListener { dialog.dismiss() }
        export.setOnClickListener {
            val options = ChatExportOptions(
                format = selectedFormat,
                includeDate = body.findViewById<MaterialSwitch>(R.id.chat_export_include_date).isChecked,
                includeTime = body.findViewById<MaterialSwitch>(R.id.chat_export_include_time).isChecked,
                includeModel = body.findViewById<MaterialSwitch>(R.id.chat_export_include_model).isChecked,
                includeUserTokenCount = body.findViewById<MaterialSwitch>(R.id.chat_export_include_user_tokens).isChecked,
                includeCompanionTokenCount =
                    body.findViewById<MaterialSwitch>(R.id.chat_export_include_companion_tokens).isChecked
            )
            dialog.dismiss()
            onExport(options)
        }

        dialog.show()
        AppDropdown.sizeToOptions(formatValue, formats.map { it.label }) {
            context.resources.displayMetrics.widthPixels -
                (context.resources.displayMetrics.density * 64).toInt()
        }
    }
}
