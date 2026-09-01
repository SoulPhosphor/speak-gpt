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
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.ui.widgets.SamplingParameterControl
import org.teslasoft.assistant.ui.widgets.SamplingParameterSpec

/**
 * A favorite model's dedicated Model Parameters screen. Same full-screen header
 * pattern as Reasoning Settings: a back action on the left and a single Save
 * (the disc icon) on the upper right, with the model's name shown at the top so
 * it is clear which model is being configured.
 *
 * It edits this favorite's saved sampling parameters — Streaming and the four
 * sampling sliders — in the order they appear in Quick Settings. The parameters
 * belong to the model, so saving stores them on the favorite and, when this is
 * also the current chat's active model, applies them to that chat immediately;
 * otherwise they take effect the next time the model is selected. Saving is
 * explicit and in-place: it persists, briefly greens the Save icon and shows a
 * "Saved" toast, and clears the dirty state so Back exits normally. Leaving with
 * unsaved changes uses the app's standard unsaved-changes confirmation.
 */
class ModelParametersActivity : FragmentActivity() {

    companion object {
        const val EXTRA_MODEL_ID = "modelId"
        const val EXTRA_ENDPOINT_ID = "endpointId"
        const val EXTRA_CHAT_ID = "chatId"

        fun createIntent(context: Context, modelId: String, endpointId: String, chatId: String): Intent =
            Intent(context, ModelParametersActivity::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_ENDPOINT_ID, endpointId)
                .putExtra(EXTRA_CHAT_ID, chatId)
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var textModel: TextView? = null
    private var checkStreaming: MaterialCheckBox? = null
    private var rowStreaming: android.view.View? = null
    private var temperatureControl: SamplingParameterControl? = null
    private var topPControl: SamplingParameterControl? = null
    private var frequencyPenaltyControl: SamplingParameterControl? = null
    private var presencePenaltyControl: SamplingParameterControl? = null

    private var modelId: String = ""
    private var endpointId: String = ""
    private var chatId: String = ""
    private var favorite: FavoriteModelObject = FavoriteModelObject("", "")

    private var streaming: Boolean = true
    private var initialSnapshot: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_model_parameters)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        textModel = findViewById(R.id.text_model)
        checkStreaming = findViewById(R.id.check_streaming)
        rowStreaming = findViewById(R.id.row_streaming)
        temperatureControl = findViewById(R.id.temperature_slider)
        topPControl = findViewById(R.id.top_p_slider)
        frequencyPenaltyControl = findViewById(R.id.frequency_penalty_slider)
        presencePenaltyControl = findViewById(R.id.presence_penalty_slider)

        applyChrome()

        modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty()
        endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID).orEmpty()
        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()

        textModel?.text = modelId

        favorite = FavoriteModelsPreferences.getPreferences(this).getFavorite(modelId, endpointId)
            ?: FavoriteModelObject(modelId, endpointId)

        seedControls()
        initialSnapshot = snapshot()

        btnBack?.setOnClickListener { attemptExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { attemptExit() }
        })
        btnSave?.setOnClickListener { save() }
    }

    /** The chat this screen was opened from, when it is the model being edited. */
    private fun currentChatPreferencesIfActiveModel(): Preferences? {
        if (chatId.isEmpty()) return null
        val prefs = Preferences.getPreferences(this, chatId)
        return if (prefs.getModel() == modelId && prefs.getApiEndpointId() == endpointId) prefs else null
    }

    /**
     * Initial values come from the favorite's own saved parameters when it has
     * them. Otherwise, if this is the chat's active model, they start from that
     * chat's current values (so opening the model you are using shows what you
     * are using); failing both, the app's default parameters.
     */
    private fun seedControls() {
        val chatPrefs = currentChatPreferencesIfActiveModel()

        streaming = favorite.streaming ?: chatPrefs?.getStreaming() ?: true
        checkStreaming?.isChecked = streaming
        rowStreaming?.setOnClickListener {
            streaming = checkStreaming?.isChecked != true
            checkStreaming?.isChecked = streaming
            onEdited()
        }

        temperatureControl?.configure(
            SamplingParameterSpec.TEMPERATURE,
            favorite.temperature ?: chatPrefs?.getTemperature() ?: ApiEndpointObject.DEFAULT_TEMPERATURE
        ) { onEdited() }
        topPControl?.configure(
            SamplingParameterSpec.TOP_P,
            favorite.topP ?: chatPrefs?.getTopP() ?: ApiEndpointObject.DEFAULT_TOP_P
        ) { onEdited() }
        frequencyPenaltyControl?.configure(
            SamplingParameterSpec.FREQUENCY_PENALTY,
            favorite.frequencyPenalty ?: chatPrefs?.getFrequencyPenalty() ?: ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY
        ) { onEdited() }
        presencePenaltyControl?.configure(
            SamplingParameterSpec.PRESENCE_PENALTY,
            favorite.presencePenalty ?: chatPrefs?.getPresencePenalty() ?: ApiEndpointObject.DEFAULT_PRESENCE_PENALTY
        ) { onEdited() }
    }

    /** Any control change marks the screen dirty and clears a prior green Save
     *  confirmation, so green only ever means "just saved". */
    private fun onEdited() {
        resetSaveButtonTint()
    }

    private fun snapshot(): String =
        "$streaming|${temperatureControl?.value}|${topPControl?.value}|" +
            "${frequencyPenaltyControl?.value}|${presencePenaltyControl?.value}"

    private fun save() {
        favorite.streaming = streaming
        favorite.temperature = temperatureControl?.value
        favorite.topP = topPControl?.value
        favorite.frequencyPenalty = frequencyPenaltyControl?.value
        favorite.presencePenalty = presencePenaltyControl?.value
        FavoriteModelsPreferences.getPreferences(this).addFavoriteModel(favorite)

        // When this is the chat's active model, the saved parameters take effect
        // in that chat right away rather than waiting for a re-selection.
        currentChatPreferencesIfActiveModel()?.let {
            FavoriteModelParameters.applyToChat(this, it, modelId, endpointId)
        }

        initialSnapshot = snapshot()
        markSaveButtonGreen()
        Toast.makeText(this, R.string.model_parameters_saved_toast, Toast.LENGTH_SHORT).show()
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
