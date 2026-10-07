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
        options.removeAllViews()
        retry.visibility = View.GONE
        status.visibility = View.GONE
        if (endpointId.isBlank() || modelId.isBlank()) return
        status.visibility = View.VISIBLE
        status.setText(R.string.image_gen_settings_loading)
        metadataJob = lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                runCatching { ImageCatalogClient.model(endpoint, modelId, fresh = true) }.getOrNull()
            }
            if (metadata == null) {
                status.setText(R.string.image_gen_settings_unavailable)
                retry.visibility = View.VISIBLE
            } else {
                status.setText(R.string.image_gen_settings_automatic)
                val legacy = ImageGenerationRequest("", ImageShape.AUTOMATIC, ImageQuality.AUTOMATIC,
                    endpointId, modelId, parameters = preferences.getImageGeneratorParameters(),
                    defaultShape = preferences.getImageGeneratorShape(), defaultQuality = preferences.getImageGeneratorQuality())
                runCatching { ImageRequestOptions.resolve(legacy, metadata) }.getOrNull()?.let { resolved ->
                    preferences.setImageGeneratorParameters(resolved)
                    preferences.setImageGeneratorShape(ImageShape.AUTOMATIC)
                    preferences.setImageGeneratorQuality(ImageQuality.AUTOMATIC)
                }
                metadata.parameters.forEach { addSetting(options, it) }
            }
        }
    }

    private fun label(key: String): String {
        val resource = when (key) {
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

    private fun save(parameter: ImageParameter, value: String?) {
        val selected = preferences.getImageGeneratorParameters().toMutableMap()
        if (value == null) selected.remove(parameter.key) else selected[parameter.key] = value
        preferences.setImageGeneratorParameters(selected)
        // These controls replace the former global shape/quality defaults. Explicit /imagine
        // and tool overrides remain independent and are validated for the selected model.
        if (parameter.key in listOf("size", "resolution", "aspect_ratio")) preferences.setImageGeneratorShape(ImageShape.AUTOMATIC)
        if (parameter.key == "quality") preferences.setImageGeneratorQuality(ImageQuality.AUTOMATIC)
    }

    private fun addSetting(parent: LinearLayout, parameter: ImageParameter) {
        val selected = preferences.getImageGeneratorParameters()[parameter.key]
        if (parameter.type == ImageParameterType.ENUM || parameter.type == ImageParameterType.BOOLEAN) {
            val row = layoutInflater.inflate(R.layout.view_image_setting_dropdown, parent, false)
            row.findViewById<TextView>(R.id.image_setting_label).text = label(parameter.key)
            val value = row.findViewById<TextView>(R.id.image_setting_value)
            val choices = parameter.values.ifEmpty { listOf("true", "false") }
            val labels = listOf(getString(R.string.image_gen_option_automatic)) + choices
            value.text = selected ?: labels.first()
            value.setOnClickListener {
                AppDropdown.show(value, labels, if (preferences.getImageGeneratorParameters()[parameter.key] == null) 0 else
                    choices.indexOf(value.text.toString()).let { if (it < 0) -1 else it + 1 }) { index ->
                    val picked = choices.getOrNull(index - 1)
                    save(parameter, picked)
                    value.text = picked ?: labels.first()
                }
            }
            parent.addView(row)
        } else {
            val row = layoutInflater.inflate(if (parameter.type == ImageParameterType.STRING)
                R.layout.view_image_setting_text else R.layout.view_image_setting_number, parent, false)
            row.findViewById<TextView>(R.id.image_setting_label).text = label(parameter.key)
            val input = row.findViewById<EditText>(R.id.image_setting_input)
            input.hint = getString(R.string.image_gen_option_automatic)
            input.setText(selected.orEmpty())
            input.setOnFocusChangeListener { _, focused ->
                if (!focused) {
                    val entered = input.text.toString().trim()
                    if (entered.isBlank() || parameter.accepts(entered)) {
                        input.error = null
                        save(parameter, entered.takeIf { it.isNotBlank() })
                    } else input.error = getString(R.string.image_gen_setting_invalid)
                }
            }
            parent.addView(row)
        }
    }

    private fun openEndpointPicker() = endpointLauncher.launch(Intent(this, ApiEndpointsListActivity::class.java))

    private fun openModelChooser() {
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
