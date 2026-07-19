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
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.ui.activities.LoreBookEntriesActivity
import org.teslasoft.assistant.ui.activities.LoreBooksListActivity
import org.teslasoft.assistant.ui.activities.ProfileImagesActivity
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.ProfileImageBinder

class EditPersonaDialogFragment : DialogFragment() {
    companion object {
        fun newInstance(persona: PersonaObject, position: Int): EditPersonaDialogFragment {
            val editPersonaDialogFragment = EditPersonaDialogFragment()

            val args = Bundle()
            args.putString("label", persona.label)
            args.putString("prompt", persona.prompt)
            args.putString("activationPromptId", persona.activationPromptId)
            args.putString("coreLoreBookId", persona.coreLoreBookId)
            args.putString("additionalLoreBookIds", persona.additionalLoreBookIds)
            args.putBoolean("autoLoadLastLoreBooks", persona.autoLoadLastLoreBooks)
            args.putString("lastUsedLoreBookIds", persona.lastUsedLoreBookIds)
            args.putString("avatarRef", persona.avatarRef)
            args.putInt("position", position)

            editPersonaDialogFragment.arguments = args

            return editPersonaDialogFragment
        }

        // Survives dialog/activity recreation while the gallery is open so the
        // pending pick is not lost (plan: EDITOR INTEGRATION - "The selected
        // avatarRef must survive activity and dialog recreation").
        private const val STATE_AVATAR_REF = "state_avatar_ref"
    }

    private var textDialogTitle: TextView? = null
    private var fieldLabel: TextInputEditText? = null
    private var fieldPrompt: TextInputEditText? = null
    private var fieldActivationPrompt: TextInputEditText? = null
    private var fieldCoreLoreBook: TextInputEditText? = null
    private var additionalLoreBooksList: LinearLayout? = null
    private var btnAddLoreBooks: MaterialButton? = null
    private var checkboxAutoload: MaterialCheckBox? = null

    private var imgPersonaAvatar: ImageView? = null
    private var btnChangePicture: MaterialButton? = null
    private var btnRemovePicture: MaterialButton? = null

    private var selectedActivationPromptId: String = ""
    private var selectedCoreLoreBookId: String = ""
    private var additionalLoreBookIds: ArrayList<String> = arrayListOf()
    /** The Companion's assigned Profile Image hash, held until the persona is
     *  saved (buildPersonaObject). "" means no picture. */
    private var selectedAvatarRef: String = ""

    private var builder: AlertDialog.Builder? = null

    private var listener: StateChangesListener? = null

