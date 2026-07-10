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

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SystemPromptsPreferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.activities.memory.ModelRulesActivity

/**
 * "AI System Settings" (Stage 4, owner_approved_rules §11 Revision 5): a
 * plain chevron-row screen that groups the machine-level instructions the
 * app sends every turn — the chat's System prompt (moved here out of the
 * Characters identity hub) and Model rules. A top hint (owner-approved
 * words) reminds the user that longer prompts/rules cost context every turn.
 *
 * The System prompts row opens the user's system prompt library
 * (SystemPromptsListActivity) — multiple saved prompts, one chosen at a time.
 * The "Automatically Apply Model Rules" switch is the GLOBAL default; the
 * per-chat "Apply Model Rules" override lives in Quick Settings.
 */
class AiSystemSettingsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var rowSystemPrompt: LinearLayout? = null
    private var textSystemPromptSubtitle: TextView? = null
    private var rowModelRules: LinearLayout? = null
    private var switchAutoApplyModelRules: MaterialSwitch? = null

    private var systemPromptsPreferences: SystemPromptsPreferences? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_ai_system_settings)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)
        systemPromptsPreferences = SystemPromptsPreferences.getSystemPromptsPreferences(this)

        bindViews()
        applyTheme()
        loadValues()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        rowSystemPrompt = findViewById(R.id.row_system_prompt)
        textSystemPromptSubtitle = findViewById(R.id.text_system_prompt_subtitle)
        rowModelRules = findViewById(R.id.row_model_rules)
        switchAutoApplyModelRules = findViewById(R.id.switch_auto_apply_model_rules)
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
        textSystemPromptSubtitle?.text = systemPromptPreview()
        switchAutoApplyModelRules?.isChecked = preferences?.getAutoApplyModelRules() ?: true
    }

    override fun onResume() {
        super.onResume()
        // The library may have changed (selection/add/edit/delete) while away.
        textSystemPromptSubtitle?.text = systemPromptPreview()
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        rowSystemPrompt?.setOnClickListener {
            startActivity(Intent(this, SystemPromptsListActivity::class.java))
        }

        rowModelRules?.setOnClickListener {
            startActivity(Intent(this, ModelRulesActivity::class.java).putExtra("chatId", chatId))
        }

        switchAutoApplyModelRules?.setOnCheckedChangeListener { _, checked ->
            preferences?.setAutoApplyModelRules(checked)
        }
    }

    private fun systemPromptPreview(): String {
        val effective = systemPromptsPreferences?.getEffectivePrompt()
        return effective?.title ?: getString(R.string.system_prompt_none)
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
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }
}
