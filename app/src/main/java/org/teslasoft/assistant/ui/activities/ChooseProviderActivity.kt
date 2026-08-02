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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.ProviderEndpointInfo
import org.teslasoft.assistant.providers.ProviderEndpointsParser
import org.teslasoft.assistant.providers.ProviderFilterState
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.core.api.network.RequestNetwork

/**
 * Choose Provider screen (OpenRouter). Opened from the Choose Provider row on
 * [ApiEndpointEditorActivity] for an endpoint whose Base URL is an OpenRouter
 * endpoint.
 *
 * Top to bottom: routing type (Automatic / Preferred / Only, default
 * Automatic), model pick (same model the editor's Model box sets), the Make
 * Favorite toggle, the Preferred-mode extras (fallbacks toggle + ordered
 * provider list), and the provider discovery area — a Filters pull-out and a
 * horizontally scrollable chart of every provider endpoint serving the model,
 * fetched from the endpoint's provider-discovery path when a model is chosen.
 *
 * Chart selection column by mode: Only → radio buttons (exactly one provider);
 * Preferred → checkboxes that append to / remove from the ordered list;
 * Automatic → none. The Ignore column is active in every mode.
 *
 * Nothing writes to disk here. Save returns the choices to the editor, which
 * applies them to the favorites store when the endpoint profile is saved —
 * favoriting is what makes the provider memory permanent, and removing the
 * favorite removes it (owner ruling). Back / cancel returns nothing.
 */
class ChooseProviderActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ENDPOINT_ID = "endpointId"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ROUTING_TYPE = "routingType"
        const val EXTRA_MAKE_FAVORITE = "makeFavorite"
        const val EXTRA_HOST = "host"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_AUTH_TYPE = "authType"
        const val EXTRA_SELECTED_PROVIDER = "selectedProvider"
        const val EXTRA_ALLOW_FALLBACKS = "allowFallbacks"
        const val EXTRA_PROVIDER_ORDER = "providerOrder"
        const val EXTRA_IGNORED_PROVIDERS = "ignoredProviders"

        private val routingTypes = arrayOf(
            FavoriteModelObject.ROUTING_AUTOMATIC,
            FavoriteModelObject.ROUTING_PREFERRED,
            FavoriteModelObject.ROUTING_ONLY
        )

        private const val UNKNOWN = "?"

        /** Chart columns: header label + fixed cell width (dp). One place so
         *  the header and every row stay aligned. */
        private val CHART_COLUMNS = listOf(
            R.string.provider_col_provider to 120,
            R.string.provider_col_quant to 56,
            R.string.provider_col_price to 84,
            R.string.provider_col_cache to 60,
            R.string.provider_col_latency to 64,
            R.string.provider_col_uptime to 60,
            R.string.provider_col_tools to 48,
            R.string.provider_col_zdr to 44,
            R.string.provider_col_caching to 64,
            R.string.provider_col_ignore to 52
        )
        private const val LEAD_CONTROL_WIDTH_DP = 40
    }

    private var preferences: Preferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var fieldRoutingType: TextInputEditText? = null
    private var fieldChooseModel: TextInputEditText? = null
    private var btnViewAllModels: MaterialButton? = null
    private var switchMakeFavorite: MaterialSwitch? = null
    private var rowAllowFallbacks: View? = null
    private var switchAllowFallbacks: MaterialSwitch? = null
    private var sectionPreferredOrder: View? = null
    private var textPreferredOrder: TextView? = null
    private var btnProviderFilters: MaterialButton? = null
    private var textProviderStatus: TextView? = null
    private var chartScroll: View? = null
    private var chartHeader: LinearLayout? = null
    private var chartRows: LinearLayout? = null

    private var endpointId: String = ""
    private var host: String = ""
    private var apiKey: String = ""
    private var authType: String = ApiEndpointObject.AUTH_BEARER
    private var selectedModel: String = ""
    private var selectedRoutingType: String = FavoriteModelObject.ROUTING_AUTOMATIC

    /** Only mode: the single chosen provider slug ("" = none yet). */
    private var selectedProvider: String = ""

    /** Preferred mode: provider slugs in click order (first = most preferred). */
    private val orderList: MutableList<String> = mutableListOf()

    /** Slugs marked Ignore. Active in every mode. */
    private val ignored: MutableSet<String> = mutableSetOf()

    /** This endpoint's favorite model ids, for the Choose Model quick-pick. */
    private var favorites: List<String> = emptyList()

    /** The fetched provider endpoints for [selectedModel]; null = not loaded. */
    private var providerEndpoints: List<ProviderEndpointInfo>? = null

    /** slug → display name, from the loaded list (order box shows names). */
    private val displayNames: MutableMap<String, String> = mutableMapOf()

    private var requestNetwork: RequestNetwork? = null

    private val fetchListener = object : RequestNetwork.RequestListener {
        override fun onResponse(tag: String, message: String) {
            val parsed = ProviderEndpointsParser.parse(message)
            if (parsed == null) {
                showProviderError(message)
                return
            }
            providerEndpoints = parsed
            displayNames.clear()
            parsed.forEach { displayNames[it.slug] = it.providerName }
            renderChart()
        }

        override fun onErrorResponse(tag: String, message: String) {
            showProviderError(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_choose_provider)

        preferences = Preferences.getPreferences(this, "")
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(this)

        endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID) ?: ""
        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
        authType = intent.getStringExtra(EXTRA_AUTH_TYPE) ?: ApiEndpointObject.AUTH_BEARER
        selectedModel = intent.getStringExtra(EXTRA_MODEL) ?: ""
        selectedRoutingType = (intent.getStringExtra(EXTRA_ROUTING_TYPE) ?: FavoriteModelObject.ROUTING_AUTOMATIC)
            .takeIf { it in routingTypes } ?: FavoriteModelObject.ROUTING_AUTOMATIC

        // Filters always start from the default view for a fresh screen.
        ProviderFilterState.reset()

        bindViews()
        applyTheme()
        loadValues()
        initLogic()

        seedProviderStateFromFavorite()
        if (selectedModel.isNotBlank()) startProviderFetch()
    }

    override fun onResume() {
        super.onResume()
        // The Filters panel auto-applies by editing ProviderFilterState in
        // place; re-render on return so the chart reflects it.
        if (providerEndpoints != null) renderChart()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        fieldRoutingType = findViewById(R.id.field_routing_type)
        fieldChooseModel = findViewById(R.id.field_choose_model)
        btnViewAllModels = findViewById(R.id.btn_view_all_models)
        switchMakeFavorite = findViewById(R.id.switch_make_favorite)
        rowAllowFallbacks = findViewById(R.id.row_allow_fallbacks)
        switchAllowFallbacks = findViewById(R.id.switch_allow_fallbacks)
        sectionPreferredOrder = findViewById(R.id.section_preferred_order)
        textPreferredOrder = findViewById(R.id.text_preferred_order)
        btnProviderFilters = findViewById(R.id.btn_provider_filters)
        textProviderStatus = findViewById(R.id.text_provider_status)
        chartScroll = findViewById(R.id.chart_scroll)
        chartHeader = findViewById(R.id.chart_header)
        chartRows = findViewById(R.id.chart_rows)
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
            val amoledTint = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = amoledTint
            btnSave?.backgroundTintList = amoledTint
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            val barTint = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = barTint
            btnSave?.backgroundTintList = barTint
        }
    }

    private fun loadValues() {
        favorites = favoriteModelsPreferences?.getFavoriteModels(endpointId)
            ?.mapNotNull { it["modelId"] }
            ?: emptyList()

        fieldRoutingType?.setText(routingLabel(selectedRoutingType))
        updateModelBox()
        updateModeViews()
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { cancelAndFinish() }
        btnSave?.setOnClickListener { saveAndFinish() }

        fieldRoutingType?.setOnClickListener { showRoutingChooser() }
        fieldChooseModel?.setOnClickListener { showModelChooser() }
        btnViewAllModels?.setOnClickListener { openFullModelPicker() }
        btnProviderFilters?.setOnClickListener { openFilterPanel() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancelAndFinish() }
        })
    }

    /* ------------------------------ routing + model ------------------------------ */

    private fun routingLabel(type: String): String = when (type) {
        FavoriteModelObject.ROUTING_PREFERRED -> getString(R.string.choose_provider_routing_preferred)
        FavoriteModelObject.ROUTING_ONLY -> getString(R.string.choose_provider_routing_only)
        else -> getString(R.string.choose_provider_routing_automatic)
    }

    private fun showRoutingChooser() {
        val labels = routingTypes.map { routingLabel(it) }.toTypedArray()
        val current = routingTypes.indexOf(selectedRoutingType).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.choose_provider_routing_picker_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                selectedRoutingType = routingTypes[which]
                fieldRoutingType?.setText(routingLabel(selectedRoutingType))
                updateModeViews()
                renderChart()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /**
     * Quick-pick from this endpoint's favorites. The first entry always clears
     * the selection ("Use None"); the rest are the favorites. When the endpoint
     * has no favorites yet the box reads "None Available" and this chooser only
     * offers "Use None" — the full catalog is reached through View All Models.
     */
    private fun showModelChooser() {
        val labels = (listOf(getString(R.string.choose_provider_model_use_none)) + favorites).toTypedArray()
        val current = if (selectedModel.isBlank()) 0 else favorites.indexOf(selectedModel).let { if (it >= 0) it + 1 else -1 }

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.choose_provider_choose_model_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                onModelChanged(if (which == 0) "" else favorites[which - 1])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun openFullModelPicker() {
        val modelDialog = AdvancedModelSelectorDialogFragment.newInstance(selectedModel, "", endpointId)
        modelDialog.setModelSelectedListener { model -> onModelChanged(model) }
        modelDialog.show(supportFragmentManager, "ChooseProviderModelSelector")
    }

    /** New model chosen (quick-pick or full picker): reload that model's saved
     *  provider memory and refetch its provider list. */
    private fun onModelChanged(model: String) {
        if (model == selectedModel) return
        selectedModel = model
        updateModelBox()
        providerEndpoints = null
        seedProviderStateFromFavorite()
        updateModeViews()
        if (selectedModel.isBlank()) {
            btnProviderFilters?.visibility = View.GONE
            textProviderStatus?.visibility = View.GONE
            chartScroll?.visibility = View.GONE
        } else {
            startProviderFetch()
        }
    }

    private fun updateModelBox() {
        val text = when {
            selectedModel.isNotBlank() -> selectedModel
            favorites.isEmpty() -> getString(R.string.choose_provider_model_none_available)
            else -> getString(R.string.choose_provider_model_use_none)
        }
        fieldChooseModel?.setText(text)
    }

    /** Load the model's saved provider memory (rides on its favorite). A model
     *  with no favorite starts from the defaults. */
    private fun seedProviderStateFromFavorite() {
        val favorite = if (selectedModel.isBlank()) null
        else favoriteModelsPreferences?.getFavorite(selectedModel, endpointId)

        selectedProvider = favorite?.selectedProvider ?: ""
        orderList.clear()
        orderList.addAll(favorite?.providerOrder ?: emptyList())
        ignored.clear()
        ignored.addAll(favorite?.ignoredProviders ?: emptyList())
        switchAllowFallbacks?.isChecked = favorite?.allowFallbacks ?: true
        if (favorite != null && favorite.routingType in routingTypes) {
            selectedRoutingType = favorite.routingType
            fieldRoutingType?.setText(routingLabel(selectedRoutingType))
        }
        updateOrderBox()
    }

    /* ------------------------------ mode views ------------------------------ */

    private fun updateModeViews() {
        val preferred = selectedRoutingType == FavoriteModelObject.ROUTING_PREFERRED
        rowAllowFallbacks?.visibility = if (preferred) View.VISIBLE else View.GONE
        sectionPreferredOrder?.visibility = if (preferred) View.VISIBLE else View.GONE
        if (preferred) updateOrderBox()
    }

    private fun updateOrderBox() {
        val box = textPreferredOrder ?: return
        if (orderList.isEmpty()) {
            box.text = getString(R.string.provider_preferred_order_placeholder)
            box.setTextColor(resolveAttrColor(R.attr.appSubtleTextColor))
        } else {
            box.text = orderList.mapIndexed { index, slug ->
                "${index + 1}. ${displayNames[slug] ?: slug}"
            }.joinToString("\n")
            box.setTextColor(resolveAttrColor(R.attr.appTextColor))
        }
    }

    /* ------------------------------ provider fetch ------------------------------ */

    private fun startProviderFetch() {
        if (host.isBlank() || selectedModel.isBlank()) return

        btnProviderFilters?.visibility = View.GONE
        chartScroll?.visibility = View.GONE
        textProviderStatus?.text = getString(R.string.provider_status_loading)
        textProviderStatus?.visibility = View.VISIBLE

        val base = host.trimEnd('/')
        val path = ProviderEndpointsParser.DEFAULT_DISCOVERY_PATH.replace("{model}", selectedModel)
        val url = base + path

        val authHeaders = HashMap<String, Any>()
        when (authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> authHeaders["x-api-key"] = apiKey
            ApiEndpointObject.AUTH_API_KEY -> authHeaders["api-key"] = apiKey
            else -> authHeaders["Authorization"] = "Bearer $apiKey"
        }

        requestNetwork = RequestNetwork(this)
        requestNetwork?.setHeaders(authHeaders)
        requestNetwork?.startRequestNetwork("GET", url, "A", fetchListener)
    }

    /** Honest failure line: what failed plus what the server actually said. */
    private fun showProviderError(responseBody: String?) {
        if (isFinishing) return
        providerEndpoints = null
        btnProviderFilters?.visibility = View.GONE
        chartScroll?.visibility = View.GONE
        val excerpt = (responseBody ?: "").take(300).trim()
        textProviderStatus?.text = getString(R.string.provider_status_error) +
            "\n" + (excerpt.ifBlank { "(empty response)" })
        textProviderStatus?.visibility = View.VISIBLE
    }

    /* ------------------------------ chart ------------------------------ */

    private fun renderChart() {
        val all = providerEndpoints ?: return

        if (all.isEmpty()) {
            btnProviderFilters?.visibility = View.GONE
            chartScroll?.visibility = View.GONE
            textProviderStatus?.text = getString(R.string.provider_status_empty)
            textProviderStatus?.visibility = View.VISIBLE
            return
        }

        // Alphabetical default; the filter state adds its sorts/filters on top.
        val sorted = ProviderFilterState.apply(all.sortedBy { it.providerName.lowercase() })

        textProviderStatus?.visibility = View.GONE
        btnProviderFilters?.visibility = View.VISIBLE
        chartScroll?.visibility = View.VISIBLE

        buildChartHeader()

        val rows = chartRows ?: return
        rows.removeAllViews()
        for (endpoint in sorted) {
            rows.addView(buildChartRow(endpoint))
        }
    }

    private fun buildChartHeader() {
        val header = chartHeader ?: return
        header.removeAllViews()
        if (hasLeadColumn()) header.addView(spacerCell())
        for ((labelRes, widthDp) in CHART_COLUMNS) {
            val cell = TextView(this, null, 0, R.style.Widget_App_Chart_HeaderCell)
            cell.text = getString(labelRes)
            cell.layoutParams = LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
            header.addView(cell)
        }
    }

    private fun buildChartRow(endpoint: ProviderEndpointInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) } // the owner's empty line between providers
        }

        if (hasLeadColumn()) row.addView(buildLeadControl(endpoint.slug))

        val values = listOf(
            endpoint.providerName,
            endpoint.quantization ?: UNKNOWN,
            "${formatPricePerM(endpoint.promptPrice)}/${formatPricePerM(endpoint.completionPrice)}",
            formatPricePerM(endpoint.cacheReadPrice),
            endpoint.latency?.let { String.format("%.2fs", it) } ?: UNKNOWN,
            endpoint.uptime?.let { String.format("%.1f", it) } ?: UNKNOWN,
            mark(endpoint.supportsTools),
            mark(endpoint.zdr),
            mark(endpoint.supportsCaching)
        )
        for ((index, value) in values.withIndex()) {
            val cell = TextView(this, null, 0, R.style.Widget_App_Chart_Cell)
            cell.text = value
            cell.layoutParams = LinearLayout.LayoutParams(
                dp(CHART_COLUMNS[index].second), LinearLayout.LayoutParams.WRAP_CONTENT
            )
            row.addView(cell)
        }

        row.addView(buildIgnoreControl(endpoint))
        return row
    }

    private fun hasLeadColumn(): Boolean =
        selectedRoutingType != FavoriteModelObject.ROUTING_AUTOMATIC

    private fun spacerCell(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(LEAD_CONTROL_WIDTH_DP), 1)
    }

    /** Only mode: radio (single choice). Preferred mode: checkbox that appends
     *  to / removes from the ordered list. */
    private fun buildLeadControl(slug: String): View {
        val params = LinearLayout.LayoutParams(dp(LEAD_CONTROL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT)
        return if (selectedRoutingType == FavoriteModelObject.ROUTING_ONLY) {
            MaterialRadioButton(this).apply {
                layoutParams = params
                isChecked = slug == selectedProvider
                setOnClickListener {
                    selectedProvider = slug
                    renderChart()
                }
            }
        } else {
            MaterialCheckBox(this).apply {
                layoutParams = params
                isChecked = slug in orderList
                setOnClickListener {
                    if (slug in orderList) orderList.remove(slug) else orderList.add(slug)
                    updateOrderBox()
                    renderChart()
                }
            }
        }
    }

    /** The Ignore square: gray outline + gray X unmarked; error-red fill +
     *  onError (white) X when the provider is ignored. Toggles on tap. */
    private fun buildIgnoreControl(endpoint: ProviderEndpointInfo): View {
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(CHART_COLUMNS.last().second), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val square = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL)
            contentDescription = getString(R.string.provider_ignore_desc, endpoint.providerName)
        }
        val x = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            setImageDrawable(ContextCompat.getDrawable(this@ChooseProviderActivity, R.drawable.ic_ignore_x))
        }
        square.addView(x)
        container.addView(square)

        fun style() {
            val isIgnored = endpoint.slug in ignored
            square.background = ContextCompat.getDrawable(
                this,
                if (isIgnored) R.drawable.bg_ignore_square_on else R.drawable.bg_ignore_square_off
            )
            x.imageTintList = ColorStateList.valueOf(
                resolveAttrColor(
                    if (isIgnored) com.google.android.material.R.attr.colorOnError
                    else R.attr.appSubtleTextColor
                )
            )
        }
        style()

        square.setOnClickListener {
            if (endpoint.slug in ignored) ignored.remove(endpoint.slug) else ignored.add(endpoint.slug)
            style()
        }
        return container
    }

    private fun mark(state: Boolean?): String = when (state) {
        true -> "X"
        false -> ""
        null -> UNKNOWN
    }

    /** Per-token API price → per-million display, trimmed; null → "?". */
    private fun formatPricePerM(perToken: Double?): String {
        perToken ?: return UNKNOWN
        val perM = perToken * 1_000_000
        return when {
            perM >= 100 -> String.format("%.0f", perM)
            perM >= 10 -> String.format("%.1f", perM).trimEnd('0').trimEnd('.')
            else -> String.format("%.2f", perM).trimEnd('0').trimEnd('.')
        }
    }

    /* ------------------------------ filters ------------------------------ */

    @Suppress("DEPRECATION")
    private fun openFilterPanel() {
        val quants = providerEndpoints
            ?.mapNotNull { it.quantization }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
        val intent = Intent(this, ProviderFilterPanelActivity::class.java)
            .putExtra(ProviderFilterPanelActivity.EXTRA_QUANTIZATIONS, quants.toTypedArray())
        startActivity(intent)
        // Pair with the panel's slide-out on close so the transition matches.
        overridePendingTransition(R.anim.slide_in_right, R.anim.anim_hold)
    }

    /* ------------------------------ save / cancel ------------------------------ */

    private fun saveAndFinish() {
        val data = Intent()
        data.putExtra(EXTRA_MODEL, selectedModel)
        data.putExtra(EXTRA_ROUTING_TYPE, selectedRoutingType)
        data.putExtra(EXTRA_MAKE_FAVORITE, switchMakeFavorite?.isChecked == true)
        data.putExtra(EXTRA_SELECTED_PROVIDER, selectedProvider)
        data.putExtra(EXTRA_ALLOW_FALLBACKS, switchAllowFallbacks?.isChecked != false)
        data.putStringArrayListExtra(EXTRA_PROVIDER_ORDER, ArrayList(orderList))
        data.putStringArrayListExtra(EXTRA_IGNORED_PROVIDERS, ArrayList(ignored))
        setResult(RESULT_OK, data)
        finish()
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /* ------------------------------ helpers ------------------------------ */

    private fun resolveAttrColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ResourcesCompat.getColor(resources, value.resourceId, theme)
        } else {
            value.data
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
