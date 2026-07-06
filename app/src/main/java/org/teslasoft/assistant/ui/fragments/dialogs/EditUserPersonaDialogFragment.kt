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
 * Add/edit one user persona: a presentation variant of the primary user
 * ("casual me", "ball gown me" — app_adaptation_notes.md §Tab structure). Same
 * person, same memories, only the appearance/voice text handed to the model
 * differs. The hosting activity (MemoryUserPersonasActivity) does all store
 * work off the main thread; this dialog only gathers input and hands it back
 * through [Listener].
 */
class EditUserPersonaDialogFragment : DialogFragment() {

    companion object {
        /** [personaId] empty = a new persona. */
        fun newInstance(
            personaId: String,
            name: String,
            presentation: String
        ): EditUserPersonaDialogFragment {
            val f = EditUserPersonaDialogFragment()
            f.arguments = Bundle().apply {
                putString("personaId", personaId)
                putString("name", name)
                putString("presentation", presentation)
            }
            return f
        }
    }

    interface Listener {
        fun onSave(personaId: String, name: String, presentation: String)
        fun onError(message: String)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    private var fieldName: TextInputEditText? = null
    private var fieldPresentation: TextInputEditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val view = layoutInflater.inflate(R.layout.dialog_edit_user_persona, null)

        val dialogTitle = view.findViewById<TextView>(R.id.text_dialog_title)
        fieldName = view.findViewById(R.id.field_name)
        fieldPresentation = view.findViewById(R.id.field_presentation)

        val isNew = (args.getString("personaId") ?: "").isEmpty()
        dialogTitle.text = getString(
            if (isNew) R.string.mem_pers_dialog_title_new_persona else R.string.mem_pers_dialog_title_edit_persona
        )

        fieldName?.setText(args.getString("name"))
        fieldPresentation?.setText(args.getString("presentation"))

        return MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateAndSave() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .create()
    }

    private fun validateAndSave() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        val presentation = fieldPresentation?.text?.toString()?.trim().orEmpty()
        if (name.isBlank() || presentation.isBlank()) {
            listener?.onError(getString(R.string.mem_pers_validation_persona_required))
            return
        }
        listener?.onSave(requireArguments().getString("personaId") ?: "", name, presentation)
    }
}