    // Lifecycle-safe: registered as a fragment field so a pending gallery
    // result is still delivered after the dialog/activity is recreated, rather
    // than lost with a transient listener (plan: EDITOR INTEGRATION).
    private val pickPictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val hash = result.data?.getStringExtra(ProfileImagesActivity.EXTRA_RESULT_ASSIGNED_HASH)
            if (!hash.isNullOrEmpty()) {
                selectedAvatarRef = hash
                updateAvatarUi()
            }
        }
    }

    private val pickLoreBooksLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val ids = result.data?.getStringArrayListExtra(LoreBooksListActivity.EXTRA_SELECTED_IDS)
            if (ids != null) {
                additionalLoreBookIds = ArrayList(ids.distinct())
                renderAdditionalLoreBooks()
            }
        }
        // Books may have been renamed/deleted inside the picker either way.
        updateCoreLoreBookLabel()
        renderAdditionalLoreBooks()
    }

    private fun activationPromptLabel(id: String): String {
        if (id == "") return getString(R.string.label_activation_none)
        val label = ActivationPromptPreferences.getActivationPromptPreferences(requireContext()).getActivationPrompt(id).label
        return if (label != "") label else getString(R.string.label_activation_none)
    }

    private fun showActivationPromptChooser() {
        val prompts = ActivationPromptPreferences.getActivationPromptPreferences(requireContext()).getActivationPromptsList()
        val ids = arrayListOf("")
        val labels = arrayListOf(getString(R.string.label_activation_none))
        for (p in prompts) {
            ids.add(Hash.hash(p.label))
            labels.add(p.label)
        }

        val current = ids.indexOf(selectedActivationPromptId).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setTitle(R.string.persona_activation_hint)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                selectedActivationPromptId = ids[which]
                fieldActivationPrompt?.setText(activationPromptLabel(selectedActivationPromptId))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun coreLoreBookLabel(id: String): String {
        if (id == "") return getString(R.string.label_lorebook_none)
        val name = LoreBookStore.getInstance(requireContext()).getBook(id)?.name ?: ""
        return if (name != "") name else getString(R.string.label_lorebook_none)
    }

    private fun updateCoreLoreBookLabel() {
        // A book deleted elsewhere must not linger as a stale selection.
        if (selectedCoreLoreBookId != "" && LoreBookStore.getInstance(requireContext()).getBook(selectedCoreLoreBookId) == null) {
            selectedCoreLoreBookId = ""
        }
        fieldCoreLoreBook?.setText(coreLoreBookLabel(selectedCoreLoreBookId))
    }

    private fun showCoreLoreBookChooser() {
        val books = LoreBookStore.getInstance(requireContext()).getAllBooks()
        val ids = arrayListOf("")
        val labels = arrayListOf(getString(R.string.label_lorebook_none))
        for (book in books) {
            ids.add(book.id)
            labels.add(book.name)
        }

        val current = ids.indexOf(selectedCoreLoreBookId).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setTitle(R.string.persona_core_lorebook_hint)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                selectedCoreLoreBookId = ids[which]
                fieldCoreLoreBook?.setText(coreLoreBookLabel(selectedCoreLoreBookId))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun renderAdditionalLoreBooks() {
        val container = additionalLoreBooksList ?: return
        container.removeAllViews()

        val store = LoreBookStore.getInstance(requireContext())
        // Drop links to books that no longer exist.
        additionalLoreBookIds = ArrayList(additionalLoreBookIds.filter { store.getBook(it) != null })

        if (additionalLoreBookIds.isEmpty()) {
            val empty = TextView(requireContext())
            empty.text = getString(R.string.persona_no_additional_lorebooks)
            empty.setTextColor(resources.getColor(R.color.text_subtitle, requireContext().theme))
            empty.textSize = 14f
            empty.setPadding(0, 0, 0, 16)
            container.addView(empty)
            return
        }

        for (id in additionalLoreBookIds) {
            val book = store.getBook(id) ?: continue
            val row = layoutInflater.inflate(R.layout.view_persona_lorebook_row, container, false)

            row.findViewById<TextView>(R.id.row_book_name)?.text = book.name

            val count = store.getEntryCount(book.id)
            var subtitle = resources.getQuantityString(R.plurals.lorebook_memory_count, count, count)
            if (book.tag.isNotBlank()) subtitle = "$subtitle · ${book.tag}"
            if (book.description.isNotBlank()) subtitle = "$subtitle\n${book.description}"
            row.findViewById<TextView>(R.id.row_book_subtitle)?.text = subtitle

            row.findViewById<ImageButton>(R.id.row_btn_edit)?.setOnClickListener {
                val intent = Intent(requireContext(), LoreBookEntriesActivity::class.java)
                intent.putExtra("lorebookId", book.id)
                intent.putExtra("lorebookName", book.name)
                startActivity(intent)
            }

            row.findViewById<ImageButton>(R.id.row_btn_unlink)?.setOnClickListener {
                // Unlink only detaches the book from this persona; the book and
                // all its memories stay in the collection.
                additionalLoreBookIds.remove(book.id)
                renderAdditionalLoreBooks()
            }

            row.findViewById<ImageButton>(R.id.row_btn_delete)?.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.label_delete_lorebook)
                    .setMessage(R.string.message_delete_lorebook)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        LoreBookStore.getInstance(requireContext()).deleteBook(book.id)
                        PersonaPreferences.getPersonaPreferences(requireContext()).removeLoreBookFromAllPersonas(book.id)
                        additionalLoreBookIds.remove(book.id)
                        if (selectedCoreLoreBookId == book.id) {
                            selectedCoreLoreBookId = ""
                            updateCoreLoreBookLabel()
                        }
                        renderAdditionalLoreBooks()
                    }
                    .setNegativeButton(R.string.no) { _, _ -> }
                    .show()
            }

            container.addView(row)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_edit_persona, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        builder = MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)

        val view: View = this.layoutInflater.inflate(R.layout.fragment_edit_persona, null)

        textDialogTitle = view.findViewById(R.id.text_dialog_title)
        fieldLabel = view.findViewById(R.id.field_label)
        fieldPrompt = view.findViewById(R.id.field_prompt)
        fieldActivationPrompt = view.findViewById(R.id.field_activation_prompt)
        fieldCoreLoreBook = view.findViewById(R.id.field_core_lorebook)
        additionalLoreBooksList = view.findViewById(R.id.additional_lorebooks_list)
        btnAddLoreBooks = view.findViewById(R.id.btn_add_lorebooks)
        checkboxAutoload = view.findViewById(R.id.checkbox_autoload_lorebooks)
        imgPersonaAvatar = view.findViewById(R.id.img_persona_avatar)
        btnChangePicture = view.findViewById(R.id.btn_change_picture)
        btnRemovePicture = view.findViewById(R.id.btn_remove_picture)

        fieldLabel?.setText(requireArguments().getString("label"))
        fieldPrompt?.setText(requireArguments().getString("prompt"))

        selectedActivationPromptId = requireArguments().getString("activationPromptId") ?: ""
        fieldActivationPrompt?.setText(activationPromptLabel(selectedActivationPromptId))

        selectedCoreLoreBookId = requireArguments().getString("coreLoreBookId") ?: ""
        additionalLoreBookIds = PersonaObject.splitIds(requireArguments().getString("additionalLoreBookIds") ?: "")
        checkboxAutoload?.isChecked = requireArguments().getBoolean("autoLoadLastLoreBooks", false)

        // Restore the pending pick across recreation; fall back to the persona's
        // saved avatarRef on first open.
        selectedAvatarRef = savedInstanceState?.getString(STATE_AVATAR_REF)
            ?: (requireArguments().getString("avatarRef") ?: "")
        updateAvatarUi()
        btnChangePicture?.setOnClickListener { openGalleryForPicture() }
        btnRemovePicture?.setOnClickListener {
            // Remove Picture clears only this Companion's reference; it never
            // deletes the gallery image (plan: PERMANENT DELETION).
            selectedAvatarRef = ""
            updateAvatarUi()
        }

        updateCoreLoreBookLabel()
        renderAdditionalLoreBooks()

        fieldActivationPrompt?.setOnClickListener { showActivationPromptChooser() }
        view.findViewById<TextInputLayout>(R.id.textInputLayoutActivation)?.setOnClickListener { showActivationPromptChooser() }

        fieldCoreLoreBook?.setOnClickListener { showCoreLoreBookChooser() }
        view.findViewById<TextInputLayout>(R.id.textInputLayoutCoreLoreBook)?.setOnClickListener { showCoreLoreBookChooser() }

        btnAddLoreBooks?.setOnClickListener {
            val intent = Intent(requireContext(), LoreBooksListActivity::class.java)
            intent.putExtra(LoreBooksListActivity.EXTRA_PICK_MODE, true)
            intent.putStringArrayListExtra(LoreBooksListActivity.EXTRA_SELECTED_IDS, ArrayList(additionalLoreBookIds))
            pickLoreBooksLauncher.launch(intent)
        }

        if (requireArguments().getInt("position") == -1) {
            textDialogTitle?.text = getString(R.string.label_add_persona)
        }

        builder!!.setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_save) { _, _ -> validateForm() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }

        if (requireArguments().getInt("position") != -1) {
            builder!!.setNeutralButton(R.string.btn_delete) { _, _ -> run {
                MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.label_delete_persona)
                    .setMessage(R.string.message_delete_persona)
                    .setPositiveButton(R.string.yes) { _, _ -> listener!!.onDelete(requireArguments().getInt("position"), Hash.hash(requireArguments().getString("label")!!)) }
                    .setNegativeButton(R.string.no) { _, _ -> listener!!.onCancel(requireArguments().getInt("position")) }
                    .show()
            }}
        }

        return builder!!.create()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the entries editor (gear) may have changed counts or
        // names, and books may have been deleted from elsewhere.
        updateCoreLoreBookLabel()
        renderAdditionalLoreBooks()
    }

    /** Reflects [selectedAvatarRef] into the preview + buttons: a shaped
     *  picture and Change/Remove when assigned, a placeholder glyph and Add
     *  Picture when not. Bound through the shared [ProfileImageBinder] so the
     *  current Default Shape and reset rules apply. */
    private fun updateAvatarUi() {
        val hasPicture = selectedAvatarRef.isNotEmpty()
        btnChangePicture?.setText(if (hasPicture) R.string.profile_image_change_picture else R.string.profile_image_add_picture)
        btnRemovePicture?.visibility = if (hasPicture) View.VISIBLE else View.GONE

        val imageView = imgPersonaAvatar ?: return
        val context = imageView.context
        val file = if (hasPicture) ProfileImageStore.getInstance(context).imageFile(selectedAvatarRef) else null
        val shape = GlobalPreferences.getPreferences(context).getProfileImageShape()
        ProfileImageBinder.bind(context, imageView, file, shape) { iv ->
            iv.setImageResource(R.drawable.ic_photo)
            iv.imageTintList = ColorStateList.valueOf(resolveColorPrimary(iv.context))
        }
    }

    private fun openGalleryForPicture() {
        val intent = Intent(requireContext(), ProfileImagesActivity::class.java)
            .putExtra(ProfileImagesActivity.EXTRA_ASSIGN_TARGET, ProfileImagesActivity.TARGET_COMPANION)
            .putExtra(ProfileImagesActivity.EXTRA_ASSIGN_CURRENT_HASH, selectedAvatarRef)
        pickPictureLauncher.launch(intent)
    }

    private fun resolveColorPrimary(context: Context): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_AVATAR_REF, selectedAvatarRef)
    }

    private fun buildPersonaObject(): PersonaObject {
        // Last-used bookkeeping survives the edit, pruned to the books that are
        // still linked.
        val lastUsed = PersonaObject.splitIds(requireArguments().getString("lastUsedLoreBookIds") ?: "")
            .filter { additionalLoreBookIds.contains(it) }
        return PersonaObject(
            label = fieldLabel?.text.toString(),
            prompt = fieldPrompt?.text.toString(),
            activationPromptId = selectedActivationPromptId,
            coreLoreBookId = selectedCoreLoreBookId,
            additionalLoreBookIds = PersonaObject.joinIds(additionalLoreBookIds),
            autoLoadLastLoreBooks = checkboxAutoload?.isChecked == true,
            lastUsedLoreBookIds = PersonaObject.joinIds(lastUsed),
            avatarRef = selectedAvatarRef
        )
    }

    private fun validateForm() {
        if (fieldLabel?.text.toString().isEmpty()) {
            listener!!.onError(getString(R.string.label_error_persona_empty), requireArguments().getInt("position"))
            return
        }

        if (requireArguments().getInt("position") == -1) {
            listener!!.onAdd(buildPersonaObject())
        } else {
            listener!!.onEdit(
                requireArguments().getString("label")!!,
                buildPersonaObject(),
                requireArguments().getInt("position")
            )
        }
    }

    fun setListener(listener: StateChangesListener) {
        this.listener = listener
    }

    interface StateChangesListener {
        fun onAdd(persona: PersonaObject) { /* default */ }
        fun onEdit(oldLabel: String, persona: PersonaObject, position: Int) { /* default */ }
        fun onDelete(position: Int, id: String) { /* default */ }
        fun onError(message: String, position: Int) { /* default */ }
        fun onCancel(position: Int) { /* default */ }
    }
}
