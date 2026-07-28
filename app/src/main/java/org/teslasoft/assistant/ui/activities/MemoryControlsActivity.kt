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
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.theme.ThemeManager

/**
 * "Memory Controls" — the normal user-facing controls page from the Memory
 * Settings reorganization (`Memory System/memory_settings_reorg_spec.md` §1,
 * July 9 2026). Owner-sanctioned wording, used verbatim. It holds the memory
 * defaults, the Memory Assistant entry + suggestion cap, the memory engine, the
 * Memory Assistant model, backups, and the destructive Reset at the bottom.
 * One door leads deeper: "Memory Assistant Advanced Settings" (extraction
 * tuning) inside the Memory Assistant section. "Advanced Memory Settings"
 * (diagnostics/repair) is NOT here — it sits as its own row in the Memory
 * Manager hub, directly under the Memory Controls row (owner ruling, July 9
 * 2026).
 *
 * The user-facing name is "Memory Assistant" — never "Archivist" (the internal
 * `Preferences.getArchivist*` accessors keep the code name).
 */
class MemoryControlsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchDefaultMemory: MaterialSwitch? = null
    private var switchCompanionInRoleplay: MaterialSwitch? = null
    private var switchChatListMemoryStatus: MaterialSwitch? = null

    private var switchCardSuggestions: MaterialSwitch? = null

    private var rowMemoryEngine: LinearLayout? = null
    private var textMemoryEngineValue: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_controls)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        refreshEngineRow()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        switchDefaultMemory = findViewById(R.id.switch_default_memory)
        switchCompanionInRoleplay = findViewById(R.id.switch_companion_in_roleplay)
        switchChatListMemoryStatus = findViewById(R.id.switch_chat_list_memory_status)
        switchCardSuggestions = findViewById(R.id.switch_card_suggestions)
        rowMemoryEngine = findViewById(R.id.row_memory_engine)
        textMemoryEngineValue = findViewById(R.id.text_memory_engine_value)
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

        /* ---- Memory Defaults ---- */
        // Global default for the per-chat memory kill switch (a plain global
        // pref — usable before the store is even provisioned).
        switchDefaultMemory?.isChecked = preferences?.getDefaultMemoryEnabled() ?: true
        switchDefaultMemory?.setOnCheckedChangeListener { _, checked ->
            preferences?.setDefaultMemoryEnabled(checked)
        }

        // "Allow companion memories in roleplay" (owner_approved_rules §3;
        // global, default OFF). Retrieval participation only, never forced.
        switchCompanionInRoleplay?.isChecked = preferences?.getAllowCompanionMemoriesInRoleplay() ?: false
        switchCompanionInRoleplay?.setOnCheckedChangeListener { _, checked ->
            preferences?.setAllowCompanionMemoriesInRoleplay(checked)
        }

        // Display-only: this controls the small review/archive line in the
        // chat list and never changes memory capture or stored memory state.
        switchChatListMemoryStatus?.isChecked =
            preferences?.getShowMemoryStatusOnChatList() ?: true
        switchChatListMemoryStatus?.setOnCheckedChangeListener { _, checked ->
            preferences?.setShowMemoryStatusOnChatList(checked)
        }

        /* ---- Memory Assistant ---- */
        switchCardSuggestions?.isChecked = preferences?.getArchivistCardSuggestions() ?: true
        switchCardSuggestions?.setOnCheckedChangeListener { _, checked ->
            preferences?.setArchivistCardSuggestions(checked)
        }

        /* ---- Memory Engine ---- */
        refreshEngineRow()
        rowMemoryEngine?.setOnClickListener { showMemoryEnginePicker() }
    }

    /* ------------------------------ memory engine ------------------------------ */

    private fun engineLabel(engine: String): String = when (engine) {
        "none" -> getString(R.string.memory_controls_engine_none)
        "full" -> getString(R.string.memory_controls_engine_full)
        else -> getString(R.string.memory_controls_engine_lorebooks)
    }

    private fun refreshEngineRow() {
        textMemoryEngineValue?.text = engineLabel(preferences?.getMemoryEngine() ?: "lorebooks")
    }

    private fun showMemoryEnginePicker() {
        val anchor = textMemoryEngineValue ?: return
        val engines = arrayOf("none", "lorebooks", "full")
        val labels = listOf(
            getString(R.string.memory_controls_engine_none_desc),
            getString(R.string.memory_controls_engine_lorebooks_desc),
            getString(R.string.memory_controls_engine_full_desc)
        )

        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            val picked = engines[position]
            if (picked == "full" && EmbeddingModelStorage.activeModel(this) == null) {
                findViewById<TextView>(R.id.text_engine_needs_model)
                    ?.visibility = View.VISIBLE
                return@setOnItemClickListener
            }
            findViewById<TextView>(R.id.text_engine_needs_model)
                ?.visibility = View.GONE
            preferences?.setMemoryEngine(picked)
            refreshEngineRow()
            if (picked == "full") {
                Thread {
                    try {
                        val created = MemoryCompanionSync.bootstrapFromPersonas(this)
                        org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                            this, "MemorySync", "info",
                            "Tier-2 enable: bootstrap linked $created new companion(s)"
                        )
                    } catch (e: Exception) {
                        org.teslasoft.assistant.preferences.memory.MemoryLog.log(
                            this, "MemorySync", "error", "Tier-2 bootstrap failed: ${e.message}"
                        )
                    }
                }.start()
            }
        }
        popup.show()
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
