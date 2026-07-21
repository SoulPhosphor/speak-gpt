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

package org.teslasoft.assistant.ui.activities

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ActivationPromptPreferences
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.util.ProfileImageBinder

/**
 * Full-screen Companion editor (owner ruling, July 19 2026 - replaces the old
 * EditPersonaDialogFragment pop-up). Uses the shared house header
 * (Widget.App.ActionBar) titled "Edit Companion" / "Companion Creation". The
 * companion picture is a shaped, tappable preview: tapping it opens the Profile
 * Images gallery in companion-assignment mode and the chosen hash is held until
 * Save; there is no Change/Remove button (owner: every gallery already holds
 * all images, so changing is just picking a different one).
 *
 * It owns no persistence: it validates and returns the result (save or delete)
 * to the caller ([PersonasListActivity]), which applies it exactly as the old
 * dialog listener did - preserving the "select the companion and finish" and
 * last-used behaviour.
 */
class EditPersonaActivity : FragmentActivity() {

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_ACTIVATION_ID = "activationPromptId"
        const val EXTRA_CORE_LOREBOOK = "coreLoreBookId"
        const val EXTRA_ADDITIONAL_LOREBOOKS = "additionalLoreBookIds"
        const val EXTRA_AUTOLOAD = "autoLoadLastLoreBooks"
        const val EXTRA_LAST_USED_LOREBOOKS = "lastUsedLoreBookIds"
        const val EXTRA_AVATAR_REF = "avatarRef"
        const val EXTRA_POSITION = "position"
        /** The companion's stable id ("" for a brand-new companion). */
        const val EXTRA_ID = "id"

        /** RESULT_OK carries one of [ACTION_SAVE] / [ACTION_DELETE]. */
        const val EXTRA_RESULT_ACTION = "result_action"
        const val ACTION_SAVE = "save"
        const val ACTION_DELETE = "delete"
        /** Delete: the persona's stable id. */
        const val EXTRA_RESULT_ID = "result_id"

        private const val STATE_AVATAR_REF = "state_avatar_ref"

        /** Builds the launch intent for [persona] at list [position] (-1 = new). */
        fun createIntent(context: Context, persona: PersonaObject, position: Int): Intent {
            return Intent(context, EditPersonaActivity::class.java)
                .putExtra(EXTRA_ID, persona.id)
                .putExtra(EXTRA_LABEL, persona.label)
                .putExtra(EXTRA_PROMPT, persona.prompt)
                .putExtra(EXTRA_ACTIVATION_ID, persona.activationPromptId)
                .putExtra(EXTRA_CORE_LOREBOOK, persona.coreLoreBookId)
                .putExtra(EXTRA_ADDITIONAL_LOREBOOKS, persona.additionalLoreBookIds)
                .putExtra(EXTRA_AUTOLOAD, persona.autoLoadLastLoreBooks)
                .putExtra(EXTRA_LAST_USED_LOREBOOKS, persona.lastUsedLoreBookIds)
                .putExtra(EXTRA_AVATAR_REF, persona.avatarRef)
                .putExtra(EXTRA_POSITION, position)
        }

        /** Reconstructs the saved PersonaObject from a RESULT_OK save result.
         *  Carries the stable id so the caller saves under it (rename-safe). */
        fun readResultPersona(data: Intent): PersonaObject {
            return PersonaObject(
                label = data.getStringExtra(EXTRA_LABEL) ?: "",
                prompt = data.getStringExtra(EXTRA_PROMPT) ?: "",
                activationPromptId = data.getStringExtra(EXTRA_ACTIVATION_ID) ?: "",
                coreLoreBookId = data.getStringExtra(EXTRA_CORE_LOREBOOK) ?: "",
                additionalLoreBookIds = data.getStringExtra(EXTRA_ADDITIONAL_LOREBOOKS) ?: "",
                autoLoadLastLoreBooks = data.getBooleanExtra(EXTRA_AUTOLOAD, false),
                lastUsedLoreBookIds = data.getStringExtra(EXTRA_LAST_USED_LOREBOOKS) ?: "",
                avatarRef = data.getStringExtra(EXTRA_AVATAR_REF) ?: "",
                id = data.getStringExtra(EXTRA_ID) ?: ""
            )
        }
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var fieldLabelError: TextView? = null
    private var fieldLabel: TextInputEditText? = null
    private var fieldPrompt: TextInputEditText? = null
    private var fieldActivationPrompt: TextInputEditText? = null
    private var fieldCoreLoreBook: TextInputEditText? = null
    private var additionalLoreBooksList: LinearLayout? = null
    private var btnAddLoreBooks: MaterialButton? = null
    private var checkboxAutoload: MaterialCheckBox? = null
    private var imgPersonaAvatar: ImageView? = null
    private var btnSave: ImageButton? = null
    private var btnDelete: ImageButton? = null

