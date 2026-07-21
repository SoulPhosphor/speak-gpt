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

package org.teslasoft.assistant.ui.activities.memory

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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.activities.ProfileImagesActivity
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.util.ProfileImageBinder
import org.teslasoft.assistant.util.ProfileImageResolver

/**
 * Full-screen My Personas editor (owner ruling, July 20 2026 - replaces the
 * old EditUserPersonaDialogFragment pop-up). Uses the shared house header
 * (Widget.App.ActionBar) titled "Edit Persona" / "Persona Creation", mirroring
 * Edit Companion's chrome, field styles and dialog shapes exactly (see
 * EditPersonaActivity) with the category noun swapped where the wording is
 * companion-specific. The persona picture at the top uses the same geometry
 * as Edit Companion's (Profile Images phase 8); an unassigned persona shows
 * the Default Personal Avatar (user-side cascade), then the generic person
 * icon only if no Personal Default is set.
 *
 * It owns no persistence: it validates and returns the result (save or
 * delete) to the caller ([MemoryUserPersonasActivity]), which does the store
 * work exactly as its old dialog listener did. Both the Short Description
 * (v16 short_description column) and the picture (imageRef) ride the result.
 */
class EditUserPersonaActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PERSONA_ID = "personaId"
        const val EXTRA_NAME = "name"
        const val EXTRA_PRESENTATION = "presentation"
        const val EXTRA_SHORT_DESCRIPTION = "shortDescription"
        const val EXTRA_IMAGE_REF = "imageRef"

        /** RESULT_OK carries one of [ACTION_SAVE] / [ACTION_DELETE]. */
        const val EXTRA_RESULT_ACTION = "result_action"
        const val ACTION_SAVE = "save"
        const val ACTION_DELETE = "delete"
        const val EXTRA_RESULT_NAME = "result_name"
        const val EXTRA_RESULT_PRESENTATION = "result_presentation"
        const val EXTRA_RESULT_SHORT_DESCRIPTION = "result_short_description"
        const val EXTRA_RESULT_IMAGE_REF = "result_image_ref"

        /** The My Personas list row caps its subtitle at this many lines, with
         *  no character maximum to match instead. */
        private const val SHORT_DESCRIPTION_MAX_LINES = 3

        private const val STATE_IMAGE_REF = "state_image_ref"

        /** [personaId] empty = a new persona. */
        fun createIntent(
            context: Context,
            personaId: String,
            name: String,
            presentation: String,
            shortDescription: String,
            imageRef: String
        ): Intent {
            return Intent(context, EditUserPersonaActivity::class.java)
                .putExtra(EXTRA_PERSONA_ID, personaId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PRESENTATION, presentation)
                .putExtra(EXTRA_SHORT_DESCRIPTION, shortDescription)
                .putExtra(EXTRA_IMAGE_REF, imageRef)
        }
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var btnSave: ImageButton? = null
    private var btnDelete: ImageButton? = null

    private var imgPersonaAvatar: ImageView? = null
    private var fieldName: TextInputEditText? = null
    private var textNameError: TextView? = null
    private var fieldShortDescription: TextInputEditText? = null
    private var textShortDescriptionCounter: TextView? = null
    private var fieldPresentation: TextInputEditText? = null
    private var textPresentationError: TextView? = null

    private var personaId: String = ""
    private var shortDescriptionOverLimit = false

    /** The persona's assigned Profile Image hash, held until Save. */
    private var selectedImageRef: String = ""

    /** True once the initial field values are loaded, so the unsaved-changes
     *  check doesn't fire against a half-built screen. */
    private var ready = false

    /** Snapshot of the editable fields as first loaded, for the discard-
     *  changes confirmation on back-out (see DiscardChangesDialog). Includes
     *  the Short Description and the picture so typed-but-unsaved changes
     *  aren't silently lost either. */
    private var initialSnapshot: String = ""

    // Registered as an activity field so a pending gallery result survives
    // recreation (same lifecycle-safe pattern as the Companion editor). The
    // gallery's companion-assignment mode is the generic "return a picked
    // hash to the caller" flow - it writes no preference and shows no
    // companion-specific wording - so it is reused here as-is.
    private val pickPictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val hash = result.data?.getStringExtra(ProfileImagesActivity.EXTRA_RESULT_ASSIGNED_HASH)
            if (!hash.isNullOrEmpty()) {
                selectedImageRef = hash
                updateAvatarUi()
                persistImageOnlyIfExisting(hash)
            }
        }
    }

    /**
     * Existing persona (has a stable id): the image tap IS the save — persist
     * only image_ref immediately, by its stable id, through the narrow
     * [MemoryStore.setUserPersonaImageRef] which UPDATEs only that one column.
     * It never writes back the name/presentation/short-description draft still
     * in the editor, and backing out cannot undo the picture. A brand-new
     * persona (blank id) keeps the pick in draft — written when the persona is
     * first created, so cancelling creation leaves no record.
     */
    private fun persistImageOnlyIfExisting(hash: String) {
        if (personaId.isEmpty()) return
        if (!MemoryStore.isProvisioned(this)) return
        val id = personaId
        val context = this
        Thread {
            try {
                MemoryStore.getInstance(context).setUserPersonaImageRef(id, hash)
            } catch (_: Exception) { /* best-effort; the editor Save still persists it */ }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_edit_user_persona)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        btnSave = findViewById(R.id.btn_save)
        btnDelete = findViewById(R.id.btn_delete)

        imgPersonaAvatar = findViewById(R.id.img_persona_avatar)
        fieldName = findViewById(R.id.field_persona_name)
        textNameError = findViewById(R.id.text_persona_name_error)
        fieldShortDescription = findViewById(R.id.field_short_description)
        textShortDescriptionCounter = findViewById(R.id.text_short_description_counter)
        fieldPresentation = findViewById(R.id.field_presentation)
        textPresentationError = findViewById(R.id.text_presentation_error)

        applyAmoledChrome()

        personaId = intent.getStringExtra(EXTRA_PERSONA_ID) ?: ""

        activityTitle?.setText(if (personaId.isEmpty()) R.string.title_mem_pers_creation else R.string.title_mem_pers_edit)

        fieldName?.setText(intent.getStringExtra(EXTRA_NAME))
        fieldPresentation?.setText(intent.getStringExtra(EXTRA_PRESENTATION))
        fieldShortDescription?.setText(intent.getStringExtra(EXTRA_SHORT_DESCRIPTION))

        // Restore the pending pick across recreation; else the saved imageRef.
        selectedImageRef = savedInstanceState?.getString(STATE_IMAGE_REF)
            ?: (intent.getStringExtra(EXTRA_IMAGE_REF) ?: "")

        // The picture itself is the control: tap it to pick a different image.
        imgPersonaAvatar?.setOnClickListener { openGalleryForPicture() }
        updateAvatarUi()

        fieldName?.setOnFocusChangeListener { _, _ -> textNameError?.visibility = View.GONE }
        fieldPresentation?.setOnFocusChangeListener { _, _ -> textPresentationError?.visibility = View.GONE }

        // Keep the picture's screen-reader label in sync with the name (the
        // approved a11y scheme labels an assigned picture "<Name>'s picture").
        fieldName?.doAfterTextChanged { updateAvatarContentDescription() }

        fieldShortDescription?.doAfterTextChanged { updateShortDescriptionCounter() }
        fieldShortDescription?.post { updateShortDescriptionCounter() }

        onBackPressedDispatcher.addCallback(this) { attemptExit() }

        btnBack?.setOnClickListener { attemptExit() }
        btnSave?.setOnClickListener { save() }

        // Delete is only for an existing persona.
        btnDelete?.visibility = if (personaId.isEmpty()) View.GONE else View.VISIBLE
        btnDelete?.setOnClickListener { confirmDelete() }

        // Baseline for the unsaved-changes check; every field is set above.
        ready = true
        initialSnapshot = snapshot()
    }

    override fun onResume() {
        super.onResume()
        // The Default Shape or the Personal Default may have changed on another
        // screen; re-resolve the preview cheaply.
        updateAvatarUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_IMAGE_REF, selectedImageRef)
    }

    /* --------------------------- picture --------------------------- */

    /** Shows the persona's own picture, or - when it has none - the Default
     *  Personal Avatar (user-side cascade, owner ruling July 21 2026), through
     *  the shared binder so the current Default Shape applies. The generic
     *  person icon is the last resort, shown only when no Personal Default is
     *  set either. */
    private fun updateAvatarUi() {
        val imageView = imgPersonaAvatar ?: return
        val file = ProfileImageResolver.resolveUserImageFile(this, selectedImageRef)
        val shape = GlobalPreferences.getPreferences(this).getProfileImageShape()
        ProfileImageBinder.bind(this, imageView, file, shape) { iv ->
            iv.setImageResource(R.drawable.ic_user)
            // accent_900 is the app's established glyph tint - a plain color
            // resource, avoiding the material colorPrimary code-side resolution
            // failure this project has hit in CI before.
            iv.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(iv.context, R.color.accent_900))
        }
        updateAvatarContentDescription()
    }

    /** Screen-reader label for the picture slot (owner-approved a11y scheme):
     *  "<Name>'s picture" once assigned, else the default that actually shows. */
    private fun updateAvatarContentDescription() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        // Before the persona is named, don't announce "'s picture" with an
        // empty name - fall through to the default-state wording.
        val refForLabel = if (name.isNotEmpty()) selectedImageRef else ""
        imgPersonaAvatar?.contentDescription =
            ProfileImageResolver.userContentDescription(this, name, refForLabel)
    }

    private fun openGalleryForPicture() {
        val intent = Intent(this, ProfileImagesActivity::class.java)
            .putExtra(ProfileImagesActivity.EXTRA_ASSIGN_TARGET, ProfileImagesActivity.TARGET_COMPANION)
            .putExtra(ProfileImagesActivity.EXTRA_ASSIGN_CURRENT_HASH, selectedImageRef)
        pickPictureLauncher.launch(intent)
    }

    /* --------------------------- short description counter --------------------------- */

    /** Reads the box's OWN real text layout to find where a 4th line would
     *  start, rather than guessing a fixed character count that would drift
     *  from the actual rendered width/font. Never blocks typing - only
     *  colors the counter and swaps its text for the removal instruction;
     *  save() is what actually blocks. */
    private fun updateShortDescriptionCounter() {
        val field = fieldShortDescription ?: return
        val counter = textShortDescriptionCounter ?: return
        val text = field.text?.toString().orEmpty()
        val layout = field.layout
        val fourthLineStart = if (layout != null && layout.lineCount > SHORT_DESCRIPTION_MAX_LINES) {
            layout.getLineStart(SHORT_DESCRIPTION_MAX_LINES)
        } else {
            null
        }

        if (fourthLineStart != null && text.length > fourthLineStart) {
            shortDescriptionOverLimit = true
            counter.text = getString(R.string.mem_pers_short_desc_over, text.length - fourthLineStart)
            counter.setTextColor(MaterialColors.getColor(counter, androidx.appcompat.R.attr.colorError))
        } else {
            shortDescriptionOverLimit = false
            counter.text = text.length.toString()
            counter.setTextColor(ResourcesCompat.getColor(resources, R.color.text_subtitle, theme))
        }
    }

    /* --------------------------- save / delete --------------------------- */

    private fun save() {
        val name = fieldName?.text?.toString()?.trim().orEmpty()
        val presentation = fieldPresentation?.text?.toString()?.trim().orEmpty()

        var hasError = false
        if (name.isEmpty()) {
            textNameError?.text = getString(R.string.mem_pers_error_name_empty)
            textNameError?.visibility = View.VISIBLE
            hasError = true
        }
        if (presentation.isEmpty()) {
            textPresentationError?.text = getString(R.string.mem_pers_error_presentation_empty)
            textPresentationError?.visibility = View.VISIBLE
            hasError = true
        }
        if (hasError) return

        // Never blocks typing, only Save (owner ruling) - a snackbar explains
        // why, the user trims the box, taps Save again.
        if (shortDescriptionOverLimit) {
            Snackbar.make(findViewById<View>(R.id.root), getString(R.string.mem_pers_short_desc_snackbar), Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.okay) { /* dismiss */ }
                .show()
            return
        }

        val result = Intent()
            .putExtra(EXTRA_RESULT_ACTION, ACTION_SAVE)
            .putExtra(EXTRA_PERSONA_ID, personaId)
            .putExtra(EXTRA_RESULT_NAME, name)
            .putExtra(EXTRA_RESULT_PRESENTATION, presentation)
            .putExtra(EXTRA_RESULT_SHORT_DESCRIPTION, fieldShortDescription?.text?.toString()?.trim().orEmpty())
            .putExtra(EXTRA_RESULT_IMAGE_REF, selectedImageRef)
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
     *  against initialSnapshot (see attemptExit). The picture is deliberately
     *  NOT part of this: for an existing persona it is persisted the moment it
     *  is picked (immediate-save), so it is never an unsaved edit; for a new
     *  persona the pick is a draft written on creation, and an image-only pick
     *  alone must not trigger the discard prompt. */
    private fun snapshot(): String = listOf(
        fieldName?.text?.toString().orEmpty(),
        fieldShortDescription?.text?.toString().orEmpty(),
        fieldPresentation?.text?.toString().orEmpty()
    ).joinToString("")

    /** Back / cancel. Confirms first if anything changed since load
     *  (DiscardChangesDialog - the app's standard unsaved-changes confirmation). */
    private fun attemptExit() {
        if (ready && snapshot() != initialSnapshot) {
            DiscardChangesDialog.show(this) { cancel() }
        } else {
            cancel()
        }
    }

    /** Delete confirmation (owner-specified wording, July 21 2026): title
     *  only, no body - "Delete this persona?" / Delete / Cancel. Same real
     *  Primary/Destructive two-button shape as the discard dialog
     *  (dialog_two_actions); the App_MaterialAlertDialog theme centers the
     *  title on its own. */
    private fun confirmDelete() {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_pers_delete_title_full)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_delete)
            setOnClickListener {
                dialog.dismiss()
                val result = Intent().putExtra(EXTRA_RESULT_ACTION, ACTION_DELETE).putExtra(EXTRA_PERSONA_ID, personaId)
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
            findViewById<ScrollView>(R.id.scroll)?.setPadding(0, 0, 0, dpToPx(12) + navBottom)
        } catch (_: Exception) { /* unused */ }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
