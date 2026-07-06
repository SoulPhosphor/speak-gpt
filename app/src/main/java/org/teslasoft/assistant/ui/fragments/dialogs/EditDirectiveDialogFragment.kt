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
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R

/**
 * Add/edit one directive's fields (text, rationale, priority) for the Phase 5
 * memory-manager Directives screen. Mirrors [EditMemoryDialogFragment]: this
 * dialog only gathers input and hands it back through [Listener] — the
 * hosting [org.teslasoft.assistant.ui.activities.memory.MemoryDirectivesActivity]
 * does all store work off the main thread.
 */
class EditDirectiveDialogFragment : DialogFragment() {

    companion object {
        /** [directiveId] empty = a new directive. */
        fun newInstance(
            directiveId: String,
            text: String,
            rationale: String,
            priority: Int
        ): EditDirectiveDialogFragment {
            val f = EditDirectiveDialogFragment()
            f.arguments = Bundle().apply {
                putString("directiveId", directiveId)
                putString("text", text)
                putString("rationale", rationale)
                putInt("priority", priority)
            }
            return f
        }
    }

    interface Listener {
        fun onSave(
            directiveId: String,
            text: String,
            rationale: String,
            priority: Int
        )
        fun onError(message: String)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    private var fieldText: TextInputEditText? = null
    private var fieldRationale: TextInputEditText? = null
    private var fieldPriority: TextInputEditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val view = layoutInflater.inflate(R.layout.dialog_edit_directive, null)

        val dialogTitle = view.findViewById<TextView>(R.id.text_dialog_title)
        fieldText = view.findViewById(R.id.field_text)
        fieldRationale = view.findViewById(R.id.field_rationale)
        fieldPriority = view.findViewById(R.id.field_priority)

        val isNew = (args.getString("directiveId") ?: "").isEmpty()
        dialogTitle.text = getString(
            if (isNew) R.string.mem_admin_dialog_directive_title_new else R.string.mem_admin_dialog_directive_title_edit
        )

        fieldText?.setText(args.getString("text"))
        fieldRationale?.setText(args.getString("rationale"))
        fieldPriority?.setText(args.getInt("priority", 3).toString())

        return MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateAndSave() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .create()
    }

    private fun validateAndSave() {
        val text = fieldText?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            listener?.onError(getString(R.string.mem_admin_directive_text_required))
            return
        }
        val priority = (fieldPriority?.text?.toString()?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val args = requireArguments()
        listener?.onSave(
            args.getString("directiveId") ?: "",
            text,
            fieldRationale?.text?.toString()?.trim().orEmpty(),
            priority
        )
    }
}