    private var position: Int = -1
    private var personaId: String = ""
    private var originalLabel: String = ""
    private var lastUsedLoreBookIds: String = ""

    /** True once the initial field values are loaded, so the unsaved-changes
     *  check doesn't fire against a half-built screen. */
    private var ready = false

    /** Snapshot of the editable fields as first loaded, for the discard-changes
     *  confirmation on back-out (see DiscardChangesDialog). */
    private var initialSnapshot: String = ""

    private var selectedActivationPromptId: String = ""
    private var selectedCoreLoreBookId: String = ""
    private var additionalLoreBookIds: ArrayList<String> = arrayListOf()
    /** The companion's assigned Profile Image hash, held until Save. */
    private var selectedAvatarRef: String = ""

    // Registered as an activity field so a pending gallery result survives
    // recreation (owner-approved lifecycle safety carried over from Phase 7).
    private val pickPictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val hash = result.data?.getStringExtra(ProfileImagesActivity.EXTRA_RESULT_ASSIGNED_HASH)
            if (!hash.isNullOrEmpty()) {
                selectedAvatarRef = hash
                updateAvatarUi()
                persistImageOnlyIfExisting(hash)
            }
        }
    }

    /**
     * Existing companion (has a stable id): the image tap IS the save — persist
     * only the avatar immediately, by its stable id, through the narrow
     * [PersonaPreferences.setPersonaAvatarRef] which writes ONLY the avatar_ref
     * key. This never commits the unsaved name/prompt/activation/lorebook edits
     * still in the editor, and backing out (which discards those edits) cannot
     * undo the picture. A brand-new companion (blank id) keeps the pick in
     * draft — it is written when the companion is first created, so cancelling
     * creation leaves no record.
     */
    private fun persistImageOnlyIfExisting(hash: String) {
        if (personaId.isEmpty()) return
        PersonaPreferences.getPersonaPreferences(this).setPersonaAvatarRef(personaId, hash)
    }

    private val pickLoreBooksLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val ids = result.data?.getStringArrayListExtra(LoreBooksListActivity.EXTRA_SELECTED_IDS)
            if (ids != null) {
                additionalLoreBookIds = ArrayList(ids.distinct())
                renderAdditionalLoreBooks()
            }
        }
        updateCoreLoreBookLabel()
        renderAdditionalLoreBooks()
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_edit_persona)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        fieldLabelError = findViewById(R.id.text_field_label_error)
        fieldLabel = findViewById(R.id.field_label)
        fieldPrompt = findViewById(R.id.field_prompt)
        fieldActivationPrompt = findViewById(R.id.field_activation_prompt)
        fieldCoreLoreBook = findViewById(R.id.field_core_lorebook)
        additionalLoreBooksList = findViewById(R.id.additional_lorebooks_list)
        btnAddLoreBooks = findViewById(R.id.btn_add_lorebooks)
        checkboxAutoload = findViewById(R.id.checkbox_autoload_lorebooks)
        imgPersonaAvatar = findViewById(R.id.img_persona_avatar)
        btnSave = findViewById(R.id.btn_save)
        btnDelete = findViewById(R.id.btn_delete)

        applyAmoledChrome()

        position = intent.getIntExtra(EXTRA_POSITION, -1)
        personaId = intent.getStringExtra(EXTRA_ID) ?: ""
        originalLabel = intent.getStringExtra(EXTRA_LABEL) ?: ""
        lastUsedLoreBookIds = intent.getStringExtra(EXTRA_LAST_USED_LOREBOOKS) ?: ""

        activityTitle?.setText(if (position == -1) R.string.title_companion_creation else R.string.title_edit_companion)

        fieldLabel?.setText(originalLabel)
        fieldPrompt?.setText(intent.getStringExtra(EXTRA_PROMPT))

        selectedActivationPromptId = intent.getStringExtra(EXTRA_ACTIVATION_ID) ?: ""
        fieldActivationPrompt?.setText(activationPromptLabel(selectedActivationPromptId))

        selectedCoreLoreBookId = intent.getStringExtra(EXTRA_CORE_LOREBOOK) ?: ""
        additionalLoreBookIds = PersonaObject.splitIds(intent.getStringExtra(EXTRA_ADDITIONAL_LOREBOOKS) ?: "")
        checkboxAutoload?.isChecked = intent.getBooleanExtra(EXTRA_AUTOLOAD, false)

        // Restore the pending pick across recreation; else the saved avatarRef.
        selectedAvatarRef = savedInstanceState?.getString(STATE_AVATAR_REF)
            ?: (intent.getStringExtra(EXTRA_AVATAR_REF) ?: "")

        // The picture itself is the control: tap it to pick a different image.
        imgPersonaAvatar?.setOnClickListener { openGalleryForPicture() }
        updateAvatarUi()

        fieldLabel?.setOnFocusChangeListener { _, _ -> fieldLabelError?.visibility = View.GONE }

        fieldActivationPrompt?.setOnClickListener { showActivationPromptChooser() }
        fieldCoreLoreBook?.setOnClickListener { showCoreLoreBookChooser() }

        btnAddLoreBooks?.setOnClickListener {
            val intent = Intent(this, LoreBooksListActivity::class.java)
            intent.putExtra(LoreBooksListActivity.EXTRA_PICK_MODE, true)
            intent.putStringArrayListExtra(LoreBooksListActivity.EXTRA_SELECTED_IDS, ArrayList(additionalLoreBookIds))
            pickLoreBooksLauncher.launch(intent)
        }

        updateCoreLoreBookLabel()
        renderAdditionalLoreBooks()

        onBackPressedDispatcher.addCallback(this) { attemptExit() }

        btnBack?.setOnClickListener { attemptExit() }
        btnSave?.setOnClickListener { save() }

        // Delete is only for an existing companion.
        btnDelete?.visibility = if (position == -1) View.GONE else View.VISIBLE
        btnDelete?.setOnClickListener { confirmDelete() }

        // Baseline for the unsaved-changes check; every field is set above.
        ready = true
        initialSnapshot = snapshot()
    }

    override fun onResume() {
        super.onResume()
        // Books may have been renamed/deleted in the entries editor; the Default
        // Shape may have changed - re-resolve both, cheaply.
        updateCoreLoreBookLabel()
        renderAdditionalLoreBooks()
        updateAvatarUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_AVATAR_REF, selectedAvatarRef)
    }

    /* --------------------------- picture --------------------------- */

    /** Shows the assigned picture (shaped) or a placeholder glyph, through the
     *  shared binder so the current Default Shape and reset rules apply. */
    private fun updateAvatarUi() {
        val imageView = imgPersonaAvatar ?: return
        val hasPicture = selectedAvatarRef.isNotEmpty()
        val file = if (hasPicture) ProfileImageStore.getInstance(this).imageFile(selectedAvatarRef) else null
        val shape = GlobalPreferences.getPreferences(this).getProfileImageShape()
        ProfileImageBinder.bind(this, imageView, file, shape) { iv ->
            iv.setImageResource(R.drawable.ic_photo)
            // accent_900 is the app's established glyph tint - a plain color
            // resource, avoiding the material colorPrimary code-side resolution
            // failure this project has hit in CI before.
            iv.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(iv.context, R.color.accent_900))
        }
    }

    private fun openGalleryForPicture() {
        val intent = Intent(this, ProfileImagesActivity::class.java)
            .putExtra(ProfileImagesActivity.EXTRA_ASSIGN_TARGET, ProfileImagesActivity.TARGET_COMPANION)
            .putExtra(ProfileImagesActivity.EXTRA_ASSIGN_CURRENT_HASH, selectedAvatarRef)
        pickPictureLauncher.launch(intent)
    }

    /* --------------------------- choosers --------------------------- */

    private fun activationPromptLabel(id: String): String {
        if (id == "") return getString(R.string.label_activation_none)
        val label = ActivationPromptPreferences.getActivationPromptPreferences(this).getActivationPrompt(id).label
        return if (label != "") label else getString(R.string.label_activation_none)
    }

    private fun showActivationPromptChooser() {
        val prompts = ActivationPromptPreferences.getActivationPromptPreferences(this).getActivationPromptsList()
        val ids = arrayListOf("")
        val labels = arrayListOf(getString(R.string.label_activation_none))
        for (p in prompts) {
            ids.add(p.id)
            labels.add(p.label)
        }

        val current = ids.indexOf(selectedActivationPromptId).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
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
        val name = LoreBookStore.getInstance(this).getBook(id)?.name ?: ""
        return if (name != "") name else getString(R.string.label_lorebook_none)
    }

    private fun updateCoreLoreBookLabel() {
        if (selectedCoreLoreBookId != "" && LoreBookStore.getInstance(this).getBook(selectedCoreLoreBookId) == null) {
            selectedCoreLoreBookId = ""
        }
        fieldCoreLoreBook?.setText(coreLoreBookLabel(selectedCoreLoreBookId))
    }

    private fun showCoreLoreBookChooser() {
        val books = LoreBookStore.getInstance(this).getAllBooks()
        val ids = arrayListOf("")
        val labels = arrayListOf(getString(R.string.label_lorebook_none))
        for (book in books) {
            ids.add(book.id)
            labels.add(book.name)
        }

        val current = ids.indexOf(selectedCoreLoreBookId).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
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

        val store = LoreBookStore.getInstance(this)
        additionalLoreBookIds = ArrayList(additionalLoreBookIds.filter { store.getBook(it) != null })

        if (additionalLoreBookIds.isEmpty()) {
            // Matches Widget.App.Field.Hint's spec (13sp / text_subtitle / 2dp
            // top margin) so this reads the same as every other explanatory
            // line on the screen, even though it's built in code rather than
            // XML (owner ruling, July 20 2026 - style only, wording unchanged).
            val empty = TextView(this)
            empty.text = getString(R.string.persona_no_additional_lorebooks)
            empty.setTextColor(resources.getColor(R.color.text_subtitle, theme))
            empty.textSize = 13f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = dpToPx(2)
            empty.layoutParams = params
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
                val intent = Intent(this, LoreBookEntriesActivity::class.java)
                intent.putExtra("lorebookId", book.id)
                intent.putExtra("lorebookName", book.name)
                startActivity(intent)
            }

            row.findViewById<ImageButton>(R.id.row_btn_unlink)?.setOnClickListener {
                additionalLoreBookIds.remove(book.id)
                renderAdditionalLoreBooks()
            }

            row.findViewById<ImageButton>(R.id.row_btn_delete)?.setOnClickListener {
                MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                    .setTitle(R.string.label_delete_lorebook)
                    .setMessage(R.string.message_delete_lorebook)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        LoreBookStore.getInstance(this).deleteBook(book.id)
                        PersonaPreferences.getPersonaPreferences(this).removeLoreBookFromAllPersonas(book.id)
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

    /* --------------------------- save / delete --------------------------- */

    private fun buildPersonaObject(): PersonaObject {
        val lastUsed = PersonaObject.splitIds(lastUsedLoreBookIds)
            .filter { additionalLoreBookIds.contains(it) }
        return PersonaObject(
            label = fieldLabel?.text.toString(),
            prompt = fieldPrompt?.text.toString(),
            activationPromptId = selectedActivationPromptId,
            coreLoreBookId = selectedCoreLoreBookId,
            additionalLoreBookIds = PersonaObject.joinIds(additionalLoreBookIds),
            autoLoadLastLoreBooks = checkboxAutoload?.isChecked == true,
            lastUsedLoreBookIds = PersonaObject.joinIds(lastUsed),
            avatarRef = selectedAvatarRef,
            // Rename keeps the same id; a new companion carries "" and is minted
            // an id on first save.
            id = personaId
        )
    }

    private fun save() {
        if (fieldLabel?.text.toString().isEmpty()) {
            // Inline field error keeps the user on the screen (no lost work).
            fieldLabelError?.text = getString(R.string.label_error_persona_empty)
            fieldLabelError?.visibility = View.VISIBLE
            return
        }

        val persona = buildPersonaObject()
        val result = Intent()
            .putExtra(EXTRA_RESULT_ACTION, ACTION_SAVE)
            .putExtra(EXTRA_ID, persona.id)
            .putExtra(EXTRA_POSITION, position)
            .putExtra(EXTRA_LABEL, persona.label)
            .putExtra(EXTRA_PROMPT, persona.prompt)
            .putExtra(EXTRA_ACTIVATION_ID, persona.activationPromptId)
            .putExtra(EXTRA_CORE_LOREBOOK, persona.coreLoreBookId)
            .putExtra(EXTRA_ADDITIONAL_LOREBOOKS, persona.additionalLoreBookIds)
            .putExtra(EXTRA_AUTOLOAD, persona.autoLoadLastLoreBooks)
            .putExtra(EXTRA_LAST_USED_LOREBOOKS, persona.lastUsedLoreBookIds)
            .putExtra(EXTRA_AVATAR_REF, persona.avatarRef)
        setResult(RESULT_OK, result)
        flashSaveButtonGreen()
        finish()
    }

    /** This screen closes on save with no toast - a brief green flash on the
     *  save icon's own background (owner ruling, July 21 2026) is the only
     *  save confirmation the user sees, visible during the closing
     *  slide-out transition since it's set synchronously right before
     *  finish(). */
    private fun flashSaveButtonGreen() {
        btnSave?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.light_green, theme))
    }

    /** Serialised form of the editable fields, used only for change detection
     *  against initialSnapshot (see attemptExit). The avatar is deliberately
     *  NOT part of this: for an existing companion the picture is persisted the
     *  moment it is picked (immediate-save), so it is never an unsaved edit; for
     *  a new companion the pick is a draft written on creation, and an
     *  image-only pick alone must not trigger the discard prompt. */
    private fun snapshot(): String = listOf(
        fieldLabel?.text?.toString().orEmpty(),
        fieldPrompt?.text?.toString().orEmpty(),
        selectedActivationPromptId,
        selectedCoreLoreBookId,
        PersonaObject.joinIds(additionalLoreBookIds),
        (checkboxAutoload?.isChecked == true).toString()
    ).joinToString("\u0001")

    /** Back / cancel. Confirms first if anything changed since load
     *  (DiscardChangesDialog — the app's standard unsaved-changes confirmation). */
    private fun attemptExit() {
        if (ready && snapshot() != initialSnapshot) {
            DiscardChangesDialog.show(this) { cancel() }
        } else {
            cancel()
        }
    }

    /** Delete confirmation (owner ruling, July 20 2026). Same real Primary/
     *  Destructive two-button shape as the discard dialog (dialog_two_actions),
     *  with its own title + explanatory subtext. Deleting returns ACTION_DELETE
     *  to PersonasListActivity, whose deletePersona now also removes this
     *  companion's memory record and its sole-owned memories (memories shared
     *  with another companion survive) via MemoryCompanionSync.onPersonaDeleted
     *  — hence the "all associated memories that aren't shared" wording. */
    private fun confirmDelete() {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.persona_delete_title)
            .setMessage(R.string.persona_delete_body)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_delete)
            setOnClickListener {
                dialog.dismiss()
                val result = Intent()
                    .putExtra(EXTRA_RESULT_ACTION, ACTION_DELETE)
                    .putExtra(EXTRA_POSITION, position)
                    .putExtra(EXTRA_RESULT_ID, personaId)
                setResult(RESULT_OK, result)
                finish()
            }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /* --------------------------- chrome --------------------------- */

    @Suppress("DEPRECATION")
    private fun applyAmoledChrome() {
        val preferences = Preferences.getPreferences(this, "")
        val amoled = isDarkThemeEnabled() && preferences.getAmoledPitchBlack()
        ThemeManager.getThemeManager().applyTheme(this, amoled)

        if (amoled) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            val amoledTint = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = amoledTint
            btnSave?.backgroundTintList = amoledTint
            btnDelete?.backgroundTintList = amoledTint
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            val barTint = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = barTint
            btnSave?.backgroundTintList = barTint
            btnDelete?.backgroundTintList = barTint
        }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val insets = window.decorView.rootWindowInsets
            actionBar?.setPadding(0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0)
            val navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            // Save moved into the header, so the bottom bar is gone; keep the
            // scroll content clear of the nav bar instead.
            findViewById<ScrollView>(R.id.scroll)?.setPadding(0, 0, 0, dpToPx(12) + navBottom)
        } catch (_: Exception) { /* unused */ }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
