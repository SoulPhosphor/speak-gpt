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
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.core.api.network.RequestNetwork

/**
 * Choose Provider screen (OpenRouter). Opened from the Choose Provider row on
 * [ApiEndpointEditorActivity] for an endpoint whose Base URL is an OpenRouter
 * endpoint.
 *
 * Top to bottom: routing type (Automatic / Preferred / Only, default
 * Automatic), model pick (same model the editor's Model box sets), the
 * Preferred-mode extras (fallbacks toggle + ordered provider list), and the
 * provider discovery area — a Filters pull-out and a horizontally scrollable
 * chart of every provider endpoint serving the model, fetched from the
 * endpoint's provider-discovery path when a model is chosen.
 *
 * Chart selection column by mode: Only → radio buttons (exactly one provider);
 * Preferred → checkboxes that append to / remove from the ordered list;
 * Automatic → none. The Ignore column is active in every mode.
 *
 * Saving always makes the chosen model a favorite — the favorite is the box
 * that stores this model's provider-routing memory, and removing the favorite
 * removes it (owner ruling). Two entry points: from the endpoint editor Save
 * returns the choices to the editor, which favorites the model on its own
 * save; from the Favorite AI Models list's routing gear ([EXTRA_PERSIST_DIRECTLY])
 * Save writes the favorite straight to the store. Back / cancel returns nothing.
 */
class ChooseProviderActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ENDPOINT_ID = "endpointId"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ROUTING_TYPE = "routingType"
        const val EXTRA_HOST = "host"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_AUTH_TYPE = "authType"
        const val EXTRA_DISCOVERY_PATH = "discoveryPath"
        const val EXTRA_SELECTED_PROVIDER = "selectedProvider"
        const val EXTRA_ALLOW_FALLBACKS = "allowFallbacks"
        const val EXTRA_PROVIDER_ORDER = "providerOrder"
        const val EXTRA_IGNORED_PROVIDERS = "ignoredProviders"

        /** When true the screen writes the chosen routing straight to the
         *  favorites store on Save (used when opened from the Favorite AI
         *  Models list's routing gear, where there is no endpoint-editor save
         *  to ride on). When absent/false the choices are returned to the
         *  caller, which applies them on its own save. */
        const val EXTRA_PERSIST_DIRECTLY = "persistDirectly"

        /** When true, changing the model on this screen KEEPS the routing type
         *  the user is currently on instead of adopting the newly-chosen model's
         *  stored routing. Set only for the Summoning Circle mode-setup flow,
         *  where the user deliberately picked a routing method and expects it to
         *  carry to whatever model they land on. The Favorite Models routing
         *  gear leaves this false so switching models there still shows each
         *  model's own saved routing. */
        const val EXTRA_KEEP_ROUTING_ON_MODEL_CHANGE = "keepRoutingOnModelChange"

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
    private var fieldRoutingType: TextView? = null
    private var fieldChooseModel: TextView? = null
    private var btnViewAllModels: MaterialButton? = null
    private var rowAllowFallbacks: View? = null
    private var switchAllowFallbacks: MaterialSwitch? = null
    private var sectionPreferredOrder: View? = null
    private var textPreferredOrder: TextView? = null
    private var rowsPreferredOrder: LinearLayout? = null
    private var btnProviderFilters: MaterialButton? = null
    private var textProviderWarning: TextView? = null
    private var textProviderStatus: TextView? = null
    private var chartScroll: View? = null
    private var chartHeader: LinearLayout? = null
    private var chartRows: LinearLayout? = null

    private var endpointId: String = ""
    private var persistDirectly: Boolean = false
    private var keepRoutingOnModelChange: Boolean = false
    private var host: String = ""
    private var apiKey: String = ""
    private var authType: String = ApiEndpointObject.AUTH_BEARER
    /** Provider-discovery path from the endpoint profile's Advanced Options
     *  field; {model} is replaced with the model id at fetch time. */
    private var discoveryPath: String = ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH
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

    /** Lowercase slugs of the providers in a SUCCESSFULLY fetched list. Null
     *  while unloaded or after a failed fetch — a network or parsing failure
     *  means availability is unknown, and nothing may be marked Unavailable. */
    private var availableSlugs: Set<String>? = null

    /** Lowercase provider identifiers of the ZDR endpoints serving the model,
     *  from the separate /endpoints/zdr fetch. Null = list not available →
     *  the ZDR column stays "?"; a loaded list is authoritative (absent = no). */
    private var zdrMatches: Set<String>? = null

    /** slug → display name, from the loaded list (order box shows names). */
    private val displayNames: MutableMap<String, String> = mutableMapOf()

    private var requestNetwork: RequestNetwork? = null
    private var zdrRequestNetwork: RequestNetwork? = null

    private val fetchListener = object : RequestNetwork.RequestListener {
        override fun onResponse(tag: String, message: String) {
            val parsed = ProviderEndpointsParser.parse(message)
            if (parsed == null) {
                showProviderError(message)
                return
            }
            providerEndpoints = parsed.endpoints
            // A saved provider may be marked Unavailable ONLY on a complete,
            // authoritative list. A partial/paginated/truncated/empty result
            // leaves availability unknown: no labels, no warning, every saved
            // selection preserved untouched.
            availableSlugs = if (parsed.authoritative) {
                parsed.endpoints.map { it.slug.lowercase() }.toSet()
            } else {
                null
            }
            // Names of currently served providers; saved-but-absent providers
            // keep whatever name was stored (their slug when none is known).
            parsed.endpoints.forEach { displayNames[it.slug] = it.providerName }
            renderChart()
            updateOrderBox()
            startZdrFetch()
        }

        override fun onErrorResponse(tag: String, message: String) {
            showProviderError(message)
        }
    }

    /** ZDR list arrives after the chart; failure just leaves the column "?". */
    private val zdrFetchListener = object : RequestNetwork.RequestListener {
        override fun onResponse(tag: String, message: String) {
            zdrMatches = ProviderEndpointsParser.parseZdrMatches(message, selectedModel)
            if (providerEndpoints != null) renderChart()
        }

        override fun onErrorResponse(tag: String, message: String) { /* stays unknown */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_choose_provider)

        preferences = Preferences.getPreferences(this, "")
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(this)

        endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID) ?: ""
        persistDirectly = intent.getBooleanExtra(EXTRA_PERSIST_DIRECTLY, false)
        keepRoutingOnModelChange = intent.getBooleanExtra(EXTRA_KEEP_ROUTING_ON_MODEL_CHANGE, false)
        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
        authType = intent.getStringExtra(EXTRA_AUTH_TYPE) ?: ApiEndpointObject.AUTH_BEARER
        discoveryPath = (intent.getStringExtra(EXTRA_DISCOVERY_PATH) ?: "")
            .ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
        selectedModel = intent.getStringExtra(EXTRA_MODEL) ?: ""
        selectedRoutingType = (intent.getStringExtra(EXTRA_ROUTING_TYPE) ?: FavoriteModelObject.ROUTING_AUTOMATIC)
            .takeIf { it in routingTypes } ?: FavoriteModelObject.ROUTING_AUTOMATIC

        // Filters always start from the default view for a fresh screen.
        ProviderFilterState.reset()

        bindViews()
        applyTheme()
        loadValues()
        initLogic()

        // The caller may be opening this screen specifically to set up a mode
        // just picked in the Summoning Circle. Load the saved provider details,
        // but preserve that explicit initial mode. Whether a later model change
        // keeps that mode or adopts the new model's own is decided per entry
        // point by keepRoutingOnModelChange (see onModelChanged).
        seedProviderStateFromFavorite(adoptFavoriteRoutingType = false)
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
        rowAllowFallbacks = findViewById(R.id.row_allow_fallbacks)
        switchAllowFallbacks = findViewById(R.id.switch_allow_fallbacks)
        sectionPreferredOrder = findViewById(R.id.section_preferred_order)
        textPreferredOrder = findViewById(R.id.text_preferred_order)
        rowsPreferredOrder = findViewById(R.id.rows_preferred_order)
        btnProviderFilters = findViewById(R.id.btn_provider_filters)
        textProviderWarning = findViewById(R.id.text_provider_warning)
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
        favorites = endpointFavoriteModels()

        fieldRoutingType?.text = routingLabel(selectedRoutingType)
        updateModelBox()
        updateModeViews()
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { cancelAndFinish() }
        btnSave?.setOnClickListener { saveAndFinish() }

        setupDropdowns()
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

    /**
     * Both selectors use the canonical full-width dropdown. Routing type lists
     * Automatic / Preferred / Only. Choose Model rebuilds its options from this
     * endpoint's favorites whenever it opens; the full catalog remains under
     * View All Models.
     */
    private fun setupDropdowns() {
        val routingLabels = routingTypes.map { routingLabel(it) }
        fieldRoutingType?.setOnClickListener {
            val dropdown = fieldRoutingType ?: return@setOnClickListener
            val current = routingTypes.indexOf(selectedRoutingType).coerceAtLeast(0)
            AppDropdown.show(dropdown, routingLabels, current) { position ->
                selectedRoutingType = routingTypes[position]
                dropdown.text = routingLabel(selectedRoutingType)
                updateModeViews()
                renderChart()
            }
        }

        fieldChooseModel?.setOnClickListener {
            val dropdown = fieldChooseModel ?: return@setOnClickListener
            favorites = endpointFavoriteModels()
            AppDropdown.show(dropdown, favorites, favorites.indexOf(selectedModel)) { position ->
                onModelChanged(favorites[position])
            }
        }
    }

    /** Every valid, distinct favorite belonging to this exact API endpoint. */
    private fun endpointFavoriteModels(): List<String> =
        favoriteModelsPreferences?.getFavoriteModels(endpointId)
            ?.mapNotNull { it["modelId"]?.takeIf { modelId -> modelId.isNotBlank() } }
            ?.distinct()
            ?: emptyList()

    private fun openFullModelPicker() {
        val modelDialog = AdvancedModelSelectorDialogFragment.newAllModelsInstance(
            selectedModel,
            "",
            endpointId
        )
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
        availableSlugs = null
        zdrMatches = null
        // Keep the routing the user is on when they came here to set up a mode
        // (Summoning Circle); otherwise adopt the newly-chosen model's own saved
        // routing (the Favorite Models routing gear).
        seedProviderStateFromFavorite(adoptFavoriteRoutingType = !keepRoutingOnModelChange)
        updateModeViews()
        if (selectedModel.isBlank()) {
            btnProviderFilters?.visibility = View.GONE
            textProviderWarning?.visibility = View.GONE
            textProviderStatus?.visibility = View.GONE
            chartScroll?.visibility = View.GONE
        } else {
            startProviderFetch()
        }
    }

    private fun updateModelBox() {
        fieldChooseModel?.text = selectedModel.ifBlank { getString(R.string.dropdown_select) }
    }

    /** Load the model's saved provider memory (rides on its favorite). A model
     *  with no favorite starts from the defaults. */
    private fun seedProviderStateFromFavorite(adoptFavoriteRoutingType: Boolean) {
        val favorite = if (selectedModel.isBlank()) null
        else favoriteModelsPreferences?.getFavorite(selectedModel, endpointId)

        selectedProvider = favorite?.selectedProvider ?: ""
        orderList.clear()
        orderList.addAll(favorite?.providerOrder ?: emptyList())
        ignored.clear()
        ignored.addAll(favorite?.ignoredProviders ?: emptyList())
        switchAllowFallbacks?.isChecked = favorite?.allowFallbacks ?: true
        if (adoptFavoriteRoutingType) {
            selectedRoutingType = favorite?.routingType
                ?.takeIf { it in routingTypes }
                ?: FavoriteModelObject.ROUTING_AUTOMATIC
            fieldRoutingType?.text = routingLabel(selectedRoutingType)
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

    /**
     * Rebuild the Preferred Provider Order box: the placeholder while empty,
     * otherwise one line per provider ("1. Name") with up/down arrows so the
     * priority can be rearranged directly — no unchecking required.
     */
    private fun updateOrderBox() {
        val rows = rowsPreferredOrder ?: return
        rows.removeAllViews()
        textPreferredOrder?.visibility = if (orderList.isEmpty()) View.VISIBLE else View.GONE

        for ((index, slug) in orderList.withIndex()) {
            val name = displayNames[slug] ?: slug
            val unavailable = isUnavailable(slug)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = dp(6) }
            }
            val label = TextView(this).apply {
                text = if (unavailable) {
                    "${index + 1}. $name (${getString(R.string.provider_unavailable)})"
                } else {
                    "${index + 1}. $name"
                }
                textSize = 14f
                setTextColor(
                    resolveAttrColor(if (unavailable) R.attr.appSubtleTextColor else R.attr.appTextColor)
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            row.addView(orderArrow(name, up = true, enabled = index > 0) { moveInOrder(index, index - 1) })
            row.addView(orderArrow(name, up = false, enabled = index < orderList.size - 1) { moveInOrder(index, index + 1) })
            row.addView(orderRemove(name, index))
            rows.addView(row)
        }
    }

    /** Direct remove X on every order line — no hunting for the chart row.
     *  Removal also updates the chart (checkbox state / Unavailable rows). */
    private fun orderRemove(name: String, index: Int): View {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setImageDrawable(ContextCompat.getDrawable(this@ChooseProviderActivity, R.drawable.ic_close))
            contentDescription = getString(R.string.provider_remove_desc, name)
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (index in orderList.indices) {
                    orderList.removeAt(index)
                    updateOrderBox()
                    renderChart()
                }
            }
        }
    }

    /** Up/down arrow for an order row: the shared chevron glyph, rotated for
     *  up; dimmed and inert at the list's ends. */
    private fun orderArrow(name: String, up: Boolean, enabled: Boolean, onClick: () -> Unit): View {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setImageDrawable(ContextCompat.getDrawable(this@ChooseProviderActivity, R.drawable.ic_chevron_down))
            rotation = if (up) 180f else 0f
            contentDescription = getString(
                if (up) R.string.provider_move_up_desc else R.string.provider_move_down_desc, name
            )
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            if (enabled) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            } else {
                alpha = 0.3f
            }
        }
    }

    private fun moveInOrder(from: Int, to: Int) {
        if (from !in orderList.indices || to !in orderList.indices) return
        val slug = orderList.removeAt(from)
        orderList.add(to, slug)
        updateOrderBox()
    }

    /* ------------------------------ provider fetch ------------------------------ */

    private fun startProviderFetch() {
        if (host.isBlank() || selectedModel.isBlank()) return

        btnProviderFilters?.visibility = View.GONE
        textProviderWarning?.visibility = View.GONE
        chartScroll?.visibility = View.GONE
        textProviderStatus?.text = getString(R.string.provider_status_loading)
        textProviderStatus?.visibility = View.VISIBLE

        // The profile's Provider Discovery Path (Advanced Options), with the
        // model id substituted in.
        val base = host.trimEnd('/')
        val path = discoveryPath.replace("{model}", selectedModel)
        val url = base + path

        requestNetwork = RequestNetwork(this)
        requestNetwork?.setHeaders(authHeaders())
        requestNetwork?.startRequestNetwork("GET", url, "A", fetchListener)
    }

    /** Fetch the Zero Data Retention endpoint list; its records are matched
     *  against the model's providers to fill the ZDR column (owner correction,
     *  Aug 2 2026 — ZDR is not part of the model-endpoints response). */
    private fun startZdrFetch() {
        if (host.isBlank() || zdrMatches != null) return
        zdrRequestNetwork = RequestNetwork(this)
        zdrRequestNetwork?.setHeaders(authHeaders())
        zdrRequestNetwork?.startRequestNetwork(
            "GET", host.trimEnd('/') + "/endpoints/zdr", "A", zdrFetchListener
        )
    }

    private fun authHeaders(): HashMap<String, Any> {
        val authHeaders = HashMap<String, Any>()
        when (authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> authHeaders["x-api-key"] = apiKey
            ApiEndpointObject.AUTH_API_KEY -> authHeaders["api-key"] = apiKey
            else -> authHeaders["Authorization"] = "Bearer $apiKey"
        }
        return authHeaders
    }

    /**
     * Discovery failed (404, unreadable body, network error). Availability is
     * UNKNOWN in this state: [availableSlugs] stays null so no saved provider
     * is marked Unavailable by a failed fetch. The user-facing line stays
     * plain; the actual server response goes to the log for diagnosis.
     */
    private fun showProviderError(responseBody: String?) {
        android.util.Log.w("ProviderDiscovery", "Provider discovery failed for '$selectedModel': " +
            (responseBody ?: "(empty response)").take(2000))
        if (isFinishing) return
        providerEndpoints = null
        availableSlugs = null
        btnProviderFilters?.visibility = View.GONE
        textProviderWarning?.visibility = View.GONE
        chartScroll?.visibility = View.GONE
        textProviderStatus?.text = getString(R.string.provider_status_unavailable)
        textProviderStatus?.visibility = View.VISIBLE
    }

    /* ------------------------------ chart ------------------------------ */

    private fun renderChart() {
        val all = providerEndpoints ?: return

        // Saved providers (Only choice, preferred order, ignore list) absent
        // from the successfully fetched list stay visible as Unavailable rows
        // — never silently deleted. Their controls keep working so they can be
        // unselected or removed. If the provider returns on a later fetch, the
        // label clears automatically and the saved position is preserved.
        val unavailableRefs = referencedSlugs().filter { isUnavailable(it) }
            .sortedBy { (displayNames[it] ?: it).lowercase() }

        if (all.isEmpty() && unavailableRefs.isEmpty()) {
            btnProviderFilters?.visibility = View.GONE
            textProviderWarning?.visibility = View.GONE
            chartScroll?.visibility = View.GONE
            textProviderStatus?.text = getString(R.string.provider_status_empty)
            textProviderStatus?.visibility = View.VISIBLE
            return
        }

        // Overlay the authoritative ZDR list (when loaded) so the column and
        // the ZDR filter both see the real values; the filter state then
        // applies its filters and sorts (alphabetical base order included).
        val displayed = all.map { it.copy(zdr = effectiveZdr(it)) }
        val sorted = ProviderFilterState.apply(displayed)

        textProviderStatus?.visibility = View.GONE
        textProviderWarning?.visibility = if (unavailableRefs.isEmpty()) View.GONE else View.VISIBLE
        btnProviderFilters?.visibility = View.VISIBLE
        chartScroll?.visibility = View.VISIBLE

        buildChartHeader()

        val rows = chartRows ?: return
        rows.removeAllViews()
        for (endpoint in sorted) {
            rows.addView(buildChartRow(endpoint))
        }
        for (slug in unavailableRefs) {
            rows.addView(buildUnavailableRow(slug))
        }
    }

    /** Every provider slug referenced by the model's saved routing settings. */
    private fun referencedSlugs(): List<String> {
        return buildList {
            if (selectedProvider.isNotBlank()) add(selectedProvider)
            addAll(orderList)
            addAll(ignored)
        }.distinct()
    }

    /** True only when a SUCCESSFUL fetch is loaded and [slug] is not in it.
     *  Matching uses the stable slug/tag, never the display name. */
    private fun isUnavailable(slug: String): Boolean {
        val available = availableSlugs ?: return false
        return slug.lowercase() !in available
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

        row.addView(buildIgnoreControl(endpoint.slug, endpoint.providerName))
        return row
    }

    /**
     * Row for a saved provider that a successfully fetched list no longer
     * contains: name, an "Unavailable" label spanning the data columns, and
     * live selection/ignore controls so the reference can still be changed or
     * removed. Never rendered while availability is unknown.
     */
    private fun buildUnavailableRow(slug: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        if (hasLeadColumn()) row.addView(buildLeadControl(slug))

        val name = displayNames[slug] ?: slug
        val nameCell = TextView(this, null, 0, R.style.Widget_App_Chart_Cell)
        nameCell.text = name
        nameCell.layoutParams = LinearLayout.LayoutParams(
            dp(CHART_COLUMNS.first().second), LinearLayout.LayoutParams.WRAP_CONTENT
        )
        row.addView(nameCell)

        // One label across the data columns instead of a row of "?" cells.
        val wideCell = TextView(this, null, 0, R.style.Widget_App_Chart_Cell)
        wideCell.text = getString(R.string.provider_unavailable)
        wideCell.layoutParams = LinearLayout.LayoutParams(
            dp(CHART_COLUMNS.subList(1, CHART_COLUMNS.size - 1).sumOf { it.second }),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        row.addView(wideCell)

        row.addView(buildIgnoreControl(slug, name))
        return row
    }

    /** ZDR for display: the fetched ZDR list is authoritative once loaded
     *  (listed → yes, absent → no); until then whatever the endpoint record
     *  itself carried (usually unknown → "?"). */
    private fun effectiveZdr(endpoint: ProviderEndpointInfo): Boolean? {
        val matches = zdrMatches ?: return endpoint.zdr
        return endpoint.slug.lowercase() in matches ||
            endpoint.providerName.lowercase() in matches
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
     *  onError (white) X when the provider is ignored. Toggles on tap. On an
     *  Unavailable row, un-ignoring drops the row once nothing references it. */
    private fun buildIgnoreControl(slug: String, providerName: String): View {
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(CHART_COLUMNS.last().second), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val square = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL)
            contentDescription = getString(R.string.provider_ignore_desc, providerName)
        }
        val x = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            setImageDrawable(ContextCompat.getDrawable(this@ChooseProviderActivity, R.drawable.ic_ignore_x))
        }
        square.addView(x)
        container.addView(square)

        fun style() {
            val isIgnored = slug in ignored
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
            val wasUnavailableRef = isUnavailable(slug)
            if (slug in ignored) ignored.remove(slug) else ignored.add(slug)
            style()
            // Ignore changes alter which Unavailable rows/warning exist.
            if (wasUnavailableRef || isUnavailable(slug)) renderChart()
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

    /**
     * Save validation (owner plan, Aug 2 2026):
     * - Only mode requires one currently AVAILABLE provider — no selection or
     *   an unavailable selection blocks Save. Only is never silently treated
     *   as Automatic. Save-time validation is NOT sufficient on its own:
     *   request wiring must independently route every outgoing request's
     *   provider preferences through ProviderRoutingEnforcer.decide with the
     *   freshest authoritative discovery data, so stale saved data cannot
     *   bypass these rules.
     * - Preferred mode with every listed provider unavailable blocks Save when
     *   Allow Other Providers if Preferred Fail is off (no permitted provider);
     *   with fallbacks on, saving proceeds and automatic fallback applies.
     * Unavailable slugs stay in the SAVED lists (position preserved for their
     * return) — they are filtered out of the API order/ignore payloads at
     * request time, not here.
     */
    private fun saveAndFinish() {
        if (selectedRoutingType == FavoriteModelObject.ROUTING_ONLY &&
            (selectedProvider.isBlank() || isUnavailable(selectedProvider))
        ) {
            showNoticeDialog(getString(R.string.provider_only_mode_error))
            return
        }

        // Preferred with fallbacks off and no permitted provider: an empty
        // order (nothing selected at all) OR a list whose every provider is
        // confirmed unavailable. Both leave nothing to route and no fallback,
        // so Save is blocked — never silently downgraded to Automatic.
        if (selectedRoutingType == FavoriteModelObject.ROUTING_PREFERRED &&
            switchAllowFallbacks?.isChecked == false &&
            (orderList.isEmpty() ||
                (availableSlugs != null && orderList.all { isUnavailable(it) }))
        ) {
            // Distinct from the Only-mode message: names the exact problem and
            // includes enabling fallbacks as a valid fix (owner wording).
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.provider_no_preferred_title)
                .setMessage(R.string.provider_no_preferred_message)
                .setPositiveButton(R.string.okay) { _, _ -> }
                .show()
            return
        }

        // Saving here always makes the model a favorite — the favorite is the
        // box that stores this model's routing settings (owner ruling). When
        // opened from the routing gear there is no editor save to ride on, so
        // the favorite is written straight to the store; otherwise the choices
        // are handed back to the caller, which favorites the model on its save.
        if (persistDirectly) {
            if (selectedModel.isNotBlank()) {
                favoriteModelsPreferences?.addFavoriteModel(
                    FavoriteModelObject(
                        selectedModel, endpointId, selectedRoutingType,
                        selectedProvider, switchAllowFallbacks?.isChecked != false,
                        ArrayList(orderList), ArrayList(ignored)
                    )
                )
            }
            // Return the model saved here so the caller (the Summoning Circle)
            // can switch the chat to it — the routing rode along on the favorite
            // just written above.
            setResult(RESULT_OK, Intent().putExtra(EXTRA_MODEL, selectedModel))
            finish()
            return
        }

        val data = Intent()
        data.putExtra(EXTRA_MODEL, selectedModel)
        data.putExtra(EXTRA_ROUTING_TYPE, selectedRoutingType)
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

    private fun showNoticeDialog(message: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(message)
            .setPositiveButton(R.string.okay) { _, _ -> }
            .show()
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
