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
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.marginBottom
import androidx.core.view.marginRight
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SystemPromptsPreferences
import org.teslasoft.assistant.preferences.dto.SystemPromptObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.SystemPromptListItemAdapter

/**
 * The user's library of saved system prompts, styled like the memory manager
 * lists (title-only rows, "Add System Prompt" at the bottom).
 *
 * Two modes, mirroring the persona/activation pattern:
 *  - Manager mode (from AI System Settings): tapping a row opens the full-screen
 *    editor. There is no per-row cog — the owner asked for just the title.
 *  - Pick mode (`pickMode`, from Quick Settings): tapping a row selects that
 *    prompt for use and returns; editing is done from the manager.
 *
 * Selecting or editing keeps the global system message (what the generation
 * funnel reads) mirrored to the effective prompt via [SystemPromptsPreferences],
 * so no generation code changes.
 */
class SystemPromptsListActivity : FragmentActivity() {

    private var btnAdd: ExtendedFloatingActionButton? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var listView: ListView? = null
    private var emptyView: TextView? = null
    private var actionBar: ConstraintLayout? = null

    private var list: ArrayList<SystemPromptObject> = arrayListOf()
    private var adapter: SystemPromptListItemAdapter? = null

    private var systemPromptsPreferences: SystemPromptsPreferences? = null
    private var preferences: Preferences? = null

    private var pickMode: Boolean = false

    private var onSelectListener: SystemPromptListItemAdapter.OnSelectListener =
        object : SystemPromptListItemAdapter.OnSelectListener {
            override fun onClick(position: Int) {
                val item = list.getOrNull(position) ?: return
                if (pickMode) {
                    // Choose this prompt for the chat: record the selection and
                    // mirror its body into the global system message.
                    systemPromptsPreferences?.setSelectedId(item.id)
                    systemPromptsPreferences?.applyEffectiveToGlobal(preferences ?: return)
                    val resultIntent = Intent()
                    resultIntent.putExtra("systemPromptId", item.id)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else {
                    openEditor(item.id)
                }
            }

            override fun onLongClick(position: Int) {
                val item = list.getOrNull(position) ?: return
                openEditor(item.id)
            }
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        setContentView(R.layout.activity_system_prompts_list)

        btnAdd = findViewById(R.id.btn_add)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        listView = findViewById(R.id.list_view)
        emptyView = findViewById(R.id.empty_view)
        actionBar = findViewById(R.id.action_bar)

        preferences = Preferences.getPreferences(this, "")

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
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())

            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }

            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }

        listView?.divider = null

        pickMode = intent.getBooleanExtra("pickMode", false)

        systemPromptsPreferences = SystemPromptsPreferences.getSystemPromptsPreferences(this)
        // Rescue any pre-existing (pre-library) system message so it isn't lost.
        systemPromptsPreferences?.migrateExistingSystemMessage(preferences!!)

        btnBack?.setOnClickListener { finish() }

        btnAdd?.setOnClickListener {
            openEditor("")
        }

        reloadList()
    }

    override fun onResume() {
        super.onResume()
        reloadList()
    }

    private fun openEditor(id: String) {
        val intent = Intent(this, SystemPromptEditorActivity::class.java)
        intent.putExtra("promptId", id)
        startActivity(intent)
    }

    private fun reloadList() {
        list = systemPromptsPreferences?.getSystemPrompts() ?: arrayListOf()

        val selectedId = if (pickMode) {
            (systemPromptsPreferences?.getEffectivePrompt()?.id ?: "")
        } else {
            ""
        }

        runOnUiThread {
            emptyView?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            adapter = SystemPromptListItemAdapter(list, this, pickMode)
            adapter!!.setOnSelectListener(onSelectListener)
            adapter!!.setSelectedId(selectedId)
            listView!!.adapter = adapter
            adapter!!.notifyDataSetChanged()
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

            listView?.setPadding(
                0,
                pxToDp(8),
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )

            val extendedFab = findViewById<ExtendedFloatingActionButton>(R.id.btn_add)
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, extendedFab!!.marginRight, window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + extendedFab.marginBottom)
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            extendedFab.layoutParams = params
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }
}
