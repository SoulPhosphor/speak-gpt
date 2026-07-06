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
import android.view.View
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R

/**
 * Add/edit one roleplay character (a user-played fictional character — the
 * Mage, the Druid; app_adaptation_notes.md §Tab structure, separate from user
 * personas so fiction never mixes with the primary user's presentation
 * variants). The **definition** (name, description, played-by) is
 * user-editable here; the **arc** (story-so-far) is Archivist-maintained and
 * rendered strictly read-only — this dialog never lets it be typed into, and
 * [Listener.onSave] always hands back the arc unchanged
 * (memory-system-integration-plan.md 📌 campaign amendment). There is no
 * dedicated abilities/spells column in the schema yet, so for now those live
 * inside the free-text description, per the hint under that field.
 */
class EditRoleplayCharacterDialogFragment : DialogFragment() {

    companion object {
        /** [roleplayCharacterId] empty = a new character. [arc] is display-only
         *  and is never sent back through [Listener.onSave]. */
        fun newInstance(
            roleplayCharacterId: String,
            name: String,
            description: String,
            playedBy: String,
            arc: String?
        ): EditRoleplayCharacterDialogFragment {
            val f = EditRoleplayCharacterDialogFragment()
            f.arguments = Bundle().apply {
                putString("roleplayCharacterId", roleplayCharacterId)
                putString("name", name)
                putString("description", description)
                putString("playedBy", playedBy)
                putString("arc", arc)
            }
            return f
        }
    }

    interface Listener {
        /** The arc is deliberately absent — the hosting activity keeps the
         *  existing arc untouched when it builds the saved record. */
        fun onSave(roleplayCharacterId: String, name: String, description: String, playedBy: String)
        fun onError(message: String)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    private var fieldName: TextInputEditText? = null
    private var fieldDescription: TextInputEditText? = null
    private var fieldPlayedBy: TextInputEditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val view = layoutInflater.inflate(R.layout.dialog_edit_roleplay_character, null)

        val dialogTitle = view.findViewById<TextView>(R.id.text_dialog_title)
        fieldName = view.findViewById(R.id.field_name)
        fieldDescription = view.findViewById(R.id.field_description)
        fieldPlayedBy = view.findViewById(R.id.field_played_by)
        val labelArc = view.findViewById<TextView>(R.id.label_arc)
        val textArc = view.findViewById<TextView>(R.id.text_arc)

        val isNew = (args.getString("roleplayCharacterId") ?: "").isEmpty()
        dialogTitle.text = getString(
            if (isNew) R.string.mem_pers_dialog_title_new_character else R.string.mem_pers_dialog_title_edit_character
        )

        fieldName?.setText(args.getString("name"))
        fieldDescription?.setText(args.getString("description"))
        fieldPlayedBy?.setText(args.getString("playedBy").orEmpty().ifBlank { "user" })

        // Arc: read-only, Archivist-maintained. Shown only for an existing
        // character — a brand-new one has no story-so-far yet, and the field
        // is never an input either way.
        val arc = args.getString("arc")
        if (!isNew) {
            labelArc.visibility = View.VISIBLE
            textArc.visibility = View.VISIBLE
            textArc.text = if (arc.isNullOrBlank()) getString(R.string.mem_pers_arc_empty) else arc
        } else {
            labelArc.visibility = View.GONE
            textArc.visibility = View.GONE
        }

        return MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateAndSave() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .create()
    }

    private fun validateAndSave() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        val description = fieldDescription?.text?.toString()?.trim().orEmpty()
        if (name.isBlank() || description.isBlank()) {
            listener?.onError(getString(R.string.mem_pers_validation_character_required))
            return
        }
        val playedBy = fieldPlayedBy?.text?.toString()?.trim().orEmpty().ifBlank { "user" }
        listener?.onSave(requireArguments().getString("roleplayCharacterId") ?: "", name, description, playedBy)
    }
}
