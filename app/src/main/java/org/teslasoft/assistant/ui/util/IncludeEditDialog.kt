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

package org.teslasoft.assistant.ui.util

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R

/**
 * The include Edit pop-up: shows the text that is actually being sent for an
 * attachment and lets the user change it.
 *
 * This exists because the alternative — trusting a model-written summary
 * sight unseen — is exactly what the owner refused. Whatever the model writes
 * (a condensed document, a one-line bookmark) is presented here for approval
 * or correction before it stands in for the real thing, and it stays editable
 * afterwards, so a missed detail is typed in rather than re-rolled.
 *
 * Deliberately a dialog rather than a full-screen editor (owner's call for
 * this one case), and deliberately uses [R.layout.dialog_two_actions_end] —
 * Cancel then Save, right-aligned — which is a different approved shape from
 * the primary/destructive confirmation pair in `dialog_two_actions.xml`.
 */
object IncludeEditDialog {

    /**
     * @param title the attachment's file name — it identifies what is being
     *  edited without inventing any new on-screen wording.
     * @param onSave receives the edited text; never called on Cancel.
     */
    fun show(context: Context, title: String, text: String, onSave: (String) -> Unit) {
        val body = LayoutInflater.from(context).inflate(R.layout.dialog_include_edit, null)
        val field = body.findViewById<TextInputEditText>(R.id.include_edit_text)
        field?.setText(text)

        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions_end, null) as LinearLayout
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_end_cancel)
        val save = actions.findViewById<MaterialButton>(R.id.btn_dialog_end_save)
        cancel?.setText(R.string.include_edit_cancel)
        save?.setText(R.string.include_edit_save)

        // The field and the action row are separate layouts, so they are
        // stacked into one custom view rather than fighting over setView.
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
            addView(actions)
        }

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(title)
            .setView(container)
            .setCancelable(true)
            .create()

        cancel?.setOnClickListener { dialog.dismiss() }
        save?.setOnClickListener {
            val edited = field?.text?.toString().orEmpty().trim()
            // An empty box would silently delete what the model can see; keep
            // the previous text instead of accepting nothing.
            if (edited.isNotEmpty()) onSave(edited)
            dialog.dismiss()
        }

        dialog.show()
    }
}
