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

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SystemPromptsPreferences
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Full-screen editor for one system prompt (owner: never a pop-up). Reached from
 * the library list — with an empty `promptId` it adds a new one, otherwise it
 * edits in place keeping the id stable.
 *
 * Saving/deleting keeps the global system message mirrored to the effective
 * prompt through [SystemPromptsPreferences.applyEffectiveToGlobal], so the
 * generation path stays untouched.
 */
class SystemPromptEditorActivity : FragmentActivity() {

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var titleFieldError: TextView? = null
    private var titleInput: TextInputEditText? = null
    private var bodyInput: TextInputEditText? = null
    private var btnSave: ImageButton? = null
    private var btnDelete: ImageButton? = null

    private var systemPromptsPreferences: SystemPromptsPreferences? = null
    private var preferences: Preferences? = null

    private var promptId: String = ""

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_system_prompt_editor)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        titleFieldError = findViewById(R.id.text_field_title_error)
        titleInput = findViewById(R.id.title_input)
        bodyInput = findViewById(R.id.body_input)
        btnSave = findViewById(R.id.btn_save)
        btnDelete = findViewById(R.id.btn_delete)

        preferences = Preferences.getPreferences(this, "")
        systemPromptsPreferences = SystemPromptsPreferences.getSystemPromptsPreferences(this)

        promptId = intent.getStringExtra("promptId") ?: ""

        applyTheme()

        val existing = if (promptId.isNotEmpty()) systemPromptsPreferences?.getById(promptId) else null
        if (existing != null) {
            activityTitle?.text = getString(R.string.title_edit_system_prompt)
            titleInput?.setText(existing.title)
            bodyInput?.setText(existing.body)
            btnDelete?.visibility = View.VISIBLE
        } else {
            activityTitle?.text = getString(R.string.title_new_system_prompt)
            btnDelete?.visibility = View.GONE
            // A stale id (its prompt was deleted elsewhere) falls back to "add".
            promptId = ""
        }

        btnBack?.setOnClickListener { finish() }
        btnSave?.setOnClickListener { save() }
        btnDelete?.setOnClickListener { confirmDelete() }

        titleInput?.setOnFocusChangeListener { _, _ -> titleFieldError?.visibility = View.GONE }
    }

    private fun save() {
        val title = titleInput?.text?.toString()?.trim() ?: ""
        val body = bodyInput?.text?.toString() ?: ""

        if (title.isEmpty()) {
            // Persistent, inline field error — never a toast (owner rule).
            titleFieldError?.text = getString(R.string.system_prompt_needs_title)
            titleFieldError?.visibility = View.VISIBLE
            return
        }

        if (promptId.isEmpty()) {
            promptId = systemPromptsPreferences?.add(title, body) ?: ""
        } else {
            systemPromptsPreferences?.update(promptId, title, body)
        }

        systemPromptsPreferences?.applyEffectiveToGlobal(preferences ?: return)
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.system_prompt_delete_title)
            .setMessage(R.string.system_prompt_delete_message)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                if (promptId.isNotEmpty()) {
                    systemPromptsPreferences?.delete(promptId)
                    systemPromptsPreferences?.applyEffectiveToGlobal(preferences ?: return@setPositiveButton)
                }
                finish()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        ThemeManager.getThemeManager()
            .applyTheme(this, isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true)

        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
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
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            val scroll = findViewById<ScrollView>(R.id.scroll)
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }
}
