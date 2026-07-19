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
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.chip.ChipGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow
import org.teslasoft.assistant.ui.adapters.memory.MemoryRowAdapter

/**
 * Shared scaffold for every memory-manager list screen (memories, entities,
 * modes, directives, personas, roleplay characters, worlds, campaigns). It owns
 * the identical theme/insets/back-button/search plumbing so each concrete area
 * is just its data + row actions. All store reads run off the main thread
 * (SQLCipher opens aren't free) and every failure surfaces as a toast, never a
 * crash — the same contract as the rest of the memory UI.
 */
abstract class MemoryScreenActivity : FragmentActivity(), MemoryRowAdapter.OnRowListener {

    protected var preferences: Preferences? = null
    protected var chatId: String = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnAction: ImageButton? = null
    private var btnActionSecondary: ImageButton? = null
    private var titleView: TextView? = null
    private var searchBar: TextInputLayout? = null
    private var fieldSearch: TextInputEditText? = null
    private var listView: ListView? = null
    private var emptyView: TextView? = null
    private var btnAdd: ExtendedFloatingActionButton? = null
    private var filterBar: HorizontalScrollView? = null
    private var filterChips: ChipGroup? = null
    private var pendingBanner: com.google.android.material.button.MaterialButton? = null
    private var btnFilter: ImageButton? = null
    private var modeToggleContainer: View? = null
    private var btnModeMemories: TextView? = null
    private var btnModePending: TextView? = null
    private var modePendingCount: TextView? = null

    protected var searchQuery: String = ""

    /* ------------------------------ config hooks ------------------------------ */

    /** The layout to inflate. Default is the shared scaffold
     *  (search/list/FAB plus the button-aware action bar that screens with a
     *  filter/mode-toggle/secondary action need); override only when a
     *  screen never uses that reserved action-button space and should get
     *  the plain Widget.App.ActionBar header instead (see
     *  MemoryUserPersonasActivity + activity_memory_user_personas.xml). Any
     *  override must keep every id this class reads - ids for pieces the
     *  screen doesn't use can simply be absent; findViewById returning null
     *  for those is safe since every hook that touches them is only
     *  reachable when that screen opts in. */
    protected open fun contentLayoutRes(): Int = R.layout.activity_memory_list

    /** The bar title. */
    protected abstract fun screenTitle(): String

    /** Show the search field. Default true. */
    protected open fun showSearch(): Boolean = true

    /** FAB label, or null to hide the "add" button (read-only screens). */
    protected open fun addButtonText(): String? = null

    /** Bar action icon (e.g. a filter or teardown), or null to hide it. */
    protected open fun actionIcon(): Int? = null

    /** Optional second bar action, left of the primary one (e.g. the browser's
     *  Companions link), or null to hide it. */
    protected open fun secondaryActionIcon(): Int? = null

    /** Accessible label / tooltip for the secondary action, or null. */
    protected open fun secondaryActionLabel(): String? = null

    /** Load the rows for [query] — runs on a worker thread. */
    protected abstract fun loadRows(query: String): List<MemoryRow>

    /** Show the filter/sort chip row (legacy Stage 2.3). Replaced by the
     *  slide-out Memory Filters panel; kept for other list screens that may
     *  still want a chip bar. The browser no longer opts in. */
    protected open fun showFilterBar(): Boolean = false

    /** Show the three-dots (vertical) filter button on the right of the search
     *  bar. Only the memory browser opts in today; when pressed it delegates
     *  to [onFilterButtonClick]. */
    protected open fun showFilterButton(): Boolean = false

    /** Called when the three-dots filter button is tapped. Default no-op so
     *  screens without a filter panel don't need to override anything. */
    protected open fun onFilterButtonClick() {}

    /** Populate the filter chip row — called once the bar is shown, and again
     *  by the screen after a filter changes. */
    protected open fun renderFilterBar() {}

    /** The filter chip row, for screens that populate it. */
    protected fun filterChipGroup(): ChipGroup? = filterChips

    /** Called on the UI thread after each list load, so a screen can refresh
     *  chrome that depends on the data (e.g. the browser's Pending banner). */
    protected open fun onRowsRendered() {}

    /** Opt into the centered "Memories | Pending" two-mode header (owner
     *  design, July 8 2026 evening). Only the memory browser uses it. */
    protected open fun showModeToggle(): Boolean = false

    /** Called when the user taps a mode word: "memories" or "pending". */
    protected open fun onModeSelected(mode: String) {}

    /** Refresh the toggle's selected word and the count line beneath it
     *  (null hides the count — it never shows when nothing is pending). */
    protected fun updateModeToggle(mode: String, pendingCountLine: String?) {
        // colorPrimary via appcompat's R — material inherits the attr rather
        // than declaring it (colorError failed to resolve from material.R in
        // CI; colorPrimary has the same risk).
        val selected = com.google.android.material.color.MaterialColors.getColor(
            titleView ?: return, androidx.appcompat.R.attr.colorPrimary
        )
        val unselected = ResourcesCompat.getColor(resources, R.color.text_subtitle, theme)
        btnModeMemories?.setTextColor(if (mode == "memories") selected else unselected)
        btnModePending?.setTextColor(if (mode == "pending") selected else unselected)
        if (pendingCountLine == null) {
            modePendingCount?.visibility = View.GONE
        } else {
            modePendingCount?.visibility = View.VISIBLE
            modePendingCount?.text = pendingCountLine
        }
    }

