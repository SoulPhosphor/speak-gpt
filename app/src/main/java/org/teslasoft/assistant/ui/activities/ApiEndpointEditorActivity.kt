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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.includes.ImageCapability
import org.teslasoft.assistant.preferences.includes.ImageCapabilityStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.util.DiscardChangesDialog

/**
 * Full-page editor for a single API chat endpoint profile (replaced the old
 * EditApiEndpointDialogFragment pop-up, July 2026). Reached from
 * ApiEndpointsListActivity.
 *
 * Behaviour the owner asked for:
 * - The upper-left double-chevron back button (and the system back gesture)
 *   is "cancel"; backing out with unsaved edits shows a "Discard changes?"
 *   dialog first.
 * - The header title reads "<profile name> API Endpoint" (or "New API
 *   Endpoint" while adding one), keeping whatever capitalization the user
 *   gave the label. Save and Delete are the disk and trash-can icons at the
 *   header's trailing edge (same chained-icon shape as Edit Companion/Edit
 *   Persona) — Save just saves (no confirm), Delete confirms first with the
 *   house two-button dialog shape (dialog_two_actions.xml).
 * - The API-key field shows a run of stars when a key already exists; tapping
 *   in clears it to an empty cursor. Leaving it untouched (or blank) keeps the
 *   existing key, even on save.
 *
 * Result contract back to the list:
 * - RESULT_OK + extra "deleted"=false + "apiEndpointId" → saved (list marks
 *   that endpoint active and finishes).
 * - RESULT_OK + extra "deleted"=true → deleted (list just reloads).
 * - RESULT_CANCELED → nothing changed.
 */
class ApiEndpointEditorActivity : FragmentActivity() {

    companion object {
        /** Visual mask shown when a key is already stored. Fixed-length so it
         *  never leaks the real key's length. */
        private const val API_KEY_MASK = "********************"

        private val authTypes = arrayOf(
            ApiEndpointObject.AUTH_BEARER,
            ApiEndpointObject.AUTH_X_API_KEY,
            ApiEndpointObject.AUTH_API_KEY
        )
    }

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null

    /** Set once the user opens Choose Provider and returns with Save. Only then
     *  does the endpoint's save apply the favorite/routing choices below. */
    private var chooseProviderVisited: Boolean = false
    private var pendingRoutingType: String = FavoriteModelObject.ROUTING_AUTOMATIC
    private var pendingSelectedProvider: String = ""
    private var pendingAllowFallbacks: Boolean = true
    private var pendingProviderOrder: List<String> = emptyList()
    private var pendingIgnoredProviders: List<String> = emptyList()

