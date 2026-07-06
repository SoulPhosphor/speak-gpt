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
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R

/**
 * Add/edit one memory's core fields (title, content, kind, importance, tags,
 * always-load, and global-vs-companion scope). Protection, archive, delete and
 * history are row actions in the browser, not here — this dialog only creates
 * or edits. The hosting activity does all store work off the main thread; the
 * dialog just gathers input and hands it back through [Listener].
 *
 * A memory can be created pre-scoped to a world / campaign / roleplay character
 * (the scoped browsers pass those ids as hidden presets so a memory added from
 * a world page belongs to that world). Companion scoping is chosen here from
 * the companion list the activity supplies.
 */
class EditMemoryDialogFragment : DialogFragment() {

    companion object {
        /** [memoryId] empty = a new memory. Companion pick options come as
         *  parallel id/name arrays so the dialog needs no store access. */
        fun newInstance(
            memoryId: String,
            title: String,
            content: String,
            kind: String,
            importance: Int,
            tags: String,
            alwaysLoad: Boolean,
            scope: String,
            companionId: String?,
            presetWorldId: String?,
            presetCampaignId: String?,
            presetRoleplayCharacterId: String?,
            companionIds: ArrayList<String>,
            companionNames: ArrayList<String>
        ): EditMemoryDialogFragment {
            val f = EditMemoryDialogFragment()
            f.arguments = Bundle().apply {
                putString("memoryId", memoryId)
                putString("title", title)
                putString("content", content)
                putString("kind", kind)
                putInt("importance", importance)
                putString("tags", tags)
                putBoolean("alwaysLoad", alwaysLoad)
                putString("scope", scope)
                putString("companionId", companionId)
                putString("presetWorldId", presetWorldId)
                putString("presetCampaignId", presetCampaignId)
                putString("presetRoleplayCharacterId", presetRoleplayCharacterId)
                putStringArrayList("companionIds", companionIds)
                putStringArrayList("companionNames", companionNames)
            }
            return f
        }
    }

    interface Listener {
        fun onSave(
            memoryId: String,
            title: String,
            content: String,
            kind: String,
            importance: Int,
            tags: String,
            alwaysLoad: Boolean,
            scope: String,
            companionId: String?,
            presetWorldId: String?,
            presetCampaignId: String?,
            presetRoleplayCharacterId: String?
        )
        fun onError(message: String)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    private var fieldTitle: TextInputEditText? = null
    private var fieldContent: TextInputEditText? = null
    private var fieldKind: TextInputEditText? = null
    private var fieldImportance: TextInputEditText? = null
    private var fieldTags: TextInputEditText? = null
    private var checkAlwaysLoad: CheckBox? = null
    private var checkCompanionScoped: CheckBox? = null
    private var btnPickCompanion: MaterialButton? = null

    private var selectedCompanionId: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val view = layoutInflater.inflate(R.layout.dialog_edit_memory, null)

        val dialogTitle = view.findViewById<TextView>(R.id.text_dialog_title)
        fieldTitle = view.findViewById(R.id.field_title)
        fieldContent = view.findViewById(R.id.field_content)
        fieldKind = view.findViewById(R.id.field_kind)
        fieldImportance = view.findViewById(R.id.field_importance)
        fieldTags = view.findViewById(R.id.field_tags)
        checkAlwaysLoad = view.findViewById(R.id.check_always_load)
        checkCompanionScoped = view.findViewById(R.id.check_companion_scoped)
        btnPickCompanion = view.findViewById(R.id.btn_pick_companion)

        val isNew = (args.getString("memoryId") ?: "").isEmpty()
        dialogTitle.text = getString(
            if (isNew) R.string.dialog_memory_title_new else R.string.dialog_memory_title_edit
        )

        fieldTitle?.setText(args.getString("title"))
        fieldContent?.setText(args.getString("content"))
        fieldKind?.setText(args.getString("kind").orEmpty().ifBlank { "note" })
        fieldImportance?.setText(args.getInt("importance", 3).toString())
        fieldTags?.setText(args.getString("tags"))
        checkAlwaysLoad?.isChecked = args.getBoolean("alwaysLoad", false)

        selectedCompanionId = args.getString("companionId")
        val companionScoped = args.getString("scope") == "companion"
        checkCompanionScoped?.isChecked = companionScoped
        btnPickCompanion?.visibility = if (companionScoped) View.VISIBLE else View.GONE
        refreshCompanionButton()

        checkCompanionScoped?.setOnCheckedChangeListener { _, checked ->
            btnPickCompanion?.visibility = if (checked) View.VISIBLE else View.GONE
        }
        btnPickCompanion?.setOnClickListener { showCompanionPicker() }

        return MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateAndSave() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .create()
    }

    private fun companionName(id: String?): String? {
        if (id == null) return null
        val ids = requireArguments().getStringArrayList("companionIds") ?: return null
        val names = requireArguments().getStringArrayList("companionNames") ?: return null
        val i = ids.indexOf(id)
        return if (i in names.indices) names[i] else null
    }

    private fun refreshCompanionButton() {
        val name = companionName(selectedCompanionId)
        btnPickCompanion?.text = if (name != null) getString(R.string.memory_companion_selected, name)
        else getString(R.string.memory_pick_companion)
    }

    private fun showCompanionPicker() {
        val ids = requireArguments().getStringArrayList("companionIds") ?: arrayListOf()
        val names = requireArguments().getStringArrayList("companionNames") ?: arrayListOf()
        if (ids.isEmpty()) {
            listener?.onError(getString(R.string.memory_no_companions))
            return
        }
        val current = ids.indexOf(selectedCompanionId).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_pick_companion)
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                selectedCompanionId = ids[which]
                refreshCompanionButton()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun validateAndSave() {
        val title = fieldTitle?.text?.toString()?.trim().orEmpty()
        val content = fieldContent?.text?.toString()?.trim().orEmpty()
        if (title.isBlank() || content.isBlank()) {
            listener?.onError(getString(R.string.memory_title_content_required))
            return
        }
        val companionScoped = checkCompanionScoped?.isChecked == true
        if (companionScoped && selectedCompanionId == null) {
            listener?.onError(getString(R.string.memory_companion_required))
            return
        }
        val importance = (fieldImportance?.text?.toString()?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val args = requireArguments()
        listener?.onSave(
            args.getString("memoryId") ?: "",
            title,
            content,
            fieldKind?.text?.toString()?.trim()?.ifBlank { "note" } ?: "note",
            importance,
            fieldTags?.text?.toString()?.trim().orEmpty(),
            checkAlwaysLoad?.isChecked ?: false,
            if (companionScoped) "companion" else "global",
            if (companionScoped) selectedCompanionId else null,
            args.getString("presetWorldId"),
            args.getString("presetCampaignId"),
            args.getString("presetRoleplayCharacterId")
        )
    }
}
