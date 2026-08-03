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
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog

/**
 * Full-screen lorebook editor (owner ruling, Aug 3 2026 - replaces the old
 * EditLoreBookDialogFragment pop-up). Uses the shared house header
 * (Widget.App.ActionBar) titled "Edit {name}" when editing, or "Create
 * Lorebook" for a brand-new book. Save (disk) and, when editing, Delete
 * (trashcan) live in the header; there is no bottom button row.
 *
 * Like the Companion editor it owns no persistence: it validates and returns
 * the result (save or delete) to the caller, which applies it exactly as the
 * old dialog listener did - so the list refresh, persona cleanup on delete,
 * pick-mode selection, and suggestion assignment all stay in the callers.
 */
class EditLoreBookActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_TAG = "tag"
        const val EXTRA_CREATED_AT = "createdAt"
        /** List position of the edited book; -1 means "create a new book". */
        const val EXTRA_POSITION = "position"

        /** RESULT_OK carries one of [ACTION_SAVE] / [ACTION_DELETE]. */
        const val EXTRA_RESULT_ACTION = "result_action"
        const val ACTION_SAVE = "save"
        const val ACTION_DELETE = "delete"

        /** Builds the launch intent for [book] at list [position] (-1 = new). */
        fun createIntent(context: Context, book: LoreBook, position: Int): Intent {
            return Intent(context, EditLoreBookActivity::class.java)
                .putExtra(EXTRA_ID, book.id)
                .putExtra(EXTRA_NAME, book.name)
                .putExtra(EXTRA_DESCRIPTION, book.description)
                .putExtra(EXTRA_TAG, book.tag)
                .putExtra(EXTRA_CREATED_AT, book.createdAt)
                .putExtra(EXTRA_POSITION, position)
        }

        fun readResultAction(data: Intent): String = data.getStringExtra(EXTRA_RESULT_ACTION) ?: ""

        fun readResultPosition(data: Intent): Int = data.getIntExtra(EXTRA_POSITION, -1)

        fun readResultId(data: Intent): String = data.getStringExtra(EXTRA_ID) ?: ""

        /** Reconstructs the edited book from a RESULT_OK save result. Carries the
         *  stable id so the caller saves under it (rename-safe). */
        fun readResultBook(data: Intent): LoreBook {
            return LoreBook(
                id = data.getStringExtra(EXTRA_ID) ?: "",
                name = data.getStringExtra(EXTRA_NAME) ?: "",
                description = data.getStringExtra(EXTRA_DESCRIPTION) ?: "",
                tag = data.getStringExtra(EXTRA_TAG) ?: "",
                createdAt = data.getLongExtra(EXTRA_CREATED_AT, 0L)
            )
        }
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var btnDelete: ImageButton? = null
    private var activityTitle: TextView? = null
    private var fieldNameError: TextView? = null
    private var fieldName: TextInputEditText? = null
    private var fieldDescription: TextInputEditText? = null
    private var fieldTag: TextInputEditText? = null

    private var bookId: String = ""
    private var createdAt: Long = 0L
    private var position: Int = -1

    /** The book's name as first loaded, used for the (stable) header title and
     *  the delete confirmation, so both name "what they are touching" even if the
     *  name field is being edited. */
    private var originalName: String = ""

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
        setContentView(R.layout.activity_edit_lorebook)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        btnDelete = findViewById(R.id.btn_delete)
        activityTitle = findViewById(R.id.activity_title)
        fieldNameError = findViewById(R.id.text_field_name_error)
        fieldName = findViewById(R.id.field_name)
        fieldDescription = findViewById(R.id.field_description)
        fieldTag = findViewById(R.id.field_tag)

        applyAmoledChrome()

        bookId = intent.getStringExtra(EXTRA_ID) ?: ""
        createdAt = intent.getLongExtra(EXTRA_CREATED_AT, 0L)
        position = intent.getIntExtra(EXTRA_POSITION, -1)
        originalName = intent.getStringExtra(EXTRA_NAME) ?: ""

        fieldName?.setText(originalName)
        fieldDescription?.setText(intent.getStringExtra(EXTRA_DESCRIPTION))
        fieldTag?.setText(intent.getStringExtra(EXTRA_TAG))

        // A blank-name error clears itself the moment the user starts fixing it
        // (house rule: field errors live on the input, never a toast/dialog).
        fieldName?.setOnFocusChangeListener { _, _ -> fieldNameError?.visibility = View.GONE }

        activityTitle?.text =
            if (position == -1) getString(R.string.title_create_lorebook)
            else getString(R.string.title_edit_lorebook_fmt, originalName)

        // Delete only exists for a book that has already been created.
        btnDelete?.visibility = if (position == -1) View.GONE else View.VISIBLE
        btnDelete?.setOnClickListener { confirmDelete() }

        onBackPressedDispatcher.addCallback(this) { attemptExit() }
        btnBack?.setOnClickListener { attemptExit() }
        btnSave?.setOnClickListener { save() }

        // Baseline for the unsaved-changes check; every field is set above.
        ready = true
        initialSnapshot = snapshot()
    }

    private fun buildBook(): LoreBook {
        return LoreBook(
            id = bookId,
            name = fieldName?.text.toString().trim(),
            description = fieldDescription?.text.toString().trim(),
            tag = fieldTag?.text.toString().trim(),
            createdAt = createdAt
        )
    }

    private fun save() {
        if (fieldName?.text.toString().isBlank()) {
            // Inline field error keeps the user on the screen (no lost work).
            fieldNameError?.text = getString(R.string.label_error_lorebook_empty)
            fieldNameError?.visibility = View.VISIBLE
            return
        }

        val book = buildBook()
        val result = Intent()
            .putExtra(EXTRA_RESULT_ACTION, ACTION_SAVE)
            .putExtra(EXTRA_ID, book.id)
            .putExtra(EXTRA_NAME, book.name)
            .putExtra(EXTRA_DESCRIPTION, book.description)
            .putExtra(EXTRA_TAG, book.tag)
            .putExtra(EXTRA_CREATED_AT, book.createdAt)
            .putExtra(EXTRA_POSITION, position)
        setResult(RESULT_OK, result)
        flashSaveButtonGreen()
        finish()
    }

    /** This screen closes on save with no toast - a brief green flash on the
     *  save icon's own background is the only save confirmation the user sees,
     *  visible during the closing slide-out transition since it's set
     *  synchronously right before finish() (matches the Companion editor). */
    private fun flashSaveButtonGreen() {
        btnSave?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.light_green, theme))
    }

    /** Serialised form of the editable fields, used only for change detection
     *  against initialSnapshot (see attemptExit). */
    private fun snapshot(): String = listOf(
        fieldName?.text?.toString().orEmpty(),
        fieldDescription?.text?.toString().orEmpty(),
        fieldTag?.text?.toString().orEmpty()
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

    /** Delete confirmation. Title carries the book's name; a short subtext
     *  reads "This can't be undone." The Delete action takes the destructive
     *  slot and Cancel the primary slot (owner ruling, Aug 3 2026). */
    private fun confirmDelete() {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(getString(R.string.lorebook_delete_title_fmt, originalName))
            .setMessage(R.string.lorebook_delete_body)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_delete)
            setOnClickListener {
                dialog.dismiss()
                val result = Intent()
                    .putExtra(EXTRA_RESULT_ACTION, ACTION_DELETE)
                    .putExtra(EXTRA_ID, bookId)
                    .putExtra(EXTRA_POSITION, position)
                setResult(RESULT_OK, result)
                finish()
            }
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

    @Suppress("DEPRECATION")
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
