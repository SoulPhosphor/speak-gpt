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
 * Add/edit one entity's fields (name, kind, summary, aliases, importance) for
 * the Phase 5 memory-manager Entities screen. Mirrors
 * [EditMemoryDialogFragment]: this dialog only gathers input and hands it
 * back through [Listener] — the hosting
 * [org.teslasoft.assistant.ui.activities.memory.MemoryEntitiesActivity] does
 * all store work off the main thread.
 */
class EditEntityDialogFragment : DialogFragment() {

    companion object {
        /** [entityId] empty = a new entity. */
        fun newInstance(
            entityId: String,
            name: String,
            kind: String,
            summary: String,
            aliases: String,
            importance: Int
        ): EditEntityDialogFragment {
            val f = EditEntityDialogFragment()
            f.arguments = Bundle().apply {
                putString("entityId", entityId)
                putString("name", name)
                putString("kind", kind)
                putString("summary", summary)
                putString("aliases", aliases)
                putInt("importance", importance)
            }
            return f
        }
    }

    interface Listener {
        fun onSave(
            entityId: String,
            name: String,
            kind: String,
            summary: String,
            aliases: String,
            importance: Int
        )
        fun onError(message: String)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    private var fieldName: TextInputEditText? = null
    private var fieldKind: TextInputEditText? = null
    private var fieldSummary: TextInputEditText? = null
    private var fieldAliases: TextInputEditText? = null
    private var fieldImportance: TextInputEditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val view = layoutInflater.inflate(R.layout.dialog_edit_entity, null)

        val dialogTitle = view.findViewById<TextView>(R.id.text_dialog_title)
        fieldName = view.findViewById(R.id.field_name)
        fieldKind = view.findViewById(R.id.field_kind)
        fieldSummary = view.findViewById(R.id.field_summary)
        fieldAliases = view.findViewById(R.id.field_aliases)
        fieldImportance = view.findViewById(R.id.field_importance)

        val isNew = (args.getString("entityId") ?: "").isEmpty()
        dialogTitle.text = getString(
            if (isNew) R.string.mem_admin_dialog_entity_title_new else R.string.mem_admin_dialog_entity_title_edit
        )

        fieldName?.setText(args.getString("name"))
        fieldKind?.setText(args.getString("kind"))
        fieldSummary?.setText(args.getString("summary"))
        fieldAliases?.setText(args.getString("aliases"))
        fieldImportance?.setText(args.getInt("importance", 3).toString())

        return MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateAndSave() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .create()
    }

    private fun validateAndSave() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        val summary = fieldSummary?.text?.toString()?.trim().orEmpty()
        if (name.isBlank() || summary.isBlank()) {
            listener?.onError(getString(R.string.mem_admin_entity_required))
            return
        }
        val importance = (fieldImportance?.text?.toString()?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val args = requireArguments()
        listener?.onSave(
            args.getString("entityId") ?: "",
            name,
            fieldKind?.text?.toString()?.trim().orEmpty(),
            summary,
            fieldAliases?.text?.toString()?.trim().orEmpty(),
            importance
        )
    }
}
