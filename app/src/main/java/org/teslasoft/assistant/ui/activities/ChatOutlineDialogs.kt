/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.app.Activity
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.widgets.AppDropdown

/**
 * The Chat Outline screen's pop-ups (owner design, Nov 22 2026): the outline
 * limit, Rename, and Redo This Outline with its prompt choice.
 */
object ChatOutlineDialogs {

    /** "Outline Number Reached": Generate New Outline with every tab used. */
    fun showLimit(activity: Activity) {
        val actions = LayoutInflater.from(activity).inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.chat_outline_limit_title)
            .setMessage(R.string.chat_outline_limit_body)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** Rename on a tab's menu; a blank name is ignored by the caller's rule. */
    fun showRename(activity: Activity, currentName: String, onRename: (String) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_name_entry, null)
        view.findViewById<TextInputLayout>(R.id.name_entry_layout).hint = null
        val field = view.findViewById<TextInputEditText>(R.id.name_entry_field)
        field.setText(currentName)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.prompt_menu_rename)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_ok) { _, _ -> onRename(field.text?.toString().orEmpty()) }
            .create()
        dialog.setOnShowListener {
            field.requestFocus()
            field.setSelection(0, field.text?.length ?: 0)
        }
        dialog.show()
    }

    /**
     * "Redo This Outline?" with a dropdown of [labels] (the outline prompts,
     * then New Prompt) opened on [selectedIndex]. The gear and Okay report
     * the chosen index; Cancel, Back or a tap outside call [onCancel].
     */
    fun showRedo(
        activity: Activity,
        labels: List<String>,
        selectedIndex: Int,
        onGear: (Int) -> Unit,
        onOkay: (Int) -> Unit,
        onCancel: () -> Unit
    ) {
        var chosen = selectedIndex.coerceIn(labels.indices)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_outline_redo, null)
        val dropdown = view.findViewById<TextView>(R.id.text_redo_prompt)
        dropdown.text = labels[chosen]
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.chat_outline_redo_title)
            .setMessage(R.string.chat_outline_redo_body)
            .setView(view)
            .create()
        dialog.setOnCancelListener { onCancel() }
        dropdown.setOnClickListener {
            AppDropdown.show(dropdown, labels, chosen) { picked ->
                chosen = picked
                dropdown.text = labels[picked]
            }
        }
        view.findViewById<ImageButton>(R.id.btn_redo_prompt_gear).setOnClickListener {
            dialog.dismiss()
            onGear(chosen)
        }
        view.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener {
                dialog.dismiss()
                onCancel()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener {
                dialog.dismiss()
                onOkay(chosen)
            }
        }
        dialog.show()
    }
}
