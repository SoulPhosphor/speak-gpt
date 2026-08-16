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
 * Chat title edit pop-up: opened by tapping the chat header's title,
 * shows the FULL current title (never truncated, unlike the header itself)
 * in a 4-line, internally scrolling box so a long AI-generated title stays
 * fully readable and editable.
 *
 * Deliberately its own small dialog rather than the chat list's full
 * "Add/Edit chat" form (AddChatDialogFragment) — that dialog carries new-chat
 * creation fields (auto-name, persona, model, etc.) that have no place here.
 * Shares [R.layout.dialog_two_actions_end] with IncludeEditDialog for the
 * same Cancel-then-Save, right-aligned button pair.
 */
object EditChatTitleDialog {

    /** @param onSave receives the trimmed, non-blank edited title; never called on Cancel. */
    fun show(context: Context, currentTitle: String, onSave: (String) -> Unit) {
        val body = LayoutInflater.from(context).inflate(R.layout.dialog_edit_chat_title, null)
        val field = body.findViewById<TextInputEditText>(R.id.chat_title_edit_text)
        field?.setText(currentTitle)

        val actions = LayoutInflater.from(context)
            .inflate(R.layout.dialog_two_actions_end, null) as LinearLayout
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_end_cancel)
        val save = actions.findViewById<MaterialButton>(R.id.btn_dialog_end_save)
        cancel?.setText(R.string.btn_cancel)
        save?.setText(R.string.btn_save)

        // The field and the action row are separate layouts, so they are
        // stacked into one custom view rather than fighting over setView.
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
            addView(actions)
        }

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.title_edit_chat_title)
            .setView(container)
            .setCancelable(true)
            .create()

        cancel?.setOnClickListener { dialog.dismiss() }
        save?.setOnClickListener {
            val edited = field?.text?.toString().orEmpty().trim()
            if (edited.isEmpty()) {
                field?.error = context.getString(R.string.chat_error_empty)
                field?.requestFocus()
                return@setOnClickListener
            }
            onSave(edited)
            dialog.dismiss()
        }

        dialog.show()
    }
}
