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
 * Add/edit one mode's fields (name, purpose, signals/respond/avoid — each a
 * one-line-per-entry list) for the Phase 5 memory-manager Modes screen.
 * Mirrors [EditMemoryDialogFragment]: this dialog only gathers input as raw
 * text and hands it back through [Listener] — the hosting
 * [org.teslasoft.assistant.ui.activities.memory.MemoryModesActivity] converts
 * the line-lists to JSON arrays and does all store work off the main thread.
 */
class EditModeDialogFragment : DialogFragment() {

    companion object {
        /** [modeId] empty = a new mode. The line-list fields ([signals],
         *  [respond], [avoid]) are newline-joined text, not JSON yet. */
        fun newInstance(
            modeId: String,
            name: String,
            purpose: String,
            signals: String,
            respond: String,
            avoid: String
        ): EditModeDialogFragment {
            val f = EditModeDialogFragment()
            f.arguments = Bundle().apply {
                putString("modeId", modeId)
                putString("name", name)
                putString("purpose", purpose)
                putString("signals", signals)
                putString("respond", respond)
                putString("avoid", avoid)
            }
            return f
        }
    }

    interface Listener {
        fun onSave(
            modeId: String,
            name: String,
            purpose: String,
            signals: String,
            respond: String,
            avoid: String
        )
        fun onError(message: String)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    private var fieldName: TextInputEditText? = null
    private var fieldPurpose: TextInputEditText? = null
    private var fieldSignals: TextInputEditText? = null
    private var fieldRespond: TextInputEditText? = null
    private var fieldAvoid: TextInputEditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val view = layoutInflater.inflate(R.layout.dialog_edit_mode, null)

        val dialogTitle = view.findViewById<TextView>(R.id.text_dialog_title)
        fieldName = view.findViewById(R.id.field_name)
        fieldPurpose = view.findViewById(R.id.field_purpose)
        fieldSignals = view.findViewById(R.id.field_signals)
        fieldRespond = view.findViewById(R.id.field_respond)
        fieldAvoid = view.findViewById(R.id.field_avoid)

        val isNew = (args.getString("modeId") ?: "").isEmpty()
        dialogTitle.text = getString(
            if (isNew) R.string.mem_admin_dialog_mode_title_new else R.string.mem_admin_dialog_mode_title_edit
        )

        fieldName?.setText(args.getString("name"))
        fieldPurpose?.setText(args.getString("purpose"))
        fieldSignals?.setText(args.getString("signals"))
        fieldRespond?.setText(args.getString("respond"))
        fieldAvoid?.setText(args.getString("avoid"))

        return MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateAndSave() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .create()
    }

    private fun validateAndSave() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            listener?.onError(getString(R.string.mem_admin_mode_name_required))
            return
        }
        val args = requireArguments()
        listener?.onSave(
            args.getString("modeId") ?: "",
            name,
            fieldPurpose?.text?.toString()?.trim().orEmpty(),
            fieldSignals?.text?.toString().orEmpty(),
            fieldRespond?.text?.toString().orEmpty(),
            fieldAvoid?.text?.toString().orEmpty()
        )
    }
}
