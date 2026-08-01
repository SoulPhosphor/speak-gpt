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
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.fragments.dialogs.AdvancedModelSelectorDialogFragment

/**
 * Choose Provider screen (OpenRouter). Opened from the Choose Provider row on
 * [ApiEndpointEditorActivity] for an endpoint whose Base URL is an OpenRouter
 * endpoint.
 *
 * The screen sets a preferred model-provider routing type (Automatic /
 * Preferred / Only, defaulting to Automatic), lets the user pick or change the
 * model (the SAME model the editor's Model box sets — chosen either place), and
 * offers a Make Favorite toggle. Favoriting is what makes the routing choice
 * permanent: the routing memory rides on the favorite, so removing the favorite
 * later removes it too (owner ruling).
 *
 * This screen does not write anything to disk itself. Its Save icon returns the
 * chosen model, routing type and favorite flag to the editor, which applies
 * them when the endpoint profile is saved — one save path, and it works whether
 * the endpoint already exists or is being created. Back / cancel returns
 * nothing.
 */
class ChooseProviderActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ENDPOINT_ID = "endpointId"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ROUTING_TYPE = "routingType"
        const val EXTRA_MAKE_FAVORITE = "makeFavorite"

        private val routingTypes = arrayOf(
            FavoriteModelObject.ROUTING_AUTOMATIC,
            FavoriteModelObject.ROUTING_PREFERRED,
            FavoriteModelObject.ROUTING_ONLY
        )
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

    private var endpointId: String = ""
    private var selectedModel: String = ""
    private var selectedRoutingType: String = FavoriteModelObject.ROUTING_AUTOMATIC

    /** This endpoint's favorite model ids, for the Choose Model quick-pick. */
    private var favorites: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_choose_provider)

        preferences = Preferences.getPreferences(this, "")
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(this)

        endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID) ?: ""
        selectedModel = intent.getStringExtra(EXTRA_MODEL) ?: ""
        selectedRoutingType = (intent.getStringExtra(EXTRA_ROUTING_TYPE) ?: FavoriteModelObject.ROUTING_AUTOMATIC)
            .takeIf { it in routingTypes } ?: FavoriteModelObject.ROUTING_AUTOMATIC

        bindViews()
        applyTheme()
        loadValues()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        fieldRoutingType = findViewById(R.id.field_routing_type)
        fieldChooseModel = findViewById(R.id.field_choose_model)
        btnViewAllModels = findViewById(R.id.btn_view_all_models)
        switchMakeFavorite = findViewById(R.id.switch_make_favorite)
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
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { cancelAndFinish() }
        btnSave?.setOnClickListener { saveAndFinish() }

        fieldRoutingType?.setOnClickListener { showRoutingChooser() }
        fieldChooseModel?.setOnClickListener { showModelChooser() }
        btnViewAllModels?.setOnClickListener { openFullModelPicker() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancelAndFinish() }
        })
    }

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
                selectedModel = if (which == 0) "" else favorites[which - 1]
                updateModelBox()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun openFullModelPicker() {
        val modelDialog = AdvancedModelSelectorDialogFragment.newInstance(selectedModel, "", endpointId)
        modelDialog.setModelSelectedListener { model ->
            selectedModel = model
            updateModelBox()
        }
        modelDialog.show(supportFragmentManager, "ChooseProviderModelSelector")
    }

    private fun updateModelBox() {
        val text = when {
            selectedModel.isNotBlank() -> selectedModel
            favorites.isEmpty() -> getString(R.string.choose_provider_model_none_available)
            else -> getString(R.string.choose_provider_model_use_none)
        }
        fieldChooseModel?.setText(text)
    }

    private fun saveAndFinish() {
        val data = Intent()
        data.putExtra(EXTRA_MODEL, selectedModel)
        data.putExtra(EXTRA_ROUTING_TYPE, selectedRoutingType)
        data.putExtra(EXTRA_MAKE_FAVORITE, switchMakeFavorite?.isChecked == true)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
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
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }
}
