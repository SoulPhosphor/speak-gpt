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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
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
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog

/**
 * Full-screen My Personas editor (owner ruling, July 20 2026 - replaces the
 * old EditUserPersonaDialogFragment pop-up). Uses the shared house header
 * (Widget.App.ActionBar) titled "Edit Persona" / "Persona Creation", mirroring
 * Edit Companion's chrome, field styles and dialog shapes exactly (see
 * EditPersonaActivity) with the category noun swapped where the wording is
 * companion-specific. No picture at the top yet - see the layout's own doc
 * comment for why the spacing is kept as if there were one.
 *
 * It owns no persistence: it validates and returns the result (save or
 * delete) to the caller ([MemoryUserPersonasActivity]), which does the store
 * work exactly as its old dialog listener did.
 *
 * The Short Description field is visual only for now (not read from or
 * written to the store) - it has no backing column yet.
 */
class EditUserPersonaActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PERSONA_ID = "personaId"
        const val EXTRA_NAME = "name"
        const val EXTRA_PRESENTATION = "presentation"

        /** RESULT_OK carries one of [ACTION_SAVE] / [ACTION_DELETE]. */
        const val EXTRA_RESULT_ACTION = "result_action"
        const val ACTION_SAVE = "save"
        const val ACTION_DELETE = "delete"
        const val EXTRA_RESULT_NAME = "result_name"
        const val EXTRA_RESULT_PRESENTATION = "result_presentation"

        /** The My Personas list row (view_memory_row.xml) caps its subtitle
         *  at this many lines, with no character maximum to match instead. */
        private const val SHORT_DESCRIPTION_MAX_LINES = 3

        /** [personaId] empty = a new persona. */
        fun createIntent(context: Context, personaId: String, name: String, presentation: String): Intent {
            return Intent(context, EditUserPersonaActivity::class.java)
                .putExtra(EXTRA_PERSONA_ID, personaId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PRESENTATION, presentation)
        }
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var btnSave: ImageButton? = null
    private var btnDelete: ImageButton? = null

    private var fieldName: TextInputEditText? = null
    private var textNameError: TextView? = null
    private var fieldShortDescription: TextInputEditText? = null
    private var textShortDescriptionCounter: TextView? = null
    private var fieldPresentation: TextInputEditText? = null
    private var textPresentationError: TextView? = null

    private var personaId: String = ""
    private var shortDescriptionOverLimit = false

    /** True once the initial field values are loaded, so the unsaved-changes
     *  check doesn't fire against a half-built screen. */
    private var ready = false

    /** Snapshot of the editable fields as first loaded, for the discard-
     *  changes confirmation on back-out (see DiscardChangesDialog). Includes
     *  the not-yet-wired Short Description so typed-but-unsaved text there
     *  isn't silently lost either. */
    private var initialSnapshot: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_edit_user_persona)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        btnSave = findViewById(R.id.btn_save)
        btnDelete = findViewById(R.id.btn_delete)

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

        fieldName?.setOnFocusChangeListener { _, _ -> textNameError?.visibility = View.GONE }
        fieldPresentation?.setOnFocusChangeListener { _, _ -> textPresentationError?.visibility = View.GONE }

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
        setResult(RESULT_OK, result)
        finish()
    }

    /** Serialised form of the editable fields, used only for change detection
     *  against initialSnapshot (see attemptExit). */
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

    /** Delete confirmation. Reuses the existing "Delete persona?" wording
     *  (already used by the My Personas list's own row menu) rather than
     *  Edit Companion's delete-body text, which describes a memory cascade
     *  that doesn't apply here - a persona is only a presentation variant,
     *  it owns no memories of its own to delete. Same real Primary/
     *  Destructive two-button shape as the discard dialog (dialog_two_actions). */
    private fun confirmDelete() {
        val name = fieldName?.text?.toString().orEmpty()
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_pers_delete_persona_title)
            .setMessage(getString(R.string.mem_pers_delete_persona_msg, name))
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
