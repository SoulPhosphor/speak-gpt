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
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.widgets.AppDropdown

/**
 * "Memory Filters" — slide-out panel that replaces the old chip-row (owner
 * ruling, July 8 2026). Five sections: Sort, Scope, Type, Source, Tags —
 * Status left the panel the same evening (owner ruling: the browser's
 * Memories | Pending toggle owns the status split; a Status filter here was
 * a duplicate).
 *
 * Sort and Source are single-select (Source has only two real values — the
 * "if only two options, no multi" rule). Everything else is multi-select:
 * tapping the section opens a checkbox dialog, and each chosen value becomes
 * a chip pill (10dp corners) just below the section with a tiny × to remove.
 *
 * Selections auto-apply — the shared MemoryBrowserFilterState is edited in
 * place, and closing the panel returns to the browser which reloads onResume.
 * No Apply button, no OK/Cancel round-trip.
 *
 * The Tag list is dynamic: the browser passes its currently-known tags as an
 * intent extra so the panel's picker matches what the user can actually see.
 */
class MemoryFilterPanelActivity : FragmentActivity() {

    private var preferences: Preferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnClose: ImageButton? = null
    private var sectionsContainer: LinearLayout? = null

    /** Tags / scopes / types that exist in the browser's current base set —
     *  supplied by the browser via intent extras so the pickers match what the
     *  user can actually see. Scope and Type options that are NOT present here
     *  render as unavailable (greyed, not selectable). */
    private var availableTags: List<String> = emptyList()
    private var availableScopes: Set<String> = emptySet()
    private var availableTypes: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_filter_panel)

        preferences = Preferences.getPreferences(this, "")
        availableTags = intent.getStringArrayExtra(EXTRA_AVAILABLE_TAGS)?.toList() ?: emptyList()
        availableScopes = intent.getStringArrayExtra(EXTRA_AVAILABLE_SCOPES)?.toSet() ?: emptySet()
        availableTypes = intent.getStringArrayExtra(EXTRA_AVAILABLE_TYPES)?.toSet() ?: emptySet()

        actionBar = findViewById(R.id.action_bar)
        btnClose = findViewById(R.id.btn_close)
        sectionsContainer = findViewById(R.id.sections)

        applyTheme()
        btnClose?.setOnClickListener { finish() }

        // Reset Filters: shared destructive button, one third of the screen
        // width (its label centers inside it on its own).
        findViewById<MaterialButton>(R.id.btn_reset_filters)?.apply {
            layoutParams = layoutParams.apply { width = resources.displayMetrics.widthPixels / 3 }
            setOnClickListener {
                MemoryBrowserFilterState.reset()
                buildSections()
            }
        }

        buildSections()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        // Pair with the entry animation from the browser — slide out to the right.
        overridePendingTransition(R.anim.anim_hold, R.anim.slide_out_right)
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
            btnClose?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnClose?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
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
        } catch (_: Exception) { /* unused */ }
    }

    /* ------------------------------ sections ------------------------------ */

    private fun buildSections() {
        val root = sectionsContainer ?: return
        root.removeAllViews()

        // Sort and Source use the canonical shared dropdown style,
        // with Source directly under Sort (owner ruling, Aug 3 2026).
        addDropdownSection(
            root, getString(R.string.mem_filter_sort),
            options = listOf(
                "newest" to getString(R.string.mem_filter_sort_newest),
                "oldest" to getString(R.string.mem_filter_sort_oldest)
            ),
            currentKey = { MemoryBrowserFilterState.sort },
            apply = { MemoryBrowserFilterState.sort = it }
        )

        addDropdownSection(
            root, getString(R.string.mem_filter_source),
            options = listOf(
                "all" to getString(R.string.mem_filter_option_all),
                "hand" to getString(R.string.mem_source_hand),
                "learned" to getString(R.string.mem_source_learned)
            ),
            currentKey = { MemoryBrowserFilterState.source },
            apply = { MemoryBrowserFilterState.source = it }
        )

        // Scope, Type, and Tags are multi-select dropdowns (owner ruling, Aug 3
        // 2026): the value stays "Select", each pick drops into a chip below,
        // and a picked option leaves the list until its chip is removed. Scope
        // and Type additionally grey out options absent from the loaded set.
        addMultiDropdownSection(
            root, getString(R.string.mem_edit_label_scope),
            options = SCOPE_KEYS.map { it to scopeLabel(it) },
            available = availableScopes,
            selection = MemoryBrowserFilterState.scope
        )

        addMultiDropdownSection(
            root, getString(R.string.mem_edit_label_type),
            options = TYPE_KEYS.map { it to typeLabel(it) },
            available = availableTypes,
            selection = MemoryBrowserFilterState.type
        )

        // Superseded Memories: single-value dropdown directly beneath Type
        // (owner ruling, Aug 3 2026). Hide is the default.
        addDropdownSection(
            root, getString(R.string.mem_filter_superseded),
            options = listOf(
                "hide" to getString(R.string.mem_filter_superseded_hide),
                "include" to getString(R.string.mem_filter_superseded_include),
                "only" to getString(R.string.mem_filter_superseded_only)
            ),
            currentKey = { MemoryBrowserFilterState.superseded },
            apply = { MemoryBrowserFilterState.superseded = it }
        )

        // The Status section is REMOVED (owner ruling, July 8 2026 evening):
        // the browser's Memories | Pending toggle owns the status split, so a
        // Status filter here was a duplicate. FilterState.status remains as
        // the entry-point plumbing the toggle reads.

        // Tags are already only the tags that exist in the loaded set, so every
        // listed option is available.
        addMultiDropdownSection(
            root, getString(R.string.mem_filter_tags),
            options = availableTags.map { it to it },
            available = availableTags.toSet(),
            selection = MemoryBrowserFilterState.tags
        )
    }

    /**
     * A single-value canonical dropdown row: label on the left, control filling
     * the rest of the same line. Tapping the value opens AppDropdown.
     */
    private fun addDropdownSection(
        root: LinearLayout,
        label: String,
        options: List<Pair<String, String>>,
        currentKey: () -> String,
        apply: (String) -> Unit
    ) {
        val row = layoutInflater.inflate(R.layout.view_memory_filter_dropdown, root, false)
        val labelView = row.findViewById<TextView>(R.id.label)
        val valueView = row.findViewById<TextView>(R.id.value)

        labelView.text = label
        valueView.text = options.firstOrNull { it.first == currentKey() }?.second ?: currentKey()

        valueView.setOnClickListener {
            val labels = options.map { it.second }
            val selectedIndex = options.indexOfFirst { it.first == currentKey() }
            AppDropdown.show(valueView, labels, selectedIndex) { index ->
                apply(options[index].first)
                valueView.text = options[index].second
            }
        }

        root.addView(row)
    }

    /**
     * A multi-select dropdown section in the canonical dropdown style.
     * The value stays "Select" (it is the add-another control); each pick drops
     * into a chip below and leaves the dropdown list until its chip's × returns
     * it. Options absent from [available] use the shared disabled treatment and
     * cannot be picked (owner ruling, Aug 3 2026).
     */
    private fun addMultiDropdownSection(
        root: LinearLayout,
        label: String,
        options: List<Pair<String, String>>,
        available: Set<String>,
        selection: MutableSet<String>
    ) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val row = layoutInflater.inflate(R.layout.view_memory_filter_dropdown, section, false)
        row.findViewById<TextView>(R.id.label).text = label
        // The value never changes — it is the "add another" affordance; the
        // chips below carry the current selection (owner ruling, Aug 3 2026).
        row.findViewById<TextView>(R.id.value).text = getString(R.string.mem_dropdown_select)
        val valueView = row.findViewById<TextView>(R.id.value)

        val pills = ChipGroup(this).apply {
            chipSpacingHorizontal = dp(6)
            chipSpacingVertical = dp(4)
            isSingleLine = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); leftMargin = dp(8) }
        }

        fun rebuildPills() {
            pills.removeAllViews()
            for (key in selection) {
                val chipLabel = options.firstOrNull { it.first == key }?.second ?: key
                val chip = Chip(this).apply {
                    text = chipLabel
                    isCloseIconVisible = true
                    setChipBackgroundColorResource(R.color.accent_100)
                    chipCornerRadius = dp(10).toFloat()
                    setOnCloseIconClickListener {
                        // Removing a chip returns its option to the dropdown list.
                        selection.remove(key)
                        rebuildPills()
                    }
                }
                pills.addView(chip)
            }
            pills.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
        }

        valueView.setOnClickListener {
            // Only offer what is not already chosen; picking removes it from the
            // list until the chip is closed.
            val remaining = options.filter { it.first !in selection }
            if (remaining.isEmpty()) return@setOnClickListener
            AppDropdown.show(
                anchor = valueView,
                labels = remaining.map { it.second },
                selectedIndex = -1,
                isOptionEnabled = { index -> remaining[index].first in available }
            ) { index ->
                selection.add(remaining[index].first)
                rebuildPills()
            }
        }

        rebuildPills()
        section.addView(row)
        section.addView(pills)
        root.addView(section)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* ------------------------------ label maps ------------------------------ */

    private fun scopeLabel(key: String): String = getString(when (key) {
        "global" -> R.string.mem_scope_global
        "real_life" -> R.string.mem_scope_real_life
        "companion" -> R.string.mem_scope_companion
        "project" -> R.string.mem_scope_project
        "world" -> R.string.mem_scope_world
        "campaign" -> R.string.mem_scope_campaign
        else -> R.string.mem_scope_rp_character
    })

    private fun typeLabel(key: String): String = getString(when (key) {
        "fact" -> R.string.mem_type_fact
        "preference" -> R.string.mem_type_preference
        "event" -> R.string.mem_type_event
        "status" -> R.string.mem_type_status
        "instruction" -> R.string.mem_type_instruction
        else -> R.string.mem_type_lore
    })

    companion object {
        const val EXTRA_AVAILABLE_TAGS = "availableTags"
        const val EXTRA_AVAILABLE_SCOPES = "availableScopes"
        const val EXTRA_AVAILABLE_TYPES = "availableTypes"

        private val SCOPE_KEYS = listOf(
            "global", "real_life", "companion", "project", "world", "campaign", "rp_character"
        )
        private val TYPE_KEYS = listOf(
            "fact", "preference", "event", "status", "instruction", "lore"
        )
    }
}
