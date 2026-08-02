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
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.providers.ProviderFilterState
import org.teslasoft.assistant.providers.SortDirection
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Filters pull-out for the provider chart on the Choose Provider screen.
 * Slides in from the right and auto-applies — every control edits
 * [ProviderFilterState] in place, and the Choose Provider screen re-applies
 * the state when it resumes. No Apply button. Same pull-out mechanism as the
 * Memory Filters panel; the internals use the shared dropdown and check-option
 * styles instead of that panel's local ones.
 *
 * The Quantization dropdown is dynamic: the chart passes the quantizations
 * actually present in the loaded provider list via [EXTRA_QUANTIZATIONS].
 */
class ProviderFilterPanelActivity : FragmentActivity() {

    companion object {
        const val EXTRA_QUANTIZATIONS = "quantizations"
    }

    private var preferences: Preferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnClose: ImageButton? = null

    private var valueAlphabetical: TextView? = null
    private var valueInputPrice: TextView? = null
    private var valueOutputPrice: TextView? = null
    private var valueQuantization: TextView? = null
    private var valueLatency: TextView? = null
    private var valueThroughput: TextView? = null
    private var valueUptime: TextView? = null
    private var checkToolSupport: MaterialCheckBox? = null
    private var checkImplicitCaching: MaterialCheckBox? = null
    private var checkZdr: MaterialCheckBox? = null

    private var quantizations: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_provider_filter_panel)

        preferences = Preferences.getPreferences(this, "")
        quantizations = intent.getStringArrayExtra(EXTRA_QUANTIZATIONS)?.toList() ?: emptyList()

        actionBar = findViewById(R.id.action_bar)
        btnClose = findViewById(R.id.btn_close)
        valueAlphabetical = findViewById(R.id.value_alphabetical)
        valueInputPrice = findViewById(R.id.value_input_price)
        valueOutputPrice = findViewById(R.id.value_output_price)
        valueQuantization = findViewById(R.id.value_quantization)
        valueLatency = findViewById(R.id.value_latency)
        valueThroughput = findViewById(R.id.value_throughput)
        valueUptime = findViewById(R.id.value_uptime)
        checkToolSupport = findViewById(R.id.check_tool_support)
        checkImplicitCaching = findViewById(R.id.check_implicit_caching)
        checkZdr = findViewById(R.id.check_zdr)

        applyTheme()
        btnClose?.setOnClickListener { finish() }

        bindAlphabeticalDropdown()
        bindSortDropdown(valueInputPrice, { ProviderFilterState.sortInputPrice }) { ProviderFilterState.sortInputPrice = it }
        bindSortDropdown(valueOutputPrice, { ProviderFilterState.sortOutputPrice }) { ProviderFilterState.sortOutputPrice = it }
        bindSortDropdown(valueLatency, { ProviderFilterState.sortLatency }) { ProviderFilterState.sortLatency = it }
        bindSortDropdown(valueThroughput, { ProviderFilterState.sortThroughput }) { ProviderFilterState.sortThroughput = it }
        bindSortDropdown(valueUptime, { ProviderFilterState.sortUptime }) { ProviderFilterState.sortUptime = it }
        bindQuantizationDropdown()

        bindCheckRow(R.id.row_tool_support, checkToolSupport, { ProviderFilterState.requireTools }) { ProviderFilterState.requireTools = it }
        bindCheckRow(R.id.row_implicit_caching, checkImplicitCaching, { ProviderFilterState.requireCaching }) { ProviderFilterState.requireCaching = it }
        bindCheckRow(R.id.row_zdr, checkZdr, { ProviderFilterState.requireZdr }) { ProviderFilterState.requireZdr = it }

        findViewById<MaterialButton>(R.id.btn_reset_filters)?.setOnClickListener {
            ProviderFilterState.reset()
            refreshAllValues()
        }

        refreshAllValues()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        // Pair with the chart's slide-in entry: slide out to the right.
        overridePendingTransition(R.anim.anim_hold, R.anim.slide_out_right)
    }

    /* ------------------------------ bindings ------------------------------ */

    private fun sortLabel(direction: SortDirection): String = when (direction) {
        SortDirection.HIGH_TO_LOW -> getString(R.string.provider_filter_sort_high_low)
        SortDirection.LOW_TO_HIGH -> getString(R.string.provider_filter_sort_low_high)
        else -> getString(R.string.provider_filter_sort_none)
    }

    /** Every numeric sort dropdown: None (the default, disables just this
     *  sort), Highest to Lowest, Lowest to Highest. None lets one sort be
     *  turned off without Reset Filters wiping every other choice. */
    private val sortOptions = listOf(
        SortDirection.NONE, SortDirection.HIGH_TO_LOW, SortDirection.LOW_TO_HIGH
    )

    private fun bindSortDropdown(view: TextView?, current: () -> SortDirection, apply: (SortDirection) -> Unit) {
        view?.setOnClickListener { anchor ->
            showDropdown(anchor, sortOptions.map { sortLabel(it) }) { index ->
                apply(sortOptions[index])
                refreshAllValues()
            }
        }
    }

    /** Alphabetical always has a value: A to Z (the default) or Z to A. */
    private fun bindAlphabeticalDropdown() {
        valueAlphabetical?.setOnClickListener { anchor ->
            val labels = listOf(
                getString(R.string.provider_filter_a_to_z),
                getString(R.string.provider_filter_z_to_a)
            )
            showDropdown(anchor, labels) { index ->
                ProviderFilterState.alphaAToZ = index == 0
                refreshAllValues()
            }
        }
    }

    /** "Any" (the default — clears the filter without Reset) followed by the
     *  quantizations actually present in the loaded provider list. Called
     *  "Any", not "None", so it can't read as a provider reporting no
     *  quantization. */
    private fun bindQuantizationDropdown() {
        valueQuantization?.setOnClickListener { anchor ->
            val labels = listOf(getString(R.string.provider_filter_quant_any)) + quantizations
            showDropdown(anchor, labels) { index ->
                ProviderFilterState.quantization = if (index == 0) null else quantizations[index - 1]
                refreshAllValues()
            }
        }
    }

    private fun bindCheckRow(rowId: Int, box: MaterialCheckBox?, current: () -> Boolean, apply: (Boolean) -> Unit) {
        findViewById<View>(rowId)?.setOnClickListener {
            apply(!current())
            box?.isChecked = current()
        }
    }

    private fun showDropdown(anchor: View, labels: List<String>, onPick: (Int) -> Unit) {
        if (isFinishing) return
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onPick(position)
        }
        popup.show()
    }

    private fun refreshAllValues() {
        valueAlphabetical?.text = getString(
            if (ProviderFilterState.alphaAToZ) R.string.provider_filter_a_to_z
            else R.string.provider_filter_z_to_a
        )
        valueInputPrice?.text = sortLabel(ProviderFilterState.sortInputPrice)
        valueOutputPrice?.text = sortLabel(ProviderFilterState.sortOutputPrice)
        valueLatency?.text = sortLabel(ProviderFilterState.sortLatency)
        valueThroughput?.text = sortLabel(ProviderFilterState.sortThroughput)
        valueUptime?.text = sortLabel(ProviderFilterState.sortUptime)
        valueQuantization?.text = ProviderFilterState.quantization
            ?: getString(R.string.provider_filter_quant_any)
        checkToolSupport?.isChecked = ProviderFilterState.requireTools
        checkImplicitCaching?.isChecked = ProviderFilterState.requireCaching
        checkZdr?.isChecked = ProviderFilterState.requireZdr
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
