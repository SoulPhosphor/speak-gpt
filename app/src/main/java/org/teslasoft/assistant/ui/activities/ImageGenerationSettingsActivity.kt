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
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.imagegen.*
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.ui.widgets.AppDropdown

/** Settings are scoped to the endpoint and exact model. Choices come from fetched metadata. */
class ImageGenerationSettingsActivity : FragmentActivity() {
    private lateinit var preferences: Preferences
    private lateinit var endpoints: ApiEndpointPreferences
    private var metadataJob: Job? = null
    private var currentMetadata: ImageModelMetadata? = null
    private val endpointLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getStringExtra("apiEndpointId")?.let {
            preferences.selectImageGeneratorEndpoint(it)
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_image_generation_settings)
        ImageGenerationMigration.runIfNeeded(this)
        preferences = Preferences.getPreferences(this, "")
        endpoints = ApiEndpointPreferences.getApiEndpointPreferences(this)
        ScreenChrome.apply(this, findViewById(R.id.action_bar), findViewById(R.id.btn_back))
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        bindToggle(R.id.switch_imagine_command, preferences.getImagineCommandGlobal()) { preferences.setImagineCommandGlobal(it) }
        bindToggle(R.id.switch_ai_create_images, preferences.getAiCreateImagesEnabled()) {
            preferences.setAiCreateImagesEnabled(it)
            refreshAskBeforeVisibility()
        }
        bindToggle(R.id.switch_ask_before_creating, preferences.getAskBeforeAiImages()) { preferences.setAskBeforeAiImages(it) }
        bindToggle(R.id.switch_delete_images_with_chat, preferences.getDeleteImagesWithChat()) { preferences.setDeleteImagesWithChat(it) }
        refreshAskBeforeVisibility()
        findViewById<View>(R.id.row_image_service).setOnClickListener { openEndpointPicker() }
        findViewById<View>(R.id.row_image_model).setOnClickListener { openModelChooser() }
        findViewById<View>(R.id.image_settings_retry).setOnClickListener { refresh() }
        refresh()
    }

    private fun bindToggle(id: Int, checked: Boolean, save: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> save(value) }
        }
    }

    private fun refreshAskBeforeVisibility() {
        findViewById<View>(R.id.row_ask_before_creating).visibility =
            if (preferences.getAiCreateImagesEnabled()) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        metadataJob?.cancel()
        currentMetadata = null
        val endpointId = preferences.getImageGeneratorEndpointId()
        val modelId = preferences.getImageGeneratorModel()
        val endpoint = endpoints.getApiEndpoint(this, endpointId)
        findViewById<TextView>(R.id.text_image_service_value).text =
            endpoints.getApiEndpointsList(this).firstOrNull { it.id == endpointId }?.label
                ?: getString(R.string.label_endpoint_none)
        findViewById<TextView>(R.id.text_image_model_value).text = modelId.ifBlank { getString(R.string.label_endpoint_none) }
        val options = findViewById<LinearLayout>(R.id.image_model_options)
        val status = findViewById<TextView>(R.id.image_settings_status)
        val retry = findViewById<View>(R.id.image_settings_retry)
        clearSettingsRows(options)
        retry.visibility = View.GONE
        status.visibility = View.GONE
        if (endpointId.isBlank() || modelId.isBlank()) return
        status.visibility = View.VISIBLE
        status.setText(R.string.image_gen_settings_loading)
        renderSettings()
        metadataJob = lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                runCatching { ImageCatalogClient.model(endpoint, modelId, fresh = true) }.getOrNull()
            }
            currentMetadata = metadata
            if (metadata == null || !metadata.settingsVerified) {
                status.setText(R.string.image_gen_settings_unavailable)
                retry.visibility = View.VISIBLE
            } else status.setText(R.string.image_gen_settings_automatic)
            renderSettings()
        }
    }

    private fun label(key: String): String {
        val resource = when (key) {
        "shape" -> R.string.image_gen_setting_shape
        "size" -> R.string.image_gen_size
        "resolution" -> R.string.image_gen_resolution
        "aspect_ratio" -> R.string.image_gen_aspect_ratio
        "quality" -> R.string.image_gen_quality
        "background" -> R.string.image_gen_background
        "output_format" -> R.string.image_gen_output_format
        "output_compression" -> R.string.image_gen_compression
        "seed" -> R.string.image_gen_seed
        else -> return key.replace('_', ' ')
        }
        return getString(resource)
    }

    private fun savedRequest() = ImageGenerationRequest("", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC,
        preferences.getImageGeneratorEndpointId(), preferences.getImageGeneratorModel(),
        parameters = preferences.getImageGeneratorParameters(), defaultShape = preferences.getImageGeneratorShape(),
        defaultQuality = preferences.getImageGeneratorQuality())

    private fun renderSettings() {
        val parent = findViewById<LinearLayout>(R.id.image_model_options)
        clearSettingsRows(parent)
        val endpoint = endpoints.getApiEndpoint(this, preferences.getImageGeneratorEndpointId())
        val selected = ImageRequestOptions.savedSelections(savedRequest(), currentMetadata)
        ImageSettingSupport.rows(selected, currentMetadata,
            ImageCatalogClient.confirmedIncompatibilities(endpoint, preferences.getImageGeneratorModel()))
            .forEach { addSetting(parent, it) }
    }

    private fun clearSettingsRows(parent: LinearLayout) {
        // Rebuilding metadata rows is not a user edit and must not commit focused inputs.
        for (index in 0 until parent.childCount)
            parent.getChildAt(index).findViewById<EditText>(R.id.image_setting_input)?.onFocusChangeListener = null
        parent.removeAllViews()
    }

    private fun save(parameter: ImageParameter, value: String?) {
        val selected = ImageDimensionSettings.change(preferences.getImageGeneratorParameters(), parameter.key, value, currentMetadata)
        preferences.setImageGeneratorParameters(selected)
        // Only a deliberate control change replaces a legacy default. Metadata refresh is read-only.
        if (parameter.key in listOf("size", "resolution", "aspect_ratio")) preferences.setImageGeneratorShape(ImageShape.AUTOMATIC)
        if (parameter.key == "quality") preferences.setImageGeneratorQuality(ImageQuality.AUTOMATIC)
        renderSettings()
    }

    private fun addSetting(parent: LinearLayout, setting: ImageSettingState) {
        val layout = when (setting.control) {
            ImageSettingControl.DROPDOWN -> R.layout.view_image_setting_dropdown
            ImageSettingControl.BOOLEAN -> R.layout.view_image_setting_boolean
            ImageSettingControl.NUMBER -> R.layout.view_image_setting_number
            ImageSettingControl.TEXT -> R.layout.view_image_setting_text
            ImageSettingControl.STATUS -> R.layout.view_image_setting_status
        }
        val row = layoutInflater.inflate(layout, parent, false)
        row.tag = setting.key
        row.findViewById<TextView>(R.id.image_setting_label).text = label(setting.key)
        val previous = row.findViewById<TextView>(R.id.image_setting_previous)
        val subtitle = row.findViewById<TextView>(R.id.image_setting_subtitle)
        val selected = setting.selected
        val parameter = setting.parameter
        val automatic = getString(R.string.image_gen_option_automatic)
        val emptyLabel = if (setting.allowsAutomatic) automatic else getString(R.string.image_gen_setting_required)
        when (setting.control) {
            ImageSettingControl.STATUS -> {
                row.findViewById<TextView>(R.id.image_setting_value).setText(
                    if (setting.availability == ImageSettingAvailability.UNVERIFIED)
                        R.string.image_gen_setting_unverified else R.string.image_gen_setting_not_supported)
                if (selected != null) {
                    previous.text = getString(R.string.image_gen_setting_previous, selected)
                    previous.visibility = View.VISIBLE
                }
            }
            ImageSettingControl.DROPDOWN -> {
                val value = row.findViewById<TextView>(R.id.image_setting_value)
                val choices = setting.choices
                val offset = if (setting.allowsAutomatic) 1 else 0
                val labels = (if (setting.allowsAutomatic) listOf(automatic) else emptyList()) + choices
                value.text = selected ?: emptyLabel
                value.isEnabled = labels.isNotEmpty()
                value.setOnClickListener {
                    AppDropdown.show(value, labels, if (selected == null && setting.allowsAutomatic) 0 else choices.indexOf(selected)
                        .let { if (it < 0) -1 else it + offset }) { index ->
                        save(parameter!!, choices.getOrNull(index - offset))
                    }
                }
            }
            ImageSettingControl.BOOLEAN -> {
                row.findViewById<MaterialCheckBox>(R.id.image_setting_boolean).apply {
                    checkedState = when (selected) {
                        "true" -> MaterialCheckBox.STATE_CHECKED
                        "false" -> MaterialCheckBox.STATE_UNCHECKED
                        else -> MaterialCheckBox.STATE_INDETERMINATE
                    }
                    contentDescription = label(setting.key) + ": " + (selected ?: emptyLabel)
                    setOnClickListener { save(parameter!!, setting.nextBooleanSelection()) }
                }
            }
            ImageSettingControl.NUMBER, ImageSettingControl.TEXT -> {
                val input = row.findViewById<EditText>(R.id.image_setting_input)
                if (parameter?.type == ImageParameterType.INTEGER)
                    input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                input.hint = emptyLabel
                input.setText(selected.orEmpty())
                input.setOnFocusChangeListener { _, focused ->
                    if (!focused) {
                        val entered = input.text.toString().trim()
                        if ((entered.isBlank() && setting.allowsAutomatic) || parameter!!.accepts(entered)) {
                            input.error = null
                            val newValue = entered.takeIf { it.isNotBlank() }
                            if (newValue != selected) save(parameter!!, newValue)
                        } else input.error = getString(R.string.image_gen_setting_invalid)
                    }
                }
            }
        }
        if (setting.control == ImageSettingControl.BOOLEAN &&
            setting.availability == ImageSettingAvailability.UNSUPPORTED_VALUE && selected != null) {
            previous.text = getString(R.string.image_gen_setting_previous, selected)
            previous.visibility = View.VISIBLE
        }
        val explanation = when (setting.availability) {
            ImageSettingAvailability.UNSUPPORTED_PARAMETER -> getString(R.string.image_gen_setting_unsupported_parameter)
            ImageSettingAvailability.UNSUPPORTED_VALUE -> getString(R.string.image_gen_setting_unsupported_value)
            ImageSettingAvailability.UNVERIFIED -> getString(R.string.image_gen_setting_unverified_detail)
            ImageSettingAvailability.SUPPORTED -> when {
                setting.control == ImageSettingControl.BOOLEAN -> getString(when {
                    !setting.allowsAutomatic -> R.string.image_gen_setting_boolean_required
                    selected == null -> R.string.image_gen_setting_boolean_automatic
                    else -> R.string.image_gen_setting_boolean_explicit
                })
                parameter?.minimum != null && parameter.maximum != null ->
                    getString(R.string.image_gen_setting_range, parameter.minimum.toString(), parameter.maximum.toString())
                parameter?.minimum != null -> getString(R.string.image_gen_setting_minimum, parameter.minimum.toString())
                parameter?.maximum != null -> getString(R.string.image_gen_setting_maximum, parameter.maximum.toString())
                else -> null
            }
        }
        if (explanation != null) {
            subtitle.text = explanation
            subtitle.visibility = View.VISIBLE
        }
        parent.addView(row)
    }

    private fun openEndpointPicker() = endpointLauncher.launch(Intent(this, ApiEndpointsListActivity::class.java))

    private fun openModelChooser() {
        currentFocus?.clearFocus()
        val endpointId = preferences.getImageGeneratorEndpointId()
        if (endpointId.isBlank()) { openEndpointPicker(); return }
        val dialog = AdvancedModelSelectorDialogFragment.newInstance(preferences.getImageGeneratorModel(), "", endpointId, imageModels = true)
        dialog.setModelSelectedListener { model -> preferences.setImageGeneratorModel(model); refresh() }
        dialog.show(supportFragmentManager, "ImageGeneratorModelSelector")
    }

    override fun onPause() { currentFocus?.clearFocus(); super.onPause() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        val insets = window.decorView.rootWindowInsets ?: return
        val header = findViewById<View>(R.id.action_bar)
        header.setPadding(header.paddingLeft, insets.getInsets(WindowInsets.Type.statusBars()).top, header.paddingRight, header.paddingBottom)
        val scroll = findViewById<ScrollView>(R.id.scroll)
        scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight,
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom + resources.getDimensionPixelSize(R.dimen.image_settings_bottom_padding))
    }
}
