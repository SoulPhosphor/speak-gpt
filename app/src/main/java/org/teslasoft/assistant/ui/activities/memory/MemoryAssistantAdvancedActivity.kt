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

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.assistant.MemoryAssistantPrompt
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Memory Assistant's Advanced Settings (Phase 6, task 6.2 — the exact
 * owner decisions in phase6_memory_assistant_work_order.md): Temperature as a
 * real number with "Recommended: 0.3" always visible (range 0.0–2.0, room to
 * push it AND a way back), Maximum Suggestions Per Conversation shipping Off,
 * and the editable Extraction Prompt whose baseline lives in code and can
 * never be destroyed — Reset ("Restore default extraction prompt?") clears
 * the override and the original returns.
 */
class MemoryAssistantAdvancedActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var fieldTemperature: TextInputEditText? = null
    private var switchMaxSuggestions: MaterialSwitch? = null
    private var layoutMaxSuggestions: TextInputLayout? = null
    private var fieldMaxSuggestions: TextInputEditText? = null
    private var fieldPrompt: TextInputEditText? = null
    private var btnResetPrompt: MaterialButton? = null
    private var btnSave: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_assistant_advanced)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        loadValues()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        fieldTemperature = findViewById(R.id.field_temperature)
        switchMaxSuggestions = findViewById(R.id.switch_max_suggestions)
        layoutMaxSuggestions = findViewById(R.id.layout_max_suggestions)
        fieldMaxSuggestions = findViewById(R.id.field_max_suggestions)
        fieldPrompt = findViewById(R.id.field_prompt)
        btnResetPrompt = findViewById(R.id.btn_reset_prompt)
        btnSave = findViewById(R.id.btn_save)
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true)

        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }
    }

    private fun loadValues() {
        fieldTemperature?.setText(preferences?.getMemoryAssistantTemperature()?.toString() ?: "0.3")

        val cap = preferences?.getMemoryAssistantMaxSuggestions() ?: 0
        switchMaxSuggestions?.isChecked = cap > 0
        layoutMaxSuggestions?.visibility = if (cap > 0) View.VISIBLE else View.GONE
        if (cap > 0) fieldMaxSuggestions?.setText(cap.toString())

        // The field always shows what is actually sent: the user's edit when
        // one exists, the code baseline otherwise.
        fieldPrompt?.setText(MemoryAssistantPrompt.effectivePrompt(this))
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        switchMaxSuggestions?.setOnCheckedChangeListener { _, checked ->
            layoutMaxSuggestions?.visibility = if (checked) View.VISIBLE else View.GONE
        }

        btnResetPrompt?.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setMessage(R.string.memory_assistant_reset_confirm)
                .setPositiveButton(R.string.memory_assistant_reset) { _, _ ->
                    // Reset = clear the override; the baseline constant in
                    // code is untouched by design and can never be lost.
                    preferences?.setMemoryAssistantPromptOverride("")
                    fieldPrompt?.setText(MemoryAssistantPrompt.BASELINE)
                }
                .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                .show()
        }

        btnSave?.setOnClickListener { save() }
    }

    private fun save() {
        // Clamped to the approved 0.0–2.0 range; unparseable input falls back
        // to the recommended value rather than failing the save.
        val temperature = (fieldTemperature?.text?.toString()?.trim()?.toFloatOrNull() ?: 0.3f)
            .coerceIn(0.0f, 2.0f)
        preferences?.setMemoryAssistantTemperature(temperature)
        fieldTemperature?.setText(temperature.toString())

        val cap = if (switchMaxSuggestions?.isChecked == true) {
            (fieldMaxSuggestions?.text?.toString()?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0)
        } else 0
        preferences?.setMemoryAssistantMaxSuggestions(cap)
        if (cap == 0) {
            switchMaxSuggestions?.isChecked = false
            layoutMaxSuggestions?.visibility = View.GONE
        }

        // Saving the unedited baseline stores nothing — the prompt keeps
        // following the code original (including future approved updates)
        // until the user actually changes the text.
        val text = fieldPrompt?.text?.toString() ?: ""
        preferences?.setMemoryAssistantPromptOverride(
            if (text.trim() == MemoryAssistantPrompt.BASELINE.trim()) "" else text
        )

        Toast.makeText(this, R.string.memory_assistant_saved, Toast.LENGTH_SHORT).show()
        finish()
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

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }
}