    /** Show or hide the pinned Pending-memories banner (Stage 2.4). */
    protected fun setPendingBanner(text: String?, onClick: (() -> Unit)?) {
        if (text == null) {
            pendingBanner?.visibility = View.GONE
        } else {
            pendingBanner?.visibility = View.VISIBLE
            pendingBanner?.text = text
            pendingBanner?.setOnClickListener { onClick?.invoke() }
        }
    }

    /* ------------------------------ event hooks ------------------------------ */

    protected open fun onAddClick() {}
    protected open fun onActionClick() {}
    protected open fun onSecondaryActionClick() {}

    /* ------------------------------ lifecycle ------------------------------ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(contentLayoutRes())

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnAction = findViewById(R.id.btn_action)
        btnActionSecondary = findViewById(R.id.btn_action_secondary)
        titleView = findViewById(R.id.activity_title)
        searchBar = findViewById(R.id.search_bar)
        fieldSearch = findViewById(R.id.field_search)
        listView = findViewById(R.id.list_view)
        emptyView = findViewById(R.id.empty_view)
        btnAdd = findViewById(R.id.btn_add)
        filterBar = findViewById(R.id.filter_bar)
        filterChips = findViewById(R.id.chips_filter)
        pendingBanner = findViewById(R.id.pending_banner)
        btnFilter = findViewById(R.id.btn_filter)
        modeToggleContainer = findViewById(R.id.mode_toggle_container)
        btnModeMemories = findViewById(R.id.btn_mode_memories)
        btnModePending = findViewById(R.id.btn_mode_pending)
        modePendingCount = findViewById(R.id.mode_pending_count)

        if (showModeToggle()) {
            modeToggleContainer?.visibility = View.VISIBLE
            btnModeMemories?.setOnClickListener { onModeSelected("memories") }
            btnModePending?.setOnClickListener { onModeSelected("pending") }
        }

        titleView?.text = screenTitle()

        applyTheme()

        btnBack?.setOnClickListener { finish() }

        if (showSearch()) {
            fieldSearch?.doAfterTextChanged {
                searchQuery = it?.toString() ?: ""
                reload()
            }
        } else {
            searchBar?.visibility = View.GONE
        }

        val addText = addButtonText()
        if (addText != null) {
            btnAdd?.visibility = View.VISIBLE
            btnAdd?.text = addText
            btnAdd?.setOnClickListener { onAddClick() }
        }

        val icon = actionIcon()
        if (icon != null) {
            btnAction?.visibility = View.VISIBLE
            btnAction?.setImageResource(icon)
            btnAction?.setOnClickListener { onActionClick() }
        }

        val secondaryIcon = secondaryActionIcon()
        if (secondaryIcon != null) {
            btnActionSecondary?.visibility = View.VISIBLE
            btnActionSecondary?.setImageResource(secondaryIcon)
            secondaryActionLabel()?.let {
                btnActionSecondary?.contentDescription = it
                btnActionSecondary?.tooltipText = it
            }
            btnActionSecondary?.setOnClickListener { onSecondaryActionClick() }
        }

        if (showFilterBar()) {
            filterBar?.visibility = View.VISIBLE
            renderFilterBar()
        } else {
            filterBar?.visibility = View.GONE
        }

        if (showFilterButton()) {
            btnFilter?.visibility = View.VISIBLE
            btnFilter?.setOnClickListener { onFilterButtonClick() }
        } else {
            btnFilter?.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Reload the list off-thread, then swap the adapter on the UI thread. */
    protected fun reload() {
        val query = searchQuery
        Thread {
            val rows: List<MemoryRow> = try {
                loadRows(query)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                emptyList()
            }
            runOnUiThread {
                val adapter = MemoryRowAdapter(rows, this)
                adapter.setOnRowListener(this)
                listView?.adapter = adapter
                emptyView?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                onRowsRendered()
            }
        }.start()
    }

    /** Off-thread store work with a uniform toast-on-failure wrapper. */
    protected fun runOffThread(work: () -> Unit) {
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

    /* Default row callbacks: subclasses override what they need. */
    override fun onClick(row: MemoryRow) {}
    override fun onAction(row: MemoryRow, anchor: View) {}

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
            val tint = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = tint
            btnAction?.backgroundTintList = tint
            btnActionSecondary?.backgroundTintList = tint
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            val tint = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = tint
            btnAction?.backgroundTintList = tint
            btnActionSecondary?.backgroundTintList = tint
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
            listView?.setPadding(
                0,
                pxToDp(8),
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}
