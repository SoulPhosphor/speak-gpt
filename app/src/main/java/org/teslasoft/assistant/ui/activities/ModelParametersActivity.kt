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
import androidx.fragment.app.FragmentActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.ui.util.SaveIconFlash
import org.teslasoft.assistant.preferences.FavoriteModelParameters
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.ModelPickerScope
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
 *
 * Opened for a Summarizer model, it edits that model's settings in the
 * Summarizer's own lists, and Restore Default Settings returns it to the
 * defaults on Summarizer Settings.
 */
class ModelParametersActivity : FragmentActivity() {

    companion object {
        const val EXTRA_MODEL_ID = "modelId"
        const val EXTRA_ENDPOINT_ID = "endpointId"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_SCOPE = "scope"

        fun createIntent(
            context: Context, modelId: String, endpointId: String, chatId: String,
            scope: ModelPickerScope = ModelPickerScope.CHAT
        ): Intent =
            Intent(context, ModelParametersActivity::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_ENDPOINT_ID, endpointId)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_SCOPE, scope.name)
    }

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var btnRestoreDefaults: ImageButton? = null
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
    /** Chat models, or the Summarizer's (whose defaults come from Summarizer
     *  Settings and which never touch a chat). */
    private var scope = ModelPickerScope.CHAT

    private var streaming: Boolean = true
    private var initialSnapshot: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_model_parameters)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        btnRestoreDefaults = findViewById(R.id.btn_restore_defaults)
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
        scope = ModelPickerScope.from(intent.getStringExtra(EXTRA_SCOPE))

        textModel?.text = modelId

        seedControls(FavoriteModelParameters.startingParameters(this, modelId, endpointId, scope))
        initialSnapshot = snapshot()

        btnBack?.setOnClickListener { attemptExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { attemptExit() }
        })
        btnSave?.setOnClickListener { save() }
        btnRestoreDefaults?.setOnClickListener { confirmRestoreDefaults() }
    }

    /** The chat this screen was opened from, when it is the model being edited. */
    private fun currentChatPreferencesIfActiveModel(): Preferences? {
        if (chatId.isEmpty() || scope != ModelPickerScope.CHAT) return null
        val prefs = Preferences.getPreferences(this, chatId)
        return if (prefs.getModel() == modelId && prefs.getApiEndpointId() == endpointId) prefs else null
    }

    /**
     * The controls start with what the model starts with in a chat: its saved
     * parameters (on its favorite, or on its Recent entry when it is not a
     * favorite), otherwise its API profile's defaults.
     */
    private fun seedControls(values: FavoriteModelObject) {
        val defaults = FavoriteModelParameters.defaults(this, modelId, endpointId, scope)

        streaming = values.streaming ?: defaults.streaming ?: true
        checkStreaming?.isChecked = streaming
        rowStreaming?.setOnClickListener {
            streaming = checkStreaming?.isChecked != true
            checkStreaming?.isChecked = streaming
            onEdited()
        }

        temperatureControl?.configure(
            SamplingParameterSpec.TEMPERATURE,
            values.temperature ?: defaults.temperature ?: ApiEndpointObject.DEFAULT_TEMPERATURE
        ) { onEdited() }
        topPControl?.configure(
            SamplingParameterSpec.TOP_P,
            values.topP ?: defaults.topP ?: ApiEndpointObject.DEFAULT_TOP_P
        ) { onEdited() }
        frequencyPenaltyControl?.configure(
            SamplingParameterSpec.FREQUENCY_PENALTY,
            values.frequencyPenalty ?: defaults.frequencyPenalty ?: ApiEndpointObject.DEFAULT_FREQUENCY_PENALTY
        ) { onEdited() }
        presencePenaltyControl?.configure(
            SamplingParameterSpec.PRESENCE_PENALTY,
            values.presencePenalty ?: defaults.presencePenalty ?: ApiEndpointObject.DEFAULT_PRESENCE_PENALTY
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
        val values = FavoriteModelObject(modelId, endpointId).apply {
            streaming = this@ModelParametersActivity.streaming
            temperature = temperatureControl?.value
            topP = topPControl?.value
            frequencyPenalty = frequencyPenaltyControl?.value
            presencePenalty = presencePenaltyControl?.value
        }
        // On the favorite when the model is one, otherwise on its Recent entry.
        FavoriteModelParameters.save(this, modelId, endpointId, values, scope)

        // When this is the chat's active model, the saved parameters take effect
        // in that chat right away rather than waiting for a re-selection.
        currentChatPreferencesIfActiveModel()?.let {
            FavoriteModelParameters.applyToChat(this, it, modelId, endpointId)
        }

        initialSnapshot = snapshot()
        markSaveButtonGreen()
        Toast.makeText(this, R.string.model_parameters_saved_toast, Toast.LENGTH_SHORT).show()
    }

    /** Restore Default Settings: after Okay, this model's saved parameters are
     *  removed so it uses its API profile's defaults again, the controls show
     *  those defaults, and the icon itself turns green like Save does. */
    private fun confirmRestoreDefaults() {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.model_parameters_restore_title)
            .setMessage(
                if (scope == ModelPickerScope.SUMMARIZER) R.string.model_parameters_restore_message_summarizer
                else R.string.model_parameters_restore_message
            )
            .setView(actionsView)
            .create()
        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                dialog.dismiss()
                restoreDefaults()
            }
        }
        dialog.show()
    }

    private fun restoreDefaults() {
        if (scope == ModelPickerScope.SUMMARIZER) {
            // A Summarizer model goes back to the recommended settings, never
            // to an API profile's.
            val recommended = FavoriteModelParameters.summarizerRecommended(modelId, endpointId)
            FavoriteModelParameters.save(this, modelId, endpointId, recommended, scope)
            seedControls(recommended)
        } else {
            FavoriteModelParameters.restoreDefaults(this, modelId, endpointId, scope)
            currentChatPreferencesIfActiveModel()?.let {
                FavoriteModelParameters.applyToChat(this, it, modelId, endpointId)
            }
            seedControls(FavoriteModelParameters.defaults(this, modelId, endpointId, scope))
        }
        initialSnapshot = snapshot()
        resetSaveButtonTint()
        btnRestoreDefaults?.let { SaveIconFlash.flash(it) }
        Toast.makeText(this, R.string.model_parameters_restore_done, Toast.LENGTH_SHORT).show()
    }

    private fun attemptExit() {
        if (snapshot() != initialSnapshot) {
            DiscardChangesDialog.show(this) { finish() }
        } else {
            finish()
        }
    }

    private fun markSaveButtonGreen() {
        btnSave?.let { SaveIconFlash.markSaved(it) }
    }

    private fun resetSaveButtonTint() {
        btnSave?.let { SaveIconFlash.reset(it) }
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
