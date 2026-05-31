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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.TileFragment

class CharactersActivity : FragmentActivity() {

    private var btnBack: ImageButton? = null
    private var actionBar: ConstraintLayout? = null

    private var chatId: String = ""
    private var preferences: Preferences? = null
    private var personaPreferences: PersonaPreferences? = null

    private var tilePersonas: TileFragment? = null

    private var personasActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val personaId = result.data?.getStringExtra("personaId")
            if (personaId != null) {
                preferences?.setPersonaId(personaId)
                tilePersonas?.updateSubtitle(getActivePersonaLabel())
            }
        }
    }

    private fun getActivePersonaLabel(): String {
        val personaId = preferences?.getPersonaId() ?: ""
        if (personaId == "") return getString(R.string.label_tap_to_set)
        val label = personaPreferences?.getPersona(personaId)?.label ?: ""
        return if (label != "") label else getString(R.string.label_tap_to_set)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_characters)

        chatId = intent.getStringExtra("chatId") ?: ""
        preferences = Preferences.getPreferences(this, chatId)
        personaPreferences = PersonaPreferences.getPersonaPreferences(this)

        btnBack = findViewById(R.id.btn_back)
        actionBar = findViewById(R.id.action_bar)

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

        initializeTiles()
    }

    private fun initializeTiles() {
        tilePersonas = TileFragment.newInstance(
            checked = false,
            checkable = false,
            enabledText = getString(R.string.tile_personas_title),
            disabledText = null,
            enabledDesc = getActivePersonaLabel(),
            disabledDesc = null,
            icon = R.drawable.ic_user,
            disabled = false,
            chatId = chatId,
            functionDesc = getString(R.string.tile_personas_desc),
            transitionName = "expand_persona_list"
        )

        supportFragmentManager.beginTransaction()
            .replace(R.id.tile_personas_entry, tilePersonas!!)
            .commit()

        tilePersonas?.setOnTileClickListener {
            personasActivityResultLauncher.launch(Intent(this, PersonasListActivity::class.java))
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
