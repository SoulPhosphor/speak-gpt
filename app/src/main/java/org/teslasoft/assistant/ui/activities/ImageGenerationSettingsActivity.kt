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
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.imagegen.ImageGenerationMigration
import org.teslasoft.assistant.imagegen.ImageQuality
import org.teslasoft.assistant.imagegen.ImageShape
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment
import org.teslasoft.assistant.ui.util.EndpointProfileDropdown

/**
 * Image Generation settings (image-generation-rebuild-plan.md §5): the
 * app-wide configuration behind the Images row. Every row saves as it is
 * changed, following the Summarizer Settings interaction pattern: the
 * Image Service row opens the saved-profile dropdown, the Image
 * Model row opens the shared searchable model picker fed by the chosen
 * endpoint (in image mode, without the chat picker's name exclusions), and
 * the Ask Before Creating row is visible only while Let the AI Create
 * Images is enabled.
 */
class ImageGenerationSettingsActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var switchAiCreateImages: MaterialSwitch? = null
    private var rowAskBeforeCreating: LinearLayout? = null
    private var switchAskBeforeCreating: MaterialSwitch? = null
    private var rowImageService: LinearLayout? = null
    private var textImageServiceValue: TextView? = null
    private var rowImageModel: LinearLayout? = null
    private var textImageModelValue: TextView? = null
    private var rowDefaultShape: LinearLayout? = null
    private var rowDefaultQuality: LinearLayout? = null
    private var textDefaultShape: TextView? = null
    private var textDefaultQuality: TextView? = null
    private var switchImagineCommand: MaterialSwitch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_image_generation_settings)

        // Defensive: idempotent, normally already done at app start (§14).
        ImageGenerationMigration.runIfNeeded(this)

        preferences = Preferences.getPreferences(this, "")
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)

        bindViews()
        applyTheme()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        switchAiCreateImages = findViewById(R.id.switch_ai_create_images)
        rowAskBeforeCreating = findViewById(R.id.row_ask_before_creating)
        switchAskBeforeCreating = findViewById(R.id.switch_ask_before_creating)
        rowImageService = findViewById(R.id.row_image_service)
        textImageServiceValue = findViewById(R.id.text_image_service_value)
        rowImageModel = findViewById(R.id.row_image_model)
        textImageModelValue = findViewById(R.id.text_image_model_value)
        rowDefaultShape = findViewById(R.id.row_default_shape)
        rowDefaultQuality = findViewById(R.id.row_default_quality)
        textDefaultShape = findViewById(R.id.text_default_shape)
        textDefaultQuality = findViewById(R.id.text_default_quality)
        switchImagineCommand = findViewById(R.id.switch_imagine_command)
    }

    private fun applyTheme() {
        window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
        if (Build.VERSION.SDK_INT <= 34) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
            @Suppress("DEPRECATION")
            window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
        }
        actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
        btnBack?.backgroundTintList =
            ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        switchAiCreateImages?.isChecked = preferences?.getAiCreateImagesEnabled() ?: false
        refreshAskBeforeVisibility()
        switchAiCreateImages?.setOnCheckedChangeListener { _, checked ->
            preferences?.setAiCreateImagesEnabled(checked)
            refreshAskBeforeVisibility()
        }

        switchAskBeforeCreating?.isChecked = preferences?.getAskBeforeAiImages() ?: true
        switchAskBeforeCreating?.setOnCheckedChangeListener { _, checked ->
            preferences?.setAskBeforeAiImages(checked)
        }

        refreshServiceAndModelRows()
        rowImageService?.setOnClickListener { showEndpointDropdown() }
        textImageServiceValue?.setOnClickListener { showEndpointDropdown() }
        rowImageModel?.setOnClickListener { openModelChooser() }
        textImageModelValue?.setOnClickListener { openModelChooser() }

        refreshShapeAndQuality()
        // The Dropdown.Value style makes the value clickable, so it consumes
        // taps instead of passing them to the row — it needs its own listener.
        textDefaultShape?.setOnClickListener { showShapeDropdown(it) }
        textDefaultQuality?.setOnClickListener { showQualityDropdown(it) }

        switchImagineCommand?.isChecked = preferences?.getImagineCommandGlobal() ?: true
        switchImagineCommand?.setOnCheckedChangeListener { _, checked ->
            preferences?.setImagineCommandGlobal(checked)
        }
    }

    private fun refreshAskBeforeVisibility() {
        rowAskBeforeCreating?.visibility =
            if (switchAiCreateImages?.isChecked == true) View.VISIBLE else View.GONE
    }

    private fun refreshServiceAndModelRows() {
        val endpointId = preferences?.getImageGeneratorEndpointId().orEmpty()
        textImageServiceValue?.text = if (endpointId.isEmpty()) {
            getString(R.string.label_endpoint_none)
        } else {
            val endpoints = apiEndpointPreferences?.getApiEndpointsList(this) ?: arrayListOf()
            val label = endpoints.firstOrNull { it.id == endpointId }?.label
            if (!label.isNullOrEmpty()) label else getString(R.string.label_endpoint_none)
        }

        val model = preferences?.getImageGeneratorModel().orEmpty()
        textImageModelValue?.text = model.ifEmpty { getString(R.string.label_endpoint_none) }
    }

    private fun showEndpointDropdown() {
        val anchor = textImageServiceValue ?: return
        val endpoints = apiEndpointPreferences?.getApiEndpointsList(this) ?: return
        EndpointProfileDropdown.show(this, anchor, endpoints) { endpointId ->
            preferences?.setImageGeneratorEndpointId(endpointId)
            refreshServiceAndModelRows()
            refreshShapeAndQuality()
        }
    }

    /** The shared searchable model picker in image mode: the provider's
     *  list is fetched from the generator endpoint, image-output capability
     *  information narrows it when the catalog carries any, and the chat
     *  picker's name exclusions do not apply (§5 row 4 / §10). */
    private fun openModelChooser() {
        val endpointId = preferences?.getImageGeneratorEndpointId().orEmpty()
        if (endpointId.isEmpty()) {
            showEndpointDropdown()
            return
        }

        val current = preferences?.getImageGeneratorModel().orEmpty()
        val dialog = AdvancedModelSelectorDialogFragment.newInstance(
            current, "", endpointId, imageModels = true
        )
        dialog.setModelSelectedListener { model ->
            preferences?.setImageGeneratorModel(model)
            refreshServiceAndModelRows()
        }
        dialog.show(supportFragmentManager, "ImageGeneratorModelSelector")
    }

    /* ------------------------------ Shape and quality ------------------------------ */

    private fun shapeLabel(shape: ImageShape): String = when (shape) {
        ImageShape.AUTOMATIC -> getString(R.string.image_gen_option_automatic)
        ImageShape.SQUARE -> getString(R.string.image_gen_shape_square)
        ImageShape.PORTRAIT -> getString(R.string.image_gen_shape_portrait)
        ImageShape.LANDSCAPE -> getString(R.string.image_gen_shape_landscape)
    }

    private fun qualityLabel(quality: ImageQuality): String = when (quality) {
        ImageQuality.AUTOMATIC -> getString(R.string.image_gen_option_automatic)
        ImageQuality.LOW -> getString(R.string.image_gen_quality_low)
        ImageQuality.MEDIUM -> getString(R.string.image_gen_quality_medium)
        ImageQuality.HIGH -> getString(R.string.image_gen_quality_high)
    }

    private fun refreshShapeAndQuality() {
        textDefaultShape?.text =
            shapeLabel(preferences?.getImageGeneratorShape() ?: ImageShape.AUTOMATIC)
        textDefaultQuality?.text =
            qualityLabel(preferences?.getImageGeneratorQuality() ?: ImageQuality.AUTOMATIC)

        // §5: never expose choices the selected service's API cannot carry
        // at all. With no endpoint chosen the rows stay visible showing the
        // saved defaults.
        val endpointId = preferences?.getImageGeneratorEndpointId().orEmpty()
        val capabilities = if (endpointId.isEmpty()) null else {
            org.teslasoft.assistant.imagegen.ImageProviderAdapters.forEndpoint(
                apiEndpointPreferences!!.getApiEndpoint(this, endpointId)
            ).capabilities
        }
        rowDefaultShape?.visibility =
            if (capabilities?.supportsShape == false) View.GONE else View.VISIBLE
        rowDefaultQuality?.visibility =
            if (capabilities?.supportsQuality == false) View.GONE else View.VISIBLE
    }

    private fun showShapeDropdown(anchor: View) {
        val options = ImageShape.entries
        showDropdown(anchor, options.map { shapeLabel(it) }) { position ->
            preferences?.setImageGeneratorShape(options[position])
            refreshShapeAndQuality()
        }
    }

    private fun showQualityDropdown(anchor: View) {
        val options = ImageQuality.entries
        showDropdown(anchor, options.map { qualityLabel(it) }) { position ->
            preferences?.setImageGeneratorQuality(options[position])
            refreshShapeAndQuality()
        }
    }

    private fun showDropdown(anchor: View, labels: List<String>, onPicked: (Int) -> Unit) {
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onPicked(position)
        }
        popup.show()
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
            val density = resources.displayMetrics.density
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom +
                    (24 * density).toInt()
            )
        } catch (_: Exception) { /* unused */ }
    }
}
