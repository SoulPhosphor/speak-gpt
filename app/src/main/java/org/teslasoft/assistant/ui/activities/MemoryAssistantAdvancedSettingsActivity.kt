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
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistPrompt
import org.teslasoft.assistant.theme.ThemeManager
import java.util.Locale

/**
 * "Memory Assistant Advanced Settings" — AI extraction tuning
 * (`Memory System/memory_settings_reorg_spec.md` §2, July 9 2026). Three
 * controls and a Save button, nothing else: analysis Temperature (0.0–2.0,
 * recommended 0.3), the Minimum Importance a draft must reach, and the
 * Extraction Prompt (blank == the built-in `ArchivistPrompt.SYSTEM`). Reached
 * from the Memory Assistant section of Memory Controls.
 *
 * User-facing name is "Memory Assistant"; the `Preferences.getArchivist*`
 * accessors keep the internal code name.
 */
class MemoryAssistantAdvancedSettingsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var sliderTemperature: Slider? = null
    private var textTemperatureValue: TextView? = null
    private var btnResetTemperature: MaterialButton? = null
    private var rowMinImportance: LinearLayout? = null
    private var textMinImportanceValue: TextView? = null
    private var fieldExtractionPrompt: TextInputEditText? = null
    private var btnResetPrompt: MaterialButton? = null
    private var btnSave: MaterialButton? = null

    /** Held until Save (spec §2 has an explicit Save button). */
    private var selectedImportance = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_assistant_advanced_settings)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        sliderTemperature = findViewById(R.id.slider_temperature)
        textTemperatureValue = findViewById(R.id.text_temperature_value)
        btnResetTemperature = findViewById(R.id.btn_reset_temperature)
        rowMinImportance = findViewById(R.id.row_min_importance)
        textMinImportanceValue = findViewById(R.id.text_min_importance_value)
        fieldExtractionPrompt = findViewById(R.id.field_extraction_prompt)
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

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        // Temperature (0.0–2.0, recommended 0.3). Persisted on Save.
        val temperature = (preferences?.getArchivistTemperature() ?: RECOMMENDED_TEMPERATURE)
            .coerceIn(0.0f, 2.0f)
        sliderTemperature?.value = roundToStep(temperature)
        updateTemperatureLabel(sliderTemperature?.value ?: temperature)
        sliderTemperature?.addOnChangeListener { _, value, _ -> updateTemperatureLabel(value) }
        btnResetTemperature?.setOnClickListener {
            sliderTemperature?.value = RECOMMENDED_TEMPERATURE
            updateTemperatureLabel(RECOMMENDED_TEMPERATURE)
        }

        // Minimum Importance (1–5 on the existing importance scale). Persisted on Save.
        selectedImportance = (preferences?.getArchivistMinImportance() ?: 1).coerceIn(1, 5)
        updateImportanceLabel()
        rowMinImportance?.setOnClickListener { showImportancePicker() }

        // Extraction Prompt. Blank pref == the built-in default, so an unset
        // prompt shows the built-in text (editable). Save stores "" again when
        // the field still equals the built-in default (so Reset survives Save).
        val stored = preferences?.getArchivistCustomPrompt().orEmpty()
        fieldExtractionPrompt?.setText(stored.ifEmpty { ArchivistPrompt.SYSTEM })
        btnResetPrompt?.setOnClickListener { showResetPromptDialog() }

        btnSave?.setOnClickListener { save() }
    }

    private fun updateTemperatureLabel(value: Float) {
        textTemperatureValue?.text = String.format(Locale.getDefault(), "%.1f", value)
    }

    /** Snap a stored value onto the slider's 0.1 grid so it can be displayed. */
    private fun roundToStep(value: Float): Float = (Math.round(value * 10f) / 10f).coerceIn(0.0f, 2.0f)

    private fun importanceLabel(level: Int): String = when (level) {
        2 -> getString(R.string.mem_importance_2)
        3 -> getString(R.string.mem_importance_3)
        4 -> getString(R.string.mem_importance_4)
        5 -> getString(R.string.mem_importance_5)
        else -> getString(R.string.mem_importance_1)
    }

    private fun updateImportanceLabel() {
        textMinImportanceValue?.text = importanceLabel(selectedImportance)
    }

    private fun showImportancePicker() {
        val labels = arrayOf(
            getString(R.string.mem_importance_1),
            getString(R.string.mem_importance_2),
            getString(R.string.mem_importance_3),
            getString(R.string.mem_importance_4),
            getString(R.string.mem_importance_5)
        )
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_assistant_adv_min_importance)
            .setSingleChoiceItems(labels, selectedImportance - 1) { dialog, which ->
                selectedImportance = (which + 1).coerceIn(1, 5)
                updateImportanceLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showResetPromptDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_assistant_adv_reset_prompt_confirm)
            .setPositiveButton(R.string.memory_assistant_adv_reset_prompt) { _, _ ->
                // Clear the pref back to "" (use built-in) and re-show the
                // built-in text so the field reflects what will run.
                preferences?.setArchivistCustomPrompt("")
                fieldExtractionPrompt?.setText(ArchivistPrompt.SYSTEM)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun save() {
        preferences?.setArchivistTemperature(sliderTemperature?.value ?: RECOMMENDED_TEMPERATURE)
        preferences?.setArchivistMinImportance(selectedImportance)

        // "" means "use the built-in prompt". Storing the built-in text
        // verbatim is the same intent, so collapse it back to "" — that keeps a
        // prior Reset intact across a Save with no edits.
        val text = fieldExtractionPrompt?.text?.toString()?.trim().orEmpty()
        preferences?.setArchivistCustomPrompt(if (text.isEmpty() || text == ArchivistPrompt.SYSTEM.trim()) "" else text)

        Toast.makeText(this, R.string.memory_assistant_adv_saved, Toast.LENGTH_SHORT).show()
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
            val scroll = findViewById<android.widget.ScrollView>(R.id.scroll)
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

    companion object {
        private const val RECOMMENDED_TEMPERATURE = 0.3f
    }
}
