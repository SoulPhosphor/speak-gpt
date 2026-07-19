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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.util.Hash

/**
 * Full-page editor for a single API chat endpoint profile (replaced the old
 * EditApiEndpointDialogFragment pop-up, July 2026). Reached from
 * ApiEndpointsListActivity.
 *
 * Behaviour the owner asked for:
 * - The upper-left double-chevron back button (and the system back gesture)
 *   is "cancel"; backing out with unsaved edits shows a "Discard changes?"
 *   dialog first.
 * - "Delete" and "Save" sit as tappable words at the top-left of the page.
 *   Save just saves (no confirm). Delete confirms first.
 * - The API-key field shows a run of stars when a key already exists; tapping
 *   in clears it to an empty cursor. Leaving it untouched (or blank) keeps the
 *   existing key, even on save.
 *
 * Result contract back to the list:
 * - RESULT_OK + extra "deleted"=false + "apiEndpointLabel" → saved (list marks
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

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnDelete: TextView? = null
    private var btnSave: TextView? = null

    private var fieldLabel: TextInputEditText? = null
    private var fieldModel: TextInputEditText? = null
    private var fieldProvider: TextInputEditText? = null
    private var fieldMaxTokens: TextInputEditText? = null
    private var fieldTimeout: TextInputEditText? = null
    private var fieldResponseTime: TextInputEditText? = null
    private var fieldEndSeparator: TextInputEditText? = null
    private var fieldPrefix: TextInputEditText? = null
    private var fieldHost: TextInputEditText? = null
    private var hostInputLayout: TextInputLayout? = null
    private var labelInputLayout: TextInputLayout? = null
    private var fieldChatEndpoint: TextInputEditText? = null
    private var fieldApiKey: TextInputEditText? = null
    private var fieldAuthType: TextInputEditText? = null
    private var sliderTemperature: Slider? = null
    private var sliderTopP: Slider? = null
    private var sliderFrequencyPenalty: Slider? = null
    private var sliderPresencePenalty: Slider? = null

    private var position: Int = -1
    private var oldLabel: String = ""
    private var selectedAuthType: String = ApiEndpointObject.AUTH_BEARER
    private var selectedModel: String = ApiEndpointObject.DEFAULT_MODEL

    /** The key stored on disk when the screen opened. Preserved unless the user
     *  actually types a new one. */
    private var originalApiKey: String = ""
    private var keyHasValue: Boolean = false
    /** True while the field shows the star mask rather than a real/edited value. */
    private var apiKeyMasked: Boolean = false

    /** Snapshot of the initial field values, for the discard-changes check. */
    private var initialSnapshot: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_api_endpoint_editor)

        preferences = Preferences.getPreferences(this, "")
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)

        position = intent.getIntExtra("position", -1)
        oldLabel = intent.getStringExtra("label") ?: ""

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
        btnDelete = findViewById(R.id.btn_delete_text)
        btnSave = findViewById(R.id.btn_save_text)
        fieldLabel = findViewById(R.id.field_label)
        labelInputLayout = findViewById(R.id.textInputLayout10)
        fieldModel = findViewById(R.id.field_model)
        fieldProvider = findViewById(R.id.field_provider)
        fieldMaxTokens = findViewById(R.id.field_max_tokens)
        fieldTimeout = findViewById(R.id.field_timeout)
        fieldResponseTime = findViewById(R.id.field_response_time)
        fieldEndSeparator = findViewById(R.id.field_end_separator)
        fieldPrefix = findViewById(R.id.field_prefix)
        fieldHost = findViewById(R.id.field_host)
        hostInputLayout = findViewById(R.id.textInputLayout11)
        fieldChatEndpoint = findViewById(R.id.field_chat_endpoint)
        fieldApiKey = findViewById(R.id.field_api_key)
        fieldAuthType = findViewById(R.id.field_auth_type)
        sliderTemperature = findViewById(R.id.slider_temperature)
        sliderTopP = findViewById(R.id.slider_top_p)
        sliderFrequencyPenalty = findViewById(R.id.slider_frequency_penalty)
        sliderPresencePenalty = findViewById(R.id.slider_presence_penalty)
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

    private fun loadValues() {
        val endpoint: ApiEndpointObject = if (position == -1 || oldLabel.isEmpty()) {
            ApiEndpointObject("", "", "")
        } else {
            apiEndpointPreferences!!.getApiEndpoint(this, Hash.hash(oldLabel))
        }

        fieldLabel?.setText(endpoint.label)
        fieldHost?.setText(endpoint.host)

        fieldChatEndpoint?.setText(
            endpoint.chatEndpoint.ifBlank { ApiEndpointObject.DEFAULT_CHAT_ENDPOINT }
        )

        selectedAuthType = endpoint.authType.ifBlank { ApiEndpointObject.AUTH_BEARER }
        fieldAuthType?.setText(authLabel(selectedAuthType))

        selectedModel = endpoint.model.ifBlank { ApiEndpointObject.DEFAULT_MODEL }
        fieldModel?.setText(selectedModel)

        fieldProvider?.setText(endpoint.provider)

        sliderTemperature?.value = (endpoint.temperature * 10f).coerceIn(0f, 20f)
        sliderTemperature?.setLabelFormatter { "${it / 10.0}" }
        sliderTopP?.value = (endpoint.topP * 10f).coerceIn(0f, 10f)
        sliderTopP?.setLabelFormatter { "${it / 10.0}" }
        sliderFrequencyPenalty?.value = (endpoint.frequencyPenalty * 10f).coerceIn(-20f, 20f)
        sliderFrequencyPenalty?.setLabelFormatter { "${it / 10.0}" }
        sliderPresencePenalty?.value = (endpoint.presencePenalty * 10f).coerceIn(-20f, 20f)
        sliderPresencePenalty?.setLabelFormatter { "${it / 10.0}" }

        fieldMaxTokens?.setText(endpoint.maxTokens.toString())
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

        // A brand-new profile has nothing to delete. With Delete hidden, drop
        // Save's leading gap so it sits flush at the left instead of floating in.
        if (position == -1) {
            btnDelete?.visibility = TextView.GONE
            (btnSave?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.marginStart = 0
                btnSave?.layoutParams = it
            }
        } else {
            btnDelete?.visibility = TextView.VISIBLE
        }

        updateHostWarning()
        initialSnapshot = snapshot()
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { attemptExit() }
        btnSave?.setOnClickListener { onSaveClicked() }
        btnDelete?.setOnClickListener { onDeleteClicked() }

        fieldHost?.doAfterTextChanged { updateHostWarning() }

        fieldAuthType?.setOnClickListener { showAuthTypeChooser() }
        findViewById<TextInputLayout>(R.id.textInputLayoutAuth)?.setOnClickListener { showAuthTypeChooser() }

        fieldModel?.setOnClickListener { showModelChooser() }
        findViewById<TextInputLayout>(R.id.textInputLayoutModel)?.setOnClickListener { showModelChooser() }

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

    private fun showModelChooser() {
        val modelDialog = AdvancedModelSelectorDialogFragment.newInstance(selectedModel, "")
        modelDialog.setModelSelectedListener { model ->
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
            model = selectedModel.ifBlank { ApiEndpointObject.DEFAULT_MODEL },
            temperature = (sliderTemperature?.value ?: (ApiEndpointObject.DEFAULT_TEMPERATURE * 10f)) / 10f,
            topP = (sliderTopP?.value ?: (ApiEndpointObject.DEFAULT_TOP_P * 10f)) / 10f,
            frequencyPenalty = (sliderFrequencyPenalty?.value ?: (ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY * 10f)) / 10f,
            presencePenalty = (sliderPresencePenalty?.value ?: (ApiEndpointObject.DEFAULT_PRESENCE_PENALTY * 10f)) / 10f,
            maxTokens = fieldMaxTokens?.text.toString().toIntOrNull() ?: ApiEndpointObject.DEFAULT_MAX_TOKENS,
            endSeparator = fieldEndSeparator?.text.toString(),
            prefix = fieldPrefix?.text.toString(),
            provider = fieldProvider?.text.toString().trim(),
            connectTimeoutSeconds = ApiEndpointObject.coerceConnectTimeoutSeconds(
                fieldTimeout?.text.toString().toIntOrNull() ?: ApiEndpointObject.DEFAULT_CONNECT_TIMEOUT_SECONDS
            ),
            responseTimeoutSeconds = ApiEndpointObject.coerceResponseTimeoutSeconds(
                fieldResponseTime?.text.toString().toIntOrNull() ?: ApiEndpointObject.DEFAULT_RESPONSE_TIMEOUT_SECONDS
            )
        )
    }

    private fun updateHostWarning() {
        val host = fieldHost?.text.toString().trim()
        hostInputLayout?.error = if (host.startsWith("http://")) {
            getString(R.string.warning_http_endpoint_inline)
        } else {
            null
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

        labelInputLayout?.error = null

        if (label.isEmpty()) {
            labelInputLayout?.error = getString(R.string.label_error_api_endpoint_empty)
            return
        }

        if (host.isEmpty()) {
            hostInputLayout?.error = getString(R.string.label_error_api_endpoint_empty)
            return
        }

        if (!isValidEndpointUrl(host)) {
            hostInputLayout?.error = getString(R.string.label_error_api_endpoint_invalid_url)
            return
        }

        // The built-in "Default" profile must keep its name (other code resolves
        // it by that label).
        if (position != -1 && oldLabel == "Default" && label != "Default") {
            showNoticeDialog(getString(R.string.default_api_endpoint_error))
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
        if (position == -1) {
            apiEndpointPreferences!!.setApiEndpoint(this, endpoint)
        } else {
            apiEndpointPreferences!!.editEndpoint(this, oldLabel, endpoint)
        }

        val data = android.content.Intent()
        data.putExtra("apiEndpointLabel", endpoint.label)
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

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.message_delete_profile)
            .setPositiveButton(R.string.okay) { _, _ -> commitDelete() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun commitDelete() {
        apiEndpointPreferences!!.deleteApiEndpoint(this, Hash.hash(oldLabel))

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
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setMessage(R.string.discard_changes_q)
                .setPositiveButton(R.string.yes) { _, _ ->
                    setResult(RESULT_CANCELED)
                    finish()
                }
                .setNegativeButton(R.string.no) { _, _ -> }
                .show()
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
            fieldProvider?.text.toString(),
            sliderTemperature?.value.toString(),
            sliderTopP?.value.toString(),
            sliderFrequencyPenalty?.value.toString(),
            sliderPresencePenalty?.value.toString(),
            fieldMaxTokens?.text.toString(),
            fieldTimeout?.text.toString(),
            fieldResponseTime?.text.toString(),
            fieldEndSeparator?.text.toString(),
            fieldPrefix?.text.toString(),
            if (keyChanged) "key_changed" else "key_same"
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
}
