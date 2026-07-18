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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.activities.memory.MemoryBrowserActivity

/**
 * "Memory Manager" (owner ruling, July 8 2026): the entry point for the memory
 * system, reached from the Settings-home "Memory Manager" tile. A plain
 * chevron-row hub (house style: rows, not cards) with six doors —
 *
 *   1. Memory Browser   → the single global memories browser.
 *   2. Memory Assistant → a placeholder ("Coming soon") to be designed later.
 *   3. Lorebooks        → the lorebook manager, moved here out of Characters.
 *   4. Memory Controls  → the normal user-facing controls page.
 *   5. Memory Backup & Restore → the Backups + Reset sections, moved here out
 *      of the Memory Controls screen (owner request, July 18 2026). Sits
 *      directly above Advanced Memory Settings (owner request, July 18 2026).
 *   6. Advanced Memory Settings → diagnostics/repair; moved here out of the
 *      Memory Controls screen (owner ruling, July 9 2026).
 *
 * The browser used to sit inside the old "Memory (experimental)" screen; it is
 * now its own top row here, and that screen keeps only the plumbing under
 * "Memory Settings".
 */
class MemoryManagerActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var rowMemoryBrowser: LinearLayout? = null
    private var rowMemoryAssistant: LinearLayout? = null
    private var rowLorebooks: LinearLayout? = null
    private var rowMemorySettings: LinearLayout? = null
    private var rowAdvancedMemory: LinearLayout? = null
    private var rowMemoryBackupRestore: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_manager)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        rowMemoryBrowser = findViewById(R.id.row_memory_browser)
        rowMemoryAssistant = findViewById(R.id.row_memory_assistant)
        rowLorebooks = findViewById(R.id.row_lorebooks)
        rowMemorySettings = findViewById(R.id.row_memory_settings)
        rowAdvancedMemory = findViewById(R.id.row_advanced_memory)
        rowMemoryBackupRestore = findViewById(R.id.row_memory_backup_restore)
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

        rowMemoryBrowser?.setOnClickListener {
            startActivity(Intent(this, MemoryBrowserActivity::class.java).putExtra("chatId", chatId))
        }

        rowMemoryAssistant?.setOnClickListener {
            startActivity(Intent(this, MemoryAssistantActivity::class.java).putExtra("chatId", chatId))
        }

        rowLorebooks?.setOnClickListener {
            startActivity(Intent(this, LoreBooksListActivity::class.java))
        }

        rowMemorySettings?.setOnClickListener {
            startActivity(Intent(this, MemoryControlsActivity::class.java).putExtra("chatId", chatId))
        }

        rowAdvancedMemory?.setOnClickListener {
            startActivity(Intent(this, AdvancedMemorySettingsActivity::class.java).putExtra("chatId", chatId))
        }

        rowMemoryBackupRestore?.setOnClickListener {
            startActivity(Intent(this, MemoryBackupRestoreActivity::class.java).putExtra("chatId", chatId))
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

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }
}
