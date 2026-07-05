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
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.lorebook.LoreBookInjectionLog
import org.teslasoft.assistant.preferences.memory.enforcer.AssemblyLog
import org.teslasoft.assistant.theme.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1 debug view: shows which memories were injected into recent prompts and
 * why (which trigger matched). Helps confirm the engine is turning over.
 */
class LoreBookDebugActivity : FragmentActivity() {

    private var btnBack: ImageButton? = null
    private var btnClear: MaterialButton? = null
    private var debugText: TextView? = null
    private var actionBar: ConstraintLayout? = null

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        setContentView(R.layout.activity_lorebook_debug)

        btnBack = findViewById(R.id.btn_back)
        btnClear = findViewById(R.id.btn_clear)
        debugText = findViewById(R.id.debug_text)
        actionBar = findViewById(R.id.action_bar)

        val preferences = Preferences.getPreferences(this, "")

        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences.getAmoledPitchBlack())

        if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
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
        btnClear?.setOnClickListener {
            AssemblyLog.clear()
            LoreBookInjectionLog.clear()
            render()
        }

        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    /**
     * Edge-to-edge (SDK 35+) draws this activity under the status and gesture
     * bars; without these insets the back button hides behind the system
     * status area and the clear button behind the navigation bar. Mirrors
     * adjustPaddings() in the sibling lorebook activities.
     */
    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )

            val clearButton = findViewById<MaterialButton>(R.id.btn_clear)
            val params = clearButton?.layoutParams as? ConstraintLayout.LayoutParams
            if (params != null) {
                params.bottomMargin = window.decorView.rootWindowInsets
                    .getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
                clearButton.layoutParams = params
            }
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }

    /**
     * Renders two independent debug logs, oldest section first: the Phase 4
     * enforcer's per-turn assembly (AssemblyLog, only present when the full
     * memory engine is on) above the classic lorebook trigger-match log
     * (LoreBookInjectionLog, always present). Both are process-local and
     * cleared together by btn_clear.
     */
    private fun render() {
        val assemblyRecords = AssemblyLog.getRecords()
        val lorebookRecords = LoreBookInjectionLog.getRecords()

        if (assemblyRecords.isEmpty() && lorebookRecords.isEmpty()) {
            debugText?.text = getString(R.string.lorebook_debug_empty)
            return
        }

        val sb = StringBuilder()

        if (assemblyRecords.isNotEmpty()) {
            sb.append(getString(R.string.memory_debug_assembly_header))
            sb.append('\n')
            for (record in assemblyRecords) {
                appendAssemblyRecord(sb, record)
            }
            sb.append('\n')
        }

        if (lorebookRecords.isNotEmpty()) {
            sb.append(getString(R.string.lorebook_debug_records_header))
            sb.append('\n')
            for (record in lorebookRecords) {
                sb.append(timeFormat.format(Date(record.timestamp)))
                sb.append('\n')
                sb.append("Message: ").append(record.userMessage)
                sb.append('\n')
                // The three failure shapes look identical from the chat, so the
                // search scope is spelled out: unavailable store vs. no active
                // books vs. books searched but no trigger matched.
                when {
                    record.activeBooks < 0 ->
                        sb.append(getString(R.string.lorebook_debug_store_unavailable)).append('\n')
                    record.activeBooks == 0 ->
                        sb.append(getString(R.string.lorebook_debug_no_active_books)).append('\n')
                    else ->
                        sb.append(getString(R.string.lorebook_debug_searched_fmt, record.activeBooks)).append('\n')
                }
                sb.append("Injected ").append(record.matches.size).append(" memory(ies):")
                sb.append('\n')
                for (match in record.matches) {
                    sb.append("  • ").append(match.entry.label)
                    sb.append("  (matched trigger: \"").append(match.matchedTrigger).append("\")")
                    sb.append('\n')
                }
                sb.append("────────────────────")
                sb.append('\n')
            }
        }

        debugText?.text = sb.toString()
    }

    private fun appendAssemblyRecord(sb: StringBuilder, record: AssemblyLog.Record) {
        sb.append(timeFormat.format(Date(record.timestamp)))
        sb.append('\n')
        sb.append("Message: ").append(record.userMessage)
        sb.append('\n')

        if (record.companionName != null) {
            sb.append(getString(R.string.memory_debug_companion_fmt, record.companionName))
            sb.append('\n')
        }

        when (record.packetSource) {
            "compressed" -> sb.append(getString(R.string.memory_debug_packet_compressed)).append('\n')
            "raw" -> sb.append(getString(R.string.memory_debug_packet_raw)).append('\n')
            else -> { /* no packet this turn */ }
        }

        if (record.modes.isNotEmpty()) {
            sb.append(getString(R.string.memory_debug_modes_fmt, record.modes.joinToString(", ")))
            sb.append('\n')
        }

        if (record.injected.isNotEmpty()) {
            sb.append(getString(R.string.memory_debug_injected_header))
            sb.append('\n')
            for (line in record.injected) {
                sb.append("  • ").append(line.label).append(" — ").append(line.detail)
                sb.append('\n')
            }
        }

        if (record.cut.isNotEmpty()) {
            sb.append(getString(R.string.memory_debug_cut_header))
            sb.append('\n')
            for (line in record.cut) {
                sb.append("  • ").append(line.label).append(" — ").append(line.detail)
                sb.append('\n')
            }
        }

        if (record.loreNotes.isNotEmpty()) {
            sb.append(getString(R.string.memory_debug_lore_notes_header))
            sb.append('\n')
            for (note in record.loreNotes) {
                sb.append("  • ").append(note)
                sb.append('\n')
            }
        }

        if (record.scene != null) {
            sb.append(getString(R.string.memory_debug_scene_fmt, record.scene))
            sb.append('\n')
        }

        if (record.notes.isNotEmpty()) {
            sb.append(getString(R.string.memory_debug_notes_header))
            sb.append('\n')
            for (note in record.notes) {
                sb.append("  • ").append(note)
                sb.append('\n')
            }
        }

        sb.append("────────────────────")
        sb.append('\n')
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
