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
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Phase 5 memory-manager admin editor for the single `owner_profile` row
 * (portrait + standing context — the enforcer's standing packet reads both).
 * A one-record form, not a list, so it does NOT subclass
 * [MemoryScreenActivity]; the theme/insets/back-button boilerplate is copied
 * from [org.teslasoft.assistant.ui.activities.MemorySettingsActivity] instead.
 * Load and save both run off the main thread; failures degrade to a toast.
 */
class OwnerProfileActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var fieldPortrait: TextInputEditText? = null
    private var fieldStandingContext: TextInputEditText? = null
    private var btnSave: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_owner_profile)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
        loadProfile()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        fieldPortrait = findViewById(R.id.field_portrait)
        fieldStandingContext = findViewById(R.id.field_standing_context)
        btnSave = findViewById(R.id.btn_save)
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }
        btnSave?.setOnClickListener { save() }
    }

    private fun loadProfile() {
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) return@runOffThread
            val profile = MemoryStore.getInstance(this).getOwnerProfile()
            runOnUiThread {
                if (profile != null) {
                    fieldPortrait?.setText(profile.portrait)
                    fieldStandingContext?.setText(profile.standingContext)
                }
            }
        }
    }

    private fun save() {
        val portrait = fieldPortrait?.text?.toString()?.trim().orEmpty()
        if (portrait.isBlank()) {
            Toast.makeText(this, R.string.mem_admin_owner_portrait_required, Toast.LENGTH_LONG).show()
            return
        }
        val standingContext = fieldStandingContext?.text?.toString()?.trim()
        runOffThread {
            MemoryStore.getInstance(this).upsertOwnerProfile(portrait, standingContext?.ifBlank { null })
            runOnUiThread {
                Toast.makeText(this, R.string.mem_admin_owner_profile_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /** All store work off the main thread, all failures as a toast — never a crash. */
    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /* ------------------------------ theme + insets ------------------------------ */

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val amoled = isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true
        ThemeManager.getThemeManager().applyTheme(this, amoled)

        if (amoled) {
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

    private fun isDarkThemeEnabled(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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

    private fun pxToDp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}
