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

import org.teslasoft.assistant.ui.util.ScreenChrome

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.ui.dedicated.DedicatedModelRows
import org.teslasoft.assistant.ui.dedicated.DedicatedParametersSection

/**
 * Summarizer Settings (conversation-summary-plan.md decision 2): the
 * new-chats and resummarize toggles, the Complete Messages and compaction
 * defaults, the Customize Prompt Summaries row, the Summary Model rows
 * (DedicatedModelRows, the Summarizer's own lists and routing) and the
 * Summarizer's parameters (DedicatedParametersSection). Values save as they
 * are changed. Prompts are edited on the Summarizer Prompts screen (owner
 * ruling, Oct 3 2026).
 */
class SummarizerSettingsActivity : SettingsPageActivity() {

    private var preferences: Preferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var textEndpointValue: TextView? = null
    private var btnEditEndpoint: ImageButton? = null
    private var textModelValue: TextView? = null
    private var btnViewAllModels: MaterialButton? = null
    private var sectionRouting: View? = null
    private var textRoutingValue: TextView? = null
    private var fieldCompleteMessages: TextInputEditText? = null
    private var fieldCompactionTokens: TextInputEditText? = null
    private var fieldCompactionMaximumTokens: TextInputEditText? = null
    private var compactionFieldsReady = false
    private var switchNewChats: MaterialSwitch? = null
    private var parametersSection: DedicatedParametersSection? = null

    private var suppressWatchers = false

    private var modelRows: DedicatedModelRows? = null

    private val endpointEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> modelRows?.onEndpointEditorResult(result) }

    private val routingSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> modelRows?.onRoutingSetupResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_summarizer_settings)

        preferences = Preferences.getPreferences(this, "")

        bindViews()
        applyTheme()
        initLogic()

    }

    override fun onResume() {
        super.onResume()
        modelRows?.refresh()
        parametersSection?.reload()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        textEndpointValue = findViewById(R.id.text_summarizer_endpoint_value)
        btnEditEndpoint = findViewById(R.id.btn_edit_summarizer_endpoint)
        textModelValue = findViewById(R.id.text_summarizer_model_value)
        btnViewAllModels = findViewById(R.id.btn_view_all_summarizer_models)
        sectionRouting = findViewById(R.id.section_summarizer_routing)
        textRoutingValue = findViewById(R.id.text_summarizer_routing_value)
        fieldCompleteMessages = findViewById(R.id.field_complete_messages)
        fieldCompactionTokens = findViewById(R.id.field_compaction_tokens)
        fieldCompactionMaximumTokens = findViewById(R.id.field_compaction_maximum_tokens)
        switchNewChats = findViewById(R.id.switch_summarizer_new_chats)
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

        findViewById<View>(R.id.row_summarizer_prompts)?.setOnClickListener {
            startActivity(Intent(this, SummarizerPromptsActivity::class.java))
        }
        val section = DedicatedParametersSection(findViewById(R.id.root), ModelPickerScope.SUMMARIZER, autoSave = true)
        parametersSection = section
        modelRows = DedicatedModelRows(
            this, ModelPickerScope.SUMMARIZER,
            textEndpointValue!!, btnEditEndpoint!!, textModelValue!!, btnViewAllModels!!,
            sectionRouting!!, textRoutingValue!!,
            endpointEditorLauncher, routingSetupLauncher
        ) { section.reload() }

        suppressWatchers = true
        fieldCompleteMessages?.setText(preferences?.getSummarizerDefaultWindow()?.toString() ?: "20")
        fieldCompactionTokens?.setText(
            (preferences?.getCompactionAverageTokens()
                ?: org.teslasoft.assistant.util.summarizer.CompactionBatches.DEFAULT_TOKENS).toString()
        )
        fieldCompactionMaximumTokens?.setText(preferences?.getCompactionMaximumTokens()?.toString())
        suppressWatchers = false
        compactionFieldsReady = true
        fieldCompactionTokens?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCompactionField(maximum = false)
        }
        fieldCompactionMaximumTokens?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCompactionField(maximum = true)
        }
        fieldCompactionTokens?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (saveCompactionField(maximum = false)) fieldCompactionTokens?.clearFocus()
                true
            } else false
        }
        fieldCompactionMaximumTokens?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (saveCompactionField(maximum = true)) fieldCompactionMaximumTokens?.clearFocus()
                true
            } else false
        }

        fieldCompleteMessages?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatchers) return
                val parsed = s?.toString()?.trim()?.toIntOrNull()
                if (parsed != null && parsed >= 1) preferences?.setSummarizerDefaultWindow(parsed)
            }
        })

        switchNewChats?.isChecked = preferences?.getSummarizerOnForNewChats() ?: false
        switchNewChats?.setOnCheckedChangeListener { _, checked ->
            preferences?.setSummarizerOnForNewChats(checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_summarizer_always_resummarize)?.apply {
            isChecked = preferences?.getSummarizerAlwaysResummarize() ?: true
            setOnCheckedChangeListener { _, checked ->
                preferences?.setSummarizerAlwaysResummarize(checked)
            }
        }
    }

    // Validate the completed number, not each intermediate digit while typing.
    private fun saveCompactionField(maximum: Boolean): Boolean {
        if (!compactionFieldsReady || suppressWatchers) return true
        val prefs = preferences ?: return true
        val field = if (maximum) fieldCompactionMaximumTokens else fieldCompactionTokens
        val saved = if (maximum) prefs.getCompactionMaximumTokens() else prefs.getCompactionAverageTokens()
        val value = field?.text?.toString()?.trim()?.toIntOrNull()
        val invalidOrder = value != null && if (maximum) value < prefs.getCompactionAverageTokens()
            else value > prefs.getCompactionMaximumTokens()
        if (value == null || invalidOrder || (maximum && value <= 0)) {
            suppressWatchers = true
            field?.setText(saved.toString())
            suppressWatchers = false
            if (invalidOrder) showCompactionMaximumError()
            return !invalidOrder
        }
        if (maximum) prefs.setCompactionMaximumTokens(value) else prefs.setCompactionAverageTokens(value)
        return true
    }

    private fun showCompactionMaximumError() {
        val actions = layoutInflater.inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.summarizer_compaction_maximum_error_title)
            .setMessage(R.string.summarizer_compaction_maximum_error_message)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.okay)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    override fun finish() {
        if (!saveCompactionField(maximum = true) || !saveCompactionField(maximum = false)) return
        super.finish()
    }

    override fun onPause() {
        saveCompactionField(maximum = true)
        saveCompactionField(maximum = false)
        super.onPause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ScreenChrome.fitSystemBarsAndKeyboard(
            findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0),
            actionBar,
            findViewById<android.view.View>(R.id.scroll)
        )
    }

}
