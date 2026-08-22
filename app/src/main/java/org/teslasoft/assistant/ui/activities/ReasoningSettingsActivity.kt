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

import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.reasoning.EndpointReasoningCapability
import org.teslasoft.assistant.reasoning.ReasoningCapability
import org.teslasoft.assistant.reasoning.ReasoningEffort
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.reasoning.ReasoningEffortLabels
import org.teslasoft.assistant.ui.util.DiscardChangesDialog

/**
 * The favorite model's dedicated Reasoning Settings screen (chat-redesign-plan.md
 * §7.4). Full-screen header pattern with a back action on the left and a single
 * Save on the upper right. It contains only the controls the active
 * model/provider combination actually supports:
 *
 *  1. **Thinking** — a dropdown of the supported reasoning-effort levels (plus
 *     Auto, and Off where disabling is supported). Hidden when effort is not
 *     configurable.
 *  2. **Show Reasoning** — an On/Off toggle for whether available provider
 *     reasoning is requested/returned for display. Hidden when the path cannot
 *     return visible reasoning.
 *
 * The values are the favorite's saved default reasoning behavior; they are not
 * provider-routing settings. Saving is explicit and in-place: it persists to the
 * favorite, briefly greens the Save icon and shows a "Saved" toast, and clears
 * the dirty state so Back exits normally. Leaving with unsaved changes uses the
 * app's standard unsaved-changes confirmation.
 */
class ReasoningSettingsActivity : FragmentActivity() {

    companion object {
        const val EXTRA_MODEL_ID = "modelId"
        const val EXTRA_ENDPOINT_ID = "endpointId"

        fun createIntent(context: Context, modelId: String, endpointId: String): Intent =
            Intent(context, ReasoningSettingsActivity::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_ENDPOINT_ID, endpointId)
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var rowThinking: View? = null
    private var textThinkingValue: TextView? = null
    private var rowShowReasoning: View? = null
    private var switchShowReasoning: MaterialSwitch? = null

    private var modelId: String = ""
    private var endpointId: String = ""
    private var favorite: FavoriteModelObject = FavoriteModelObject("", "")
    private var capability: ReasoningCapability = ReasoningCapability.UNKNOWN

    private var selectedEffort: ReasoningEffort = ReasoningEffort.AUTO
    private var showReasoning: Boolean = true
    private var initialSnapshot: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_reasoning_settings)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        rowThinking = findViewById(R.id.row_thinking)
        textThinkingValue = findViewById(R.id.text_thinking_value)
        rowShowReasoning = findViewById(R.id.row_show_reasoning)
        switchShowReasoning = findViewById(R.id.switch_show_reasoning)

        applyChrome()

        modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty()
        endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID).orEmpty()

        val favPrefs = FavoriteModelsPreferences.getPreferences(this)
        favorite = favPrefs.getFavorite(modelId, endpointId)
            ?: FavoriteModelObject(modelId, endpointId)

        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(this)
            .getApiEndpoint(this, endpointId)
        capability = EndpointReasoningCapability.resolveWithLearnedRejections(
            endpoint.reasoningCapabilityByModel,
            endpoint.reasoningRejectedLevelsByModel,
            modelId,
            providerPath = org.teslasoft.assistant.reasoning.ReasoningProviderPath.forEndpoint(
                endpoint.host,
                endpoint.isOpenRouterRouting()
            )
        )

        // Start from the favorite's saved values; a saved effort the active path
        // no longer supports resolves to Auto for display (§7.8).
        selectedEffort = ReasoningEffort.fromSerialized(favorite.reasoningEffort)
            ?.takeIf { capability.supports(it) }
            ?: ReasoningEffort.AUTO
        showReasoning = favorite.showReasoning

        configureRows()
        initialSnapshot = snapshot()

        btnBack?.setOnClickListener { attemptExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { attemptExit() }
        })
        btnSave?.setOnClickListener { save() }
    }

    private fun configureRows() {
        // Thinking dropdown when the combination exposes configurable effort;
        // a non-interactive "Fixed" when the model reasons but has no adjustable
        // level (owner ruling, Aug 2026); hidden only when the path is not a
        // reasoning model at all.
        if (capability.effortConfigurable && capability.thinkingChoices().isNotEmpty()) {
            rowThinking?.visibility = View.VISIBLE
            rowThinking?.isEnabled = true
            rowThinking?.alpha = 1f
            refreshThinkingValue()
            rowThinking?.setOnClickListener { showThinkingDropdown() }
        } else if (capability.isReasoningCapable) {
            rowThinking?.visibility = View.VISIBLE
            textThinkingValue?.text = getString(R.string.reasoning_effort_fixed)
            rowThinking?.isEnabled = false
            rowThinking?.alpha = 0.5f
            rowThinking?.setOnClickListener(null)
        } else {
            rowThinking?.visibility = View.GONE
            rowThinking?.setOnClickListener(null)
        }

        // Show Reasoning only when the path can actually return visible reasoning.
        if (capability.canReturnVisibleReasoning) {
            rowShowReasoning?.visibility = View.VISIBLE
            switchShowReasoning?.isChecked = showReasoning
            switchShowReasoning?.setOnCheckedChangeListener { _, checked ->
                showReasoning = checked
                onEdited()
            }
        } else {
            rowShowReasoning?.visibility = View.GONE
        }
    }

    private fun refreshThinkingValue() {
        textThinkingValue?.text = ReasoningEffortLabels.label(this, selectedEffort)
    }

    private fun showThinkingDropdown() {
        val choices = capability.thinkingChoices()
        if (choices.isEmpty()) return
        val labels = choices.map { ReasoningEffortLabels.label(this, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.reasoning_thinking_label)
            .setItems(labels) { _, which ->
                selectedEffort = choices[which]
                refreshThinkingValue()
                onEdited()
            }
            .show()
    }

    /** Any control change marks the screen dirty and clears a prior green Save
     *  confirmation, so green only ever means "just saved". */
    private fun onEdited() {
        resetSaveButtonTint()
    }

    private fun snapshot(): String = "${selectedEffort.serialized}|$showReasoning"

    private fun save() {
        favorite.reasoningEffort = selectedEffort.serialized
        favorite.showReasoning = showReasoning
        FavoriteModelsPreferences.getPreferences(this).addFavoriteModel(favorite)
        initialSnapshot = snapshot()
        markSaveButtonGreen()
        Toast.makeText(this, R.string.reasoning_settings_saved_toast, Toast.LENGTH_SHORT).show()
    }

    private fun attemptExit() {
        if (snapshot() != initialSnapshot) {
            DiscardChangesDialog.show(this) { finish() }
        } else {
            finish()
        }
    }

    private fun markSaveButtonGreen() {
        btnSave?.backgroundTintList =
            ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.light_green, theme))
    }

    private fun resetSaveButtonTint() {
        btnSave?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
    }

    /** Standard surface chrome for the header and window. AMOLED work is paused
     *  (project rules), so this new screen deliberately adds no AMOLED-specific
     *  styling; it themes through the normal palette. */
    @Suppress("DEPRECATION")
    private fun applyChrome() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(SurfaceColors.SURFACE_0.getColor(this)))
        if (Build.VERSION.SDK_INT <= 34) {
            window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
            window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
        }
        actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
        val barTint = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        btnBack?.backgroundTintList = barTint
        btnSave?.backgroundTintList = barTint
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val insets = window.decorView.rootWindowInsets ?: return
            actionBar?.setPadding(0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0)
            val navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            findViewById<ScrollView>(R.id.scroll)?.setPadding(0, 0, 0, navBottom)
        } catch (_: Exception) { /* best-effort inset padding */ }
    }
}
