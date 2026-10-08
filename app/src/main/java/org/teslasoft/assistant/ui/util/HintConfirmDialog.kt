/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.util

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R

/**
 * A two-action explanatory confirmation (Cancel left, Okay right) with a
 * "hide this hint" switch on its own line beneath the buttons, on by default
 * (owner ruling, Oct 3 2026). Okay reports the switch so the caller can skip
 * the hint next time; Cancel changes nothing.
 */
object HintConfirmDialog {
    fun show(
        activity: Activity,
        titleRes: Int,
        messageRes: Int,
        hideLabelRes: Int,
        onConfirm: (hideNextTime: Boolean) -> Unit
    ) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val actions = activity.layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, content, false)
        val toggleRow = activity.layoutInflater.inflate(R.layout.view_dialog_hint_toggle, content, false)
        content.addView(actions)
        content.addView(toggleRow)
        toggleRow.findViewById<TextView>(R.id.text_hint_toggle).setText(hideLabelRes)
        val toggle = toggleRow.findViewById<MaterialSwitch>(R.id.switch_hint_toggle)
        toggleRow.findViewById<TextView>(R.id.text_hint_toggle).setOnClickListener { toggle.toggle() }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(content)
            .create()

        actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener {
                dialog.dismiss()
                onConfirm(toggle.isChecked)
            }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }
}
