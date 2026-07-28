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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ActivationPromptObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog

/**
 * Full-screen Activation Prompt editor, same shell as [EditPersonaActivity]:
 * shared house header (Widget.App.ActionBar) with a chained Delete/Save icon
 * pair, DiscardChangesDialog on an unsaved back-out, and the same two-button
 * delete confirmation shape. Owns no persistence: it validates and returns
 * the result (save or delete) to the caller ([ActivationPromptsListActivity]),
 * which applies it exactly as the old dialog listener did.
 */
class EditActivationPromptActivity : FragmentActivity() {

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_POSITION = "position"
        /** The activation prompt's stable id ("" for a brand-new one). */
        const val EXTRA_ID = "id"

        /** RESULT_OK carries one of [ACTION_SAVE] / [ACTION_DELETE]. */
        const val EXTRA_RESULT_ACTION = "result_action"
        const val ACTION_SAVE = "save"
        const val ACTION_DELETE = "delete"
        /** Delete: the activation prompt's stable id. */
        const val EXTRA_RESULT_ID = "result_id"

        /** Builds the launch intent for [activationPrompt] at list [position] (-1 = new). */
        fun createIntent(context: Context, activationPrompt: ActivationPromptObject, position: Int): Intent {
            return Intent(context, EditActivationPromptActivity::class.java)
                .putExtra(EXTRA_ID, activationPrompt.id)
                .putExtra(EXTRA_LABEL, activationPrompt.label)
                .putExtra(EXTRA_PROMPT, activationPrompt.prompt)
                .putExtra(EXTRA_POSITION, position)
        }

        /** Reconstructs the saved ActivationPromptObject from a RESULT_OK save result.
         *  Carries the stable id so the caller saves under it (rename-safe). */
        fun readResultActivationPrompt(data: Intent): ActivationPromptObject {
            return ActivationPromptObject(
                label = data.getStringExtra(EXTRA_LABEL) ?: "",
                prompt = data.getStringExtra(EXTRA_PROMPT) ?: "",
                id = data.getStringExtra(EXTRA_ID) ?: ""
            )
        }
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var fieldTitleError: TextView? = null
    private var fieldTitle: TextInputEditText? = null
    private var fieldPrompt: TextInputEditText? = null
    private var btnSave: ImageButton? = null
    private var btnDelete: ImageButton? = null

    private var position: Int = -1
    private var activationPromptId: String = ""

    /** True once the initial field values are loaded, so the unsaved-changes
     *  check doesn't fire against a half-built screen. */
    private var ready = false

    /** Snapshot of the editable fields as first loaded, for the discard-changes
     *  confirmation on back-out (see DiscardChangesDialog). */
    private var initialSnapshot: String = ""

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_edit_activation_prompt)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        fieldTitleError = findViewById(R.id.text_field_title_error)
        fieldTitle = findViewById(R.id.field_title)
        fieldPrompt = findViewById(R.id.field_prompt)
        btnSave = findViewById(R.id.btn_save)
        btnDelete = findViewById(R.id.btn_delete)

        applyAmoledChrome()

        position = intent.getIntExtra(EXTRA_POSITION, -1)
        activationPromptId = intent.getStringExtra(EXTRA_ID) ?: ""

        activityTitle?.setText(if (position == -1) R.string.label_add_activation_prompt else R.string.label_edit_activation_prompt)

        fieldTitle?.setText(intent.getStringExtra(EXTRA_LABEL))
        fieldPrompt?.setText(intent.getStringExtra(EXTRA_PROMPT))

        fieldTitle?.setOnFocusChangeListener { _, _ -> fieldTitleError?.visibility = View.GONE }

        onBackPressedDispatcher.addCallback(this) { attemptExit() }

        btnBack?.setOnClickListener { attemptExit() }
        btnSave?.setOnClickListener { save() }

        // Delete is only for an existing activation prompt.
        btnDelete?.visibility = if (position == -1) View.GONE else View.VISIBLE
        btnDelete?.setOnClickListener { confirmDelete() }

        // Baseline for the unsaved-changes check; every field is set above.
        ready = true
        initialSnapshot = snapshot()
    }

    /* --------------------------- save / delete --------------------------- */

    private fun buildActivationPromptObject(): ActivationPromptObject {
        return ActivationPromptObject(
            label = fieldTitle?.text.toString(),
            prompt = fieldPrompt?.text.toString(),
            // Rename keeps the same id; a new activation prompt carries "" and
            // is minted an id on first save.
            id = activationPromptId
        )
    }

    private fun save() {
        if (fieldTitle?.text.toString().isEmpty()) {
            // Inline field error keeps the user on the screen (no lost work).
            fieldTitleError?.text = getString(R.string.label_error_activation_prompt_empty)
            fieldTitleError?.visibility = View.VISIBLE
            return
        }

        val activationPrompt = buildActivationPromptObject()
        val result = Intent()
            .putExtra(EXTRA_RESULT_ACTION, ACTION_SAVE)
            .putExtra(EXTRA_ID, activationPrompt.id)
            .putExtra(EXTRA_POSITION, position)
            .putExtra(EXTRA_LABEL, activationPrompt.label)
            .putExtra(EXTRA_PROMPT, activationPrompt.prompt)
        setResult(RESULT_OK, result)
        flashSaveButtonGreen()
        finish()
    }

    /** This screen closes on save with no toast - a brief green flash on the
     *  save icon's own background (same as Edit Companion) is the only save
     *  confirmation the user sees, visible during the closing slide-out
     *  transition since it's set synchronously right before finish(). */
    private fun flashSaveButtonGreen() {
        btnSave?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.light_green, theme))
    }

    /** Serialised form of the editable fields, used only for change detection
     *  against initialSnapshot (see attemptExit). */
    private fun snapshot(): String = listOf(
        fieldTitle?.text?.toString().orEmpty(),
        fieldPrompt?.text?.toString().orEmpty()
    ).joinToString("")

    /** Back / cancel. Confirms first if anything changed since load
     *  (DiscardChangesDialog — the app's standard unsaved-changes confirmation). */
    private fun attemptExit() {
        if (ready && snapshot() != initialSnapshot) {
            DiscardChangesDialog.show(this) { cancel() }
        } else {
            cancel()
        }
    }

    /** Delete confirmation. Same real Primary/Destructive two-button shape as
     *  the discard dialog (dialog_two_actions), with its own title + explanatory
     *  subtext. Deleting returns ACTION_DELETE to ActivationPromptsListActivity. */
    private fun confirmDelete() {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.label_delete_activation_prompt)
            .setMessage(R.string.message_delete_activation_prompt)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_delete)
            setOnClickListener {
                dialog.dismiss()
                val result = Intent()
                    .putExtra(EXTRA_RESULT_ACTION, ACTION_DELETE)
                    .putExtra(EXTRA_POSITION, position)
                    .putExtra(EXTRA_RESULT_ID, activationPromptId)
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
