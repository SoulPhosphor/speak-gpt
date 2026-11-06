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

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager

class CharactersActivity : SettingsPageActivity() {

    private var btnBack: ImageButton? = null
    private var actionBar: ConstraintLayout? = null

    private var chatId: String = ""
    private var preferences: Preferences? = null

    private var rowPersonas: LinearLayout? = null
    private var rowMyPersonas: LinearLayout? = null
    private var rowActivationPrompts: LinearLayout? = null
    // Lorebooks moved to the Memory Manager (owner ruling, July 8 2026): they
    // are memory notes, so they belong with the rest of the memory system.

    private var personasActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val personaId = result.data?.getStringExtra("personaId")
            if (personaId != null) {
                preferences?.setPersonaId(personaId)
                // This selection belongs to the current chat. It becomes the
                // new-chat default only after an assistant response succeeds.
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        setContentView(R.layout.activity_characters)

        chatId = intent.getStringExtra("chatId") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        btnBack = findViewById(R.id.btn_back)
        actionBar = findViewById(R.id.action_bar)
        rowPersonas = findViewById(R.id.row_personas)
        rowMyPersonas = findViewById(R.id.row_my_personas)
        rowActivationPrompts = findViewById(R.id.row_activation_prompts)

        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences!!.getAmoledPitchBlack())

        if (isDarkThemeEnabled() && preferences!!.getAmoledPitchBlack()) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)

            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }

            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            val colorDrawable = SurfaceColors.SURFACE_0.getColor(this).toDrawable()
            window.setBackgroundDrawable(colorDrawable)

            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }

            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }

        btnBack?.setOnClickListener { finish() }

        initializeRows()
    }

    private fun initializeRows() {
        rowPersonas?.setOnClickListener {
            personasActivityResultLauncher.launch(Intent(this, PersonasListActivity::class.java))
        }

        rowMyPersonas?.setOnClickListener {
            startActivity(
                Intent(this, org.teslasoft.assistant.ui.activities.memory.MemoryUserPersonasActivity::class.java)
                    .putExtra("chatId", chatId)
            )
        }

        rowActivationPrompts?.setOnClickListener {
            startActivity(Intent(this, ActivationPromptsListActivity::class.java))
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
        } catch (_: Exception) { /* unused */ }
    }
}
