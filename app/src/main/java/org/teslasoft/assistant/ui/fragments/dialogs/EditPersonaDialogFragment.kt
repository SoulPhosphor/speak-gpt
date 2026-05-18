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

package org.teslasoft.assistant.ui.fragments.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R

class EditPersonaDialogFragment : DialogFragment() {
    companion object {
        fun newInstance(id: String, name: String, prompt: String, isActive: Boolean): EditPersonaDialogFragment {
            val f = EditPersonaDialogFragment()
            val args = Bundle()
            args.putString("id", id)
            args.putString("name", name)
            args.putString("prompt", prompt)
            args.putBoolean("isActive", isActive)
            f.arguments = args
            return f
        }
    }

    private var listener: StateChangesListener? = null

    fun setListener(listener: StateChangesListener) {
        this.listener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_edit_persona, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)
        val view: View = this.layoutInflater.inflate(R.layout.fragment_edit_persona, null)

        val titleView = view.findViewById<TextView>(R.id.text_dialog_title)
        val nameField = view.findViewById<TextInputEditText>(R.id.field_name)
        val promptField = view.findViewById<TextInputEditText>(R.id.field_prompt)
        val activeSwitch = view.findViewById<CheckBox>(R.id.switch_active)

        val id = requireArguments().getString("id", "")
        val name = requireArguments().getString("name", "")
        val prompt = requireArguments().getString("prompt", "")
        val isActive = requireArguments().getBoolean("isActive", false)

        nameField.setText(name)
        promptField.setText(prompt)
        activeSwitch.isChecked = isActive

        if (id.isEmpty()) {
            titleView.text = getString(R.string.label_add_persona)
        }

        builder.setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val newName = nameField.text?.toString()?.trim().orEmpty()
                val newPrompt = promptField.text?.toString().orEmpty()
                val checked = activeSwitch.isChecked
                if (newName.isEmpty()) {
                    listener?.onError(getString(R.string.persona_name_required), id)
                } else {
                    listener?.onSave(id, newName, newPrompt, checked)
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }

        if (id.isNotEmpty()) {
            builder.setNeutralButton(R.string.btn_delete) { _, _ ->
                MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.label_delete_persona)
                    .setMessage(R.string.message_delete_persona)
                    .setPositiveButton(R.string.yes) { _, _ -> listener?.onDelete(id) }
                    .setNegativeButton(R.string.no) { _, _ -> }
                    .show()
            }
        }

        return builder.create()
    }

    interface StateChangesListener {
        fun onSave(id: String, name: String, prompt: String, makeActive: Boolean)
        fun onDelete(id: String)
        fun onError(message: String, id: String)
    }
}