    /** Result from the Choose Provider screen: the chosen model becomes the
     *  endpoint's model (same value the Model box sets), and the routing type,
     *  provider choices and favorite flag are applied to the favorites store
     *  when the profile saves. */
    private val chooseProviderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val model = data.getStringExtra(ChooseProviderActivity.EXTRA_MODEL) ?: ""
            pendingRoutingType = data.getStringExtra(ChooseProviderActivity.EXTRA_ROUTING_TYPE)
                ?: FavoriteModelObject.ROUTING_AUTOMATIC
            pendingSelectedProvider = data.getStringExtra(ChooseProviderActivity.EXTRA_SELECTED_PROVIDER) ?: ""
            pendingAllowFallbacks = data.getBooleanExtra(ChooseProviderActivity.EXTRA_ALLOW_FALLBACKS, true)
            pendingProviderOrder = data.getStringArrayListExtra(ChooseProviderActivity.EXTRA_PROVIDER_ORDER) ?: emptyList()
            pendingIgnoredProviders = data.getStringArrayListExtra(ChooseProviderActivity.EXTRA_IGNORED_PROVIDERS) ?: emptyList()
            chooseProviderVisited = true
            if (model != selectedModel) fieldContextWindow?.setText("")
            selectedModel = model
            fieldModel?.setText(model)
        }
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var activityTitle: TextView? = null
    private var btnDelete: ImageButton? = null
    private var btnSave: ImageButton? = null

    private var fieldLabel: TextInputEditText? = null
    private var fieldModel: TextInputEditText? = null
    private var fieldMaxTokens: TextInputEditText? = null
    private var fieldContextWindow: TextInputEditText? = null
    private var fieldTimeout: TextInputEditText? = null
    private var fieldResponseTime: TextInputEditText? = null
    private var fieldEndSeparator: TextInputEditText? = null
    private var fieldPrefix: TextInputEditText? = null
    private var fieldHost: TextInputEditText? = null
    private var labelError: TextView? = null
    private var hostError: TextView? = null
    private var fieldChatEndpoint: TextInputEditText? = null
    private var fieldApiKey: TextInputEditText? = null
    private var fieldAuthType: TextInputEditText? = null

    /** OpenRouter-only rows: the Choose Provider navigation row and the whole
     *  Advanced Options section, shown only while the Base URL is an OpenRouter
     *  endpoint. The discovery-path field inside Advanced Options is a real
     *  persisted profile field used by the Choose Provider screen's fetch. */
    private var rowChooseProvider: View? = null
    private var sectionAdvancedOptions: View? = null
    private var fieldProviderDiscoveryPath: TextInputEditText? = null
    private var sliderTemperature: Slider? = null
    private var sliderTopP: Slider? = null
    private var sliderFrequencyPenalty: Slider? = null
    private var sliderPresencePenalty: Slider? = null

    private var position: Int = -1
    /** Stable id of the profile being edited ("" for a new profile). */
    private var endpointId: String = ""
    private var oldLabel: String = ""
    private var selectedAuthType: String = ApiEndpointObject.AUTH_BEARER
    private var selectedModel: String = ApiEndpointObject.DEFAULT_MODEL

    /** Stored provider value, preserved through the editor even though the
     *  Provider box was removed, so saving never wipes a value the profile
     *  already carries (it is used when routing requests). */
    private var currentProvider: String = ""

    /** The key stored on disk when the screen opened. Preserved unless the user
     *  actually types a new one. */
    private var originalApiKey: String = ""
    private var keyHasValue: Boolean = false
    /** True while the field shows the star mask rather than a real/edited value. */
    private var apiKeyMasked: Boolean = false

    private var imageCapabilityHeader: LinearLayout? = null
    private var imageCapabilityChevron: ImageView? = null
    private var imageCapabilityBody: LinearLayout? = null
    private var imageCapabilityEmpty: TextView? = null
    private var imageCapabilityRows: LinearLayout? = null
    private var btnClearImageCapability: MaterialButton? = null
    private var imageCapabilityExpanded: Boolean = false
    private var currentCapabilityJson: String = ""

    /** Learned tool capability (image-generation-rebuild-plan.md §8);
     *  cleared here so a provider upgrade is never treated as permanent. */
    private var currentToolCapabilityJson: String = ""
    private var btnClearToolCapability: MaterialButton? = null

    /** Snapshot of the initial field values, for the discard-changes check. */
    private var initialSnapshot: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        position = intent.getIntExtra("position", -1)
        endpointId = intent.getStringExtra("id") ?: ""

        // Adding a new profile and editing an existing one share this activity
        // and every view id, but lead with a different field order: a new
        // profile puts the connection details before Model, since choosing a
        // model before the endpoint does not make sense. Same-id layouts mean
        // none of the binding or logic below has to know which one is showing.
        val isNewEndpoint = position == -1 || endpointId.isEmpty()
        setContentView(
            if (isNewEndpoint) R.layout.activity_api_endpoint_editor_new
            else R.layout.activity_api_endpoint_editor
        )

        preferences = Preferences.getPreferences(this, "")
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(this)

        bindViews()
        applyTheme()
        loadValues()
        initLogic()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptExit()
            }
        })
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        activityTitle = findViewById(R.id.activity_title)
        btnDelete = findViewById(R.id.btn_delete)
        btnSave = findViewById(R.id.btn_save)
        fieldLabel = findViewById(R.id.field_label)
        labelError = findViewById(R.id.text_field_label_error)
        fieldModel = findViewById(R.id.field_model)
        fieldMaxTokens = findViewById(R.id.field_max_tokens)
        fieldContextWindow = findViewById(R.id.field_context_window)
        fieldTimeout = findViewById(R.id.field_timeout)
        fieldResponseTime = findViewById(R.id.field_response_time)
        fieldEndSeparator = findViewById(R.id.field_end_separator)
        fieldPrefix = findViewById(R.id.field_prefix)
        fieldHost = findViewById(R.id.field_host)
        hostError = findViewById(R.id.text_field_host_error)
        fieldChatEndpoint = findViewById(R.id.field_chat_endpoint)
        fieldApiKey = findViewById(R.id.field_api_key)
        fieldAuthType = findViewById(R.id.field_auth_type)
        rowChooseProvider = findViewById(R.id.row_choose_provider)
        sectionAdvancedOptions = findViewById(R.id.section_advanced_options)
        fieldProviderDiscoveryPath = findViewById(R.id.field_provider_discovery_path)
        sliderTemperature = findViewById(R.id.slider_temperature)
        sliderTopP = findViewById(R.id.slider_top_p)
        sliderFrequencyPenalty = findViewById(R.id.slider_frequency_penalty)
        sliderPresencePenalty = findViewById(R.id.slider_presence_penalty)
        imageCapabilityHeader = findViewById(R.id.image_capability_header)
        imageCapabilityChevron = findViewById(R.id.image_capability_chevron)
        imageCapabilityBody = findViewById(R.id.image_capability_body)
        imageCapabilityEmpty = findViewById(R.id.image_capability_empty)
        imageCapabilityRows = findViewById(R.id.image_capability_rows)
        btnClearImageCapability = findViewById(R.id.btn_clear_image_capability)
        btnClearToolCapability = findViewById(R.id.btn_clear_tool_capability)
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
            btnDelete?.backgroundTintList = amoledTint
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
            btnDelete?.backgroundTintList = barTint
        }
    }

    private fun loadValues() {
        val endpoint: ApiEndpointObject = if (position == -1 || endpointId.isEmpty()) {
            // A new endpoint starts with no model chosen (no gpt-4o pre-load);
            // one is required before it can be saved.
            ApiEndpointObject("", "", "", model = "")
        } else {
            apiEndpointPreferences!!.getApiEndpoint(this, endpointId)
        }
        // The record's current label anchors the "Default" delete guard; the id
        // stays in [endpointId] and is what the record is saved/deleted under.
        oldLabel = endpoint.label

        // Header title: the profile's own name plus the app's fixed "API
        // Endpoint" suffix, preserving whatever capitalization the user gave
        // the label. A brand-new profile has no label yet, so it falls back
        // to a generic title instead of "  API Endpoint".
        activityTitle?.text = if (endpoint.label.isBlank()) {
            getString(R.string.title_api_endpoint_new)
        } else {
            getString(R.string.title_api_endpoint_named, endpoint.label)
        }

        fieldLabel?.setText(endpoint.label)
        fieldHost?.setText(endpoint.host)

        fieldChatEndpoint?.setText(
            endpoint.chatEndpoint.ifBlank { ApiEndpointObject.DEFAULT_CHAT_ENDPOINT }
        )

        selectedAuthType = endpoint.authType.ifBlank { ApiEndpointObject.AUTH_BEARER }
        fieldAuthType?.setText(authLabel(selectedAuthType))

        selectedModel = endpoint.model
        fieldModel?.setText(selectedModel)

        currentProvider = endpoint.provider

        // Prefilled with OpenRouter's default discovery path when the profile
        // has no custom value; visible only on OpenRouter endpoints.
        fieldProviderDiscoveryPath?.setText(
            endpoint.providerDiscoveryPath.ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
        )

        sliderTemperature?.value = (endpoint.temperature * 10f).coerceIn(0f, 20f)
        sliderTemperature?.setLabelFormatter { "${it / 10.0}" }
        sliderTopP?.value = (endpoint.topP * 10f).coerceIn(0f, 10f)
        sliderTopP?.setLabelFormatter { "${it / 10.0}" }
        sliderFrequencyPenalty?.value = (endpoint.frequencyPenalty * 10f).coerceIn(-20f, 20f)
        sliderFrequencyPenalty?.setLabelFormatter { "${it / 10.0}" }
        sliderPresencePenalty?.value = (endpoint.presencePenalty * 10f).coerceIn(-20f, 20f)
        sliderPresencePenalty?.setLabelFormatter { "${it / 10.0}" }

        fieldMaxTokens?.setText(endpoint.maxTokens.toString())
        fieldContextWindow?.setText(
            endpoint.contextWindowTokens
                ?.takeIf { endpoint.contextWindowModelId == selectedModel }
                ?.toString()
                .orEmpty()
        )
        fieldTimeout?.setText(endpoint.connectTimeoutSeconds.toString())
        fieldResponseTime?.setText(endpoint.responseTimeoutSeconds.toString())
        fieldEndSeparator?.setText(endpoint.endSeparator)
        fieldPrefix?.setText(endpoint.prefix)

        // API-key masking: stars while a key already exists, cleared on focus.
        originalApiKey = endpoint.apiKey
        keyHasValue = originalApiKey.isNotEmpty() && originalApiKey != "null"
        if (keyHasValue) {
            fieldApiKey?.setText(API_KEY_MASK)
            apiKeyMasked = true
        } else {
            fieldApiKey?.setText("")
            apiKeyMasked = false
        }

        currentCapabilityJson = endpoint.imageCapabilityByModel
        populateCapabilitySection()
        currentToolCapabilityJson = endpoint.toolCapabilityByModel
        refreshToolCapabilityReset()

        // A brand-new profile has nothing to delete yet.
        btnDelete?.visibility = if (position == -1) ImageButton.GONE else ImageButton.VISIBLE

        updateHostWarning()
        updateOpenRouterSections()
        initialSnapshot = snapshot()
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { attemptExit() }
        btnSave?.setOnClickListener { onSaveClicked() }
        btnDelete?.setOnClickListener { onDeleteClicked() }

        fieldHost?.doAfterTextChanged {
            updateHostWarning()
            updateOpenRouterSections()
        }

        fieldAuthType?.setOnClickListener { showAuthTypeChooser() }
        fieldModel?.setOnClickListener { showModelChooser() }
        rowChooseProvider?.setOnClickListener { openChooseProvider() }

        initFloatingLabels()

        imageCapabilityHeader?.setOnClickListener { toggleCapabilitySection() }
        btnClearImageCapability?.setOnClickListener { confirmClearCapability() }
        btnClearToolCapability?.setOnClickListener { confirmClearToolCapability() }

        // Tap the key field: drop the star mask so the user gets a blank cursor.
        // If they then leave it blank, re-mask so the stored key is still shown.
        fieldApiKey?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (apiKeyMasked) {
                    fieldApiKey?.setText("")
                    apiKeyMasked = false
                }
            } else {
                if (!apiKeyMasked && keyHasValue && fieldApiKey?.text.toString().isEmpty()) {
                    fieldApiKey?.setText(API_KEY_MASK)
                    apiKeyMasked = true
                }
            }
        }
    }

    /**
     * The label behaviour the owner asked for, without Material's embedded
     * floating hint: every box shows its field name as placeholder while empty,
     * and the matching top-left title (a Widget.App.Field.Label TextView)
     * appears only once the box has text. The title follows the box live as the
     * user types, chooses a model or auth mode, or clears the field. Boxes that
     * always carry text (Auth Mode, Chat Completions Endpoint, the timeout and
     * token fields) therefore show their title from the start. The Context
     * Window box has no title here — its section heading above it is the title.
     */
    private fun initFloatingLabels() {
        bindFloatingLabel(fieldLabel, R.id.label_label)
        bindFloatingLabel(fieldModel, R.id.label_model)
        bindFloatingLabel(fieldTimeout, R.id.label_timeout)
        bindFloatingLabel(fieldResponseTime, R.id.label_response_time)
        bindFloatingLabel(fieldMaxTokens, R.id.label_max_tokens)
        bindFloatingLabel(fieldEndSeparator, R.id.label_end_separator)
        bindFloatingLabel(fieldPrefix, R.id.label_prefix)
        bindFloatingLabel(fieldHost, R.id.label_host)
        bindFloatingLabel(fieldChatEndpoint, R.id.label_chat_endpoint)
        bindFloatingLabel(fieldApiKey, R.id.label_api_key)
        bindFloatingLabel(fieldAuthType, R.id.label_auth)
    }

    private fun bindFloatingLabel(box: TextInputEditText?, labelId: Int) {
        if (box == null) return
        val label = findViewById<TextView>(labelId) ?: return
        fun sync() {
            label.visibility = if (box.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        sync()
        box.doAfterTextChanged { sync() }
    }

    /** Show a short line of error/warning text beneath a field's box. */
    private fun setFieldError(view: TextView?, message: String) {
        view?.text = message
        view?.visibility = View.VISIBLE
    }

    /** Hide a field's error/warning line. */
    private fun clearFieldError(view: TextView?) {
        view?.text = ""
        view?.visibility = View.GONE
    }

    private fun authLabel(authType: String): String {
        return when (authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> getString(R.string.auth_mode_x_api_key)
            ApiEndpointObject.AUTH_API_KEY -> getString(R.string.auth_mode_api_key)
            else -> getString(R.string.auth_mode_bearer)
        }
    }

    private fun showAuthTypeChooser() {
        val labels = authTypes.map { authLabel(it) }.toTypedArray()
        val current = authTypes.indexOf(selectedAuthType).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.api_endpoint_auth_mode)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                selectedAuthType = authTypes[which]
                fieldAuthType?.setText(authLabel(selectedAuthType))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /**
     * Open the Choose Provider screen for this endpoint (OpenRouter). Seeds it
     * with the current model and its stored routing type; the result flows back
     * through [chooseProviderLauncher].
     */
    private fun openChooseProvider() {
        val routingType = when {
            chooseProviderVisited -> pendingRoutingType
            selectedModel.isNotBlank() ->
                favoriteModelsPreferences?.getRoutingType(selectedModel, endpointId)
                    ?: FavoriteModelObject.ROUTING_AUTOMATIC
            else -> FavoriteModelObject.ROUTING_AUTOMATIC
        }
        val intent = android.content.Intent(this, ChooseProviderActivity::class.java)
        intent.putExtra(ChooseProviderActivity.EXTRA_ENDPOINT_ID, endpointId)
        intent.putExtra(ChooseProviderActivity.EXTRA_MODEL, selectedModel)
        intent.putExtra(ChooseProviderActivity.EXTRA_ROUTING_TYPE, routingType)
        // Connection details for the provider-discovery fetch, passed live from
        // the editor's fields so the screen works for an unsaved endpoint too.
        intent.putExtra(ChooseProviderActivity.EXTRA_HOST, fieldHost?.text.toString().trim())
        intent.putExtra(ChooseProviderActivity.EXTRA_API_KEY, effectiveApiKey())
        intent.putExtra(ChooseProviderActivity.EXTRA_AUTH_TYPE, selectedAuthType)
        intent.putExtra(
            ChooseProviderActivity.EXTRA_DISCOVERY_PATH,
            fieldProviderDiscoveryPath?.text.toString().trim()
                .ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
        )
        chooseProviderLauncher.launch(intent)
    }

    private fun showModelChooser() {
        val modelDialog = AdvancedModelSelectorDialogFragment.newInstance(selectedModel, "")
        modelDialog.setModelSelectedListener { model ->
            if (model != selectedModel) fieldContextWindow?.setText("")
            selectedModel = model
            fieldModel?.setText(model)
        }
        modelDialog.show(supportFragmentManager, "ProfileModelSelector")
    }

    private fun normalizedChatEndpoint(): String {
        val value = fieldChatEndpoint?.text.toString().trim()
        return value.ifEmpty { ApiEndpointObject.DEFAULT_CHAT_ENDPOINT }
    }

    /** The API key to persist: the stored key unless the user typed a new one. */
    private fun effectiveApiKey(): String {
        val typed = fieldApiKey?.text.toString()
        return if (apiKeyMasked || typed.isBlank()) originalApiKey else typed
    }

    private fun buildEndpointObject(): ApiEndpointObject {
        return ApiEndpointObject(
            label = fieldLabel?.text.toString().trim(),
            host = fieldHost?.text.toString().trim(),
            apiKey = effectiveApiKey(),
            chatEndpoint = normalizedChatEndpoint(),
            authType = selectedAuthType,
            model = selectedModel,
            temperature = (sliderTemperature?.value ?: (ApiEndpointObject.DEFAULT_TEMPERATURE * 10f)) / 10f,
            topP = (sliderTopP?.value ?: (ApiEndpointObject.DEFAULT_TOP_P * 10f)) / 10f,
            frequencyPenalty = (sliderFrequencyPenalty?.value ?: (ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY * 10f)) / 10f,
            presencePenalty = (sliderPresencePenalty?.value ?: (ApiEndpointObject.DEFAULT_PRESENCE_PENALTY * 10f)) / 10f,
            maxTokens = fieldMaxTokens?.text.toString().toIntOrNull() ?: ApiEndpointObject.DEFAULT_MAX_TOKENS,
            endSeparator = fieldEndSeparator?.text.toString(),
            prefix = fieldPrefix?.text.toString(),
            provider = currentProvider,
            connectTimeoutSeconds = ApiEndpointObject.coerceConnectTimeoutSeconds(
                fieldTimeout?.text.toString().toIntOrNull() ?: ApiEndpointObject.DEFAULT_CONNECT_TIMEOUT_SECONDS
            ),
            responseTimeoutSeconds = ApiEndpointObject.coerceResponseTimeoutSeconds(
                fieldResponseTime?.text.toString().toIntOrNull() ?: ApiEndpointObject.DEFAULT_RESPONSE_TIMEOUT_SECONDS
            ),
            // Rename keeps the same id; a new profile carries "" and is minted an
            // id (or the reserved Default id) on first save.
            id = endpointId,
            contextWindowTokens = fieldContextWindow?.text.toString()
                .trim()
                .toIntOrNull()
                ?.takeIf { it > 0 },
            contextWindowModelId = if (fieldContextWindow?.text.toString().isBlank()) {
                ""
            } else {
                selectedModel
            },
            imageCapabilityByModel = currentCapabilityJson,
            toolCapabilityByModel = currentToolCapabilityJson,
            // The default path is stored as blank so a future default change
            // reaches profiles that never customized it.
            providerDiscoveryPath = fieldProviderDiscoveryPath?.text.toString().trim()
                .takeIf { it != ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
                ?: ""
        )
    }

    /**
     * Show the OpenRouter-only rows (Choose Provider, Advanced Options) only
     * while the Base URL points at an OpenRouter endpoint, using the same rule
     * the rest of the app applies (ImageProviderAdapters.isOpenRouter): the host
     * contains "openrouter.ai". Called on load and live as the host is edited,
     * so the rows appear or disappear as soon as the URL matches.
     */
    private fun updateOpenRouterSections() {
        val isOpenRouter = fieldHost?.text.toString().contains("openrouter.ai", ignoreCase = true)
        val visibility = if (isOpenRouter) View.VISIBLE else View.GONE
        rowChooseProvider?.visibility = visibility
        sectionAdvancedOptions?.visibility = visibility
    }

    private fun updateHostWarning() {
        val host = fieldHost?.text.toString().trim()
        if (host.startsWith("http://")) {
            setFieldError(hostError, getString(R.string.warning_http_endpoint_inline))
        } else {
            clearFieldError(hostError)
        }
    }

    private fun isValidEndpointUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun onSaveClicked() {
        val label = fieldLabel?.text.toString().trim()
        val host = fieldHost?.text.toString().trim()

        clearFieldError(labelError)

        if (label.isEmpty()) {
            setFieldError(labelError, getString(R.string.label_error_api_endpoint_empty))
            return
        }

        if (host.isEmpty()) {
            setFieldError(hostError, getString(R.string.label_error_api_endpoint_empty))
            return
        }

        if (!isValidEndpointUrl(host)) {
            setFieldError(hostError, getString(R.string.label_error_api_endpoint_invalid_url))
            return
        }

        // A preferred model is required; a new endpoint no longer defaults to
        // one silently. Nothing is saved until the user chooses.
        if (selectedModel.isBlank()) {
            showNoticeDialog(getString(R.string.api_endpoint_model_required_message))
            return
        }

        // Timeout out of range: tell the user with a snackbar, correct the field
        // to the boundary, and stop this save so they can see the corrected value
        // and save again.
        if (!checkTimeoutInRange()) {
            return
        }

        // Plain-http endpoints send the key and all content unencrypted. Allowed
        // (local/LAN servers are legitimate), but only after explicit consent.
        if (host.startsWith("http://")) {
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.title_http_endpoint_warning)
                .setMessage(R.string.message_http_endpoint_warning)
                .setPositiveButton(R.string.btn_http_endpoint_accept) { _, _ -> commitSave() }
                .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                .show()
            return
        }

        commitSave()
    }

    /**
     * Validate both timeout fields. A blank / non-numeric field is left to its
     * default (in range) and passes silently. An out-of-range value shows the
     * matching snackbar, rewrites the field to the boundary value, and returns
     * false so the caller stops the save. Connection Timeout is bounded 5..300;
     * Response Time has a floor of 45 and no ceiling (owner ruling).
     */
    private fun checkTimeoutInRange(): Boolean {
        val connect = fieldTimeout?.text.toString().toIntOrNull()
        if (connect != null) {
            if (connect < ApiEndpointObject.MIN_CONNECT_TIMEOUT_SECONDS) {
                fieldTimeout?.setText(ApiEndpointObject.MIN_CONNECT_TIMEOUT_SECONDS.toString())
                showTimeoutSnackbar(R.string.api_endpoint_timeout_too_low)
                return false
            }
            if (connect > ApiEndpointObject.MAX_CONNECT_TIMEOUT_SECONDS) {
                fieldTimeout?.setText(ApiEndpointObject.MAX_CONNECT_TIMEOUT_SECONDS.toString())
                showTimeoutSnackbar(R.string.api_endpoint_timeout_too_high)
                return false
            }
        }

        val response = fieldResponseTime?.text.toString().toIntOrNull()
        if (response != null && response < ApiEndpointObject.MIN_RESPONSE_TIMEOUT_SECONDS) {
            fieldResponseTime?.setText(ApiEndpointObject.MIN_RESPONSE_TIMEOUT_SECONDS.toString())
            showTimeoutSnackbar(R.string.api_endpoint_response_time_too_low)
            return false
        }

        return true
    }

    /** Snackbar with an Okay button that stays until the user dismisses it. */
    private fun showTimeoutSnackbar(messageRes: Int) {
        val root = findViewById<android.view.View>(R.id.root) ?: return
        Snackbar.make(root, getString(messageRes), Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.okay) { /* dismiss */ }
            .show()
    }

    private fun commitSave() {
        val endpoint = buildEndpointObject()
        // One save path for create AND rename — the object carries its stable id
        // (blank for a new profile, minted in place), so a rename updates the
        // record under the same id and the API key / favorites / per-chat
        // selection stay attached.
        val savedId = apiEndpointPreferences!!.setApiEndpoint(this, endpoint)

        // Choose Provider choices (OpenRouter): applied only if the user visited
        // that screen and saved. Favoriting is no longer optional — saving on
        // that screen always makes the model a favorite, and the favorite is
        // what stores its routing memory (owner ruling — the favorite is the
        // housekeeping unit for provider choices). Removal happens by
        // unfavoriting the model in the Favorite AI Models list, not here.
        if (chooseProviderVisited && selectedModel.isNotBlank()) {
            favoriteModelsPreferences?.addFavoriteModel(
                FavoriteModelObject(
                    selectedModel, savedId, pendingRoutingType,
                    pendingSelectedProvider, pendingAllowFallbacks,
                    pendingProviderOrder, pendingIgnoredProviders
                )
            )
        }

        val data = android.content.Intent()
        data.putExtra("apiEndpointId", savedId)
        data.putExtra("deleted", false)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun onDeleteClicked() {
        if (oldLabel == "Default") {
            showNoticeDialog(getString(R.string.default_api_endpoint_error_delete))
            return
        }

        if (apiEndpointPreferences!!.getApiEndpointsList(this).size <= 1) {
            showNoticeDialog(getString(R.string.api_endpoint_error_zero))
            return
        }

        // House two-button destructive shape (same as Edit Companion/Edit
        // Persona's delete confirm and the Discard Changes dialog): a real
        // Primary button first ("Delete", proceeds) and a real Destructive-
        // styled button second ("Cancel", just dismisses).
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.api_endpoint_delete_title)
            .setMessage(R.string.api_endpoint_delete_message)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_delete)
            setOnClickListener {
                dialog.dismiss()
                commitDelete()
            }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
    }

    private fun commitDelete() {
        apiEndpointPreferences!!.deleteApiEndpoint(this, endpointId)

        val data = android.content.Intent()
        data.putExtra("deleted", true)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun showNoticeDialog(message: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(message)
            .setPositiveButton(R.string.okay) { _, _ -> }
            .show()
    }

    /** Back / cancel. Confirms first if anything changed. */
    private fun attemptExit() {
        if (snapshot() != initialSnapshot) {
            DiscardChangesDialog.show(this) {
                setResult(RESULT_CANCELED)
                finish()
            }
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** Serialised form of every editable value, used only for change detection.
     *  The key contributes a stable marker unless the user actually typed a new
     *  one, so re-masking or focusing the field never counts as a change. */
    private fun snapshot(): String {
        val typedKey = fieldApiKey?.text.toString()
        val keyChanged = !apiKeyMasked && typedKey.isNotBlank() && typedKey != originalApiKey
        return listOf(
            fieldLabel?.text.toString(),
            fieldHost?.text.toString(),
            normalizedChatEndpoint(),
            selectedAuthType,
            selectedModel,
            sliderTemperature?.value.toString(),
            sliderTopP?.value.toString(),
            sliderFrequencyPenalty?.value.toString(),
            sliderPresencePenalty?.value.toString(),
            fieldMaxTokens?.text.toString(),
            fieldContextWindow?.text.toString(),
            fieldTimeout?.text.toString(),
            fieldResponseTime?.text.toString(),
            fieldEndSeparator?.text.toString(),
            fieldPrefix?.text.toString(),
            if (keyChanged) "key_changed" else "key_same",
            fieldProviderDiscoveryPath?.text.toString(),
            currentCapabilityJson,
            currentToolCapabilityJson,
            // Choose Provider choices count as unsaved edits once made, so the
            // discard-changes guard offers to save them.
            if (chooseProviderVisited) {
                "cp:$pendingRoutingType:$pendingSelectedProvider:" +
                    "$pendingAllowFallbacks:$pendingProviderOrder:$pendingIgnoredProviders"
            } else {
                "cp:none"
            }
        ).joinToString("")
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
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }

    /* ---------- Image capability section ---------- */

    private fun toggleCapabilitySection() {
        imageCapabilityExpanded = !imageCapabilityExpanded
        imageCapabilityChevron?.rotation = if (imageCapabilityExpanded) 180f else 0f
        imageCapabilityBody?.visibility = if (imageCapabilityExpanded) View.VISIBLE else View.GONE
    }

    private fun populateCapabilitySection() {
        val entries = ImageCapabilityStore.entries(currentCapabilityJson)
        imageCapabilityRows?.removeAllViews()
        if (entries.isEmpty()) {
            imageCapabilityEmpty?.visibility = View.VISIBLE
            imageCapabilityRows?.visibility = View.GONE
            btnClearImageCapability?.visibility = View.GONE
        } else {
            imageCapabilityEmpty?.visibility = View.GONE
            imageCapabilityRows?.visibility = View.VISIBLE
            btnClearImageCapability?.visibility = View.VISIBLE
            for ((modelId, capability) in entries) {
                addCapabilityRow(modelId, capability)
            }
        }
    }

    private fun addCapabilityRow(modelId: String, capability: ImageCapability) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val label = TextView(this, null, 0, R.style.Widget_App_Dropdown_Label).apply {
            text = modelId
        }
        val value = TextView(this, null, 0, R.style.Widget_App_Dropdown_Value).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (8 * dp).toInt() }
            text = capabilityLabel(capability)
            tag = modelId
            setOnClickListener { showCapabilityDropdown(this, modelId) }
        }
        row.addView(label)
        row.addView(value)
        imageCapabilityRows?.addView(row)
    }

    private fun capabilityLabel(capability: ImageCapability): String = when (capability) {
        ImageCapability.UNKNOWN -> getString(R.string.image_capability_state_unknown)
        ImageCapability.SUPPORTED -> getString(R.string.image_capability_state_supported)
        ImageCapability.UNSUPPORTED -> getString(R.string.image_capability_state_unsupported)
    }

    private val capabilityOptions = arrayOf(
        ImageCapability.UNKNOWN,
        ImageCapability.SUPPORTED,
        ImageCapability.UNSUPPORTED
    )

    private fun showCapabilityDropdown(anchor: View, modelId: String) {
        if (isFinishing) return
        val labels = capabilityOptions.map { capabilityLabel(it) }
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            val chosen = capabilityOptions[position]
            currentCapabilityJson = ImageCapabilityStore.set(currentCapabilityJson, modelId, chosen)
            populateCapabilitySection()
        }
        popup.show()
    }

    /* ---------- Tool capability reset (plan §8) ---------- */

    private fun refreshToolCapabilityReset() {
        btnClearToolCapability?.visibility =
            if (org.teslasoft.assistant.imagegen.ToolCapabilityStore.isEmpty(currentToolCapabilityJson)) {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    private fun confirmClearToolCapability() {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.tool_capability_clear_confirm_title)
            .setMessage(R.string.tool_capability_clear_confirm_body)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.image_capability_clear_confirm_button)
            setOnClickListener {
                dialog.dismiss()
                currentToolCapabilityJson =
                    org.teslasoft.assistant.imagegen.ToolCapabilityStore.clear()
                refreshToolCapabilityReset()
            }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
    }

    private fun confirmClearCapability() {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.image_capability_clear_confirm_title)
            .setMessage(R.string.image_capability_clear_confirm_body)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.image_capability_clear_confirm_button)
            setOnClickListener {
                dialog.dismiss()
                currentCapabilityJson = ImageCapabilityStore.clear()
                populateCapabilitySection()
            }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
    }
}
