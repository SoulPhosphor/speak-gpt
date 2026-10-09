/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.activities

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import org.teslasoft.assistant.stt.api.ApiSttSettings
import org.teslasoft.assistant.stt.api.SttVocabulary
import org.teslasoft.assistant.stt.api.SttVocabularySession
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.tts.api.TtsPickerRequest
import org.teslasoft.assistant.tts.api.TtsTarget
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.assistant.util.WindowInsetsUtil
import java.util.EnumSet
import java.util.Locale

/**
 * API Voice Service: the endpoint, speech-to-text model, routing, spoken
 * language and custom vocabulary used when voice input is set to API Voice
 * Service. Every change saves immediately to the global settings.
 */
class ApiVoiceServiceActivity : SettingsPageActivity() {
    private lateinit var settings: ApiSttSettings
    private var endpoints: List<TtsEndpointChoice> = emptyList()
    private var openRouterIds: Set<String> = emptySet()
    private val modes = TtsRoutingMode.entries
    private val modelPicker = registerForActivityResult(SttModelPickerContract()) { result ->
        val current = settings.target
        if (result == null || !TtsPickerRequest(current).acceptsModelResult(result)) return@registerForActivityResult
        // Providers belong to a model, so a different model starts from Automatic.
        settings.target = if (result.modelId == current.modelId) result else result.copy(routing = TtsRoutingSettings())
        render()
    }
    private val providerPicker = registerForActivityResult(SttProviderPickerContract()) { result ->
        if (result == null || !TtsPickerRequest(settings.target).acceptsProviderResult(result)) return@registerForActivityResult
        settings.target = settings.target.copy(routing = result.routing)
        render()
    }

    private lateinit var field: TextInputEditText
    private lateinit var addButton: MaterialButton
    private lateinit var errorLine: TextView
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var clearButton: MaterialButton
    private var duplicateShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 30) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_api_voice_service)
        settings = ApiSttSettings.get(this)
        val back = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, findViewById(R.id.action_bar), back)
        back.setOnClickListener { finish() }

        findViewById<TextView>(R.id.api_stt_endpoint).setOnClickListener { view ->
            val index = endpoints.indexOfFirst { it.id == settings.target.endpointId }
            AppDropdown.show(view as TextView, endpoints.map { it.label }, index) { selectEndpoint(endpoints[it].id) }
        }
        findViewById<View>(R.id.api_stt_model_row).setOnClickListener {
            modelPicker.launch(TtsPickerRequest(settings.target))
        }
        findViewById<TextView>(R.id.api_stt_routing_mode).setOnClickListener { view ->
            AppDropdown.show(view as TextView, modes.map(::modeLabel), modes.indexOf(settings.target.routing.mode)) {
                val t = settings.target
                settings.target = t.copy(routing = t.routing.copy(mode = modes[it]))
                render()
            }
        }
        findViewById<View>(R.id.api_stt_routing_gear).setOnClickListener {
            providerPicker.launch(TtsPickerRequest(settings.target))
        }
        findViewById<TextView>(R.id.api_stt_language).setOnClickListener { view ->
            val codes = languageCodes()
            AppDropdown.show(view as TextView, codes.map(::languageLabel), codes.indexOf(settings.language).coerceAtLeast(0)) {
                settings.language = codes[it]
                render()
            }
        }

        field = findViewById(R.id.api_stt_vocabulary_field)
        addButton = findViewById(R.id.api_stt_vocabulary_add)
        errorLine = findViewById(R.id.api_stt_vocabulary_error)
        list = findViewById(R.id.api_stt_vocabulary_list)
        count = findViewById(R.id.api_stt_vocabulary_count)
        clearButton = findViewById(R.id.api_stt_vocabulary_clear)
        field.doAfterTextChanged { duplicateShown = false; renderVocabularyControls() }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { addEntry(); true } else false
        }
        addButton.setOnClickListener { addEntry() }
        clearButton.setOnClickListener { confirmClearAll() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WindowInsetsUtil.adjustPaddings(this, R.id.action_bar, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS))
        WindowInsetsUtil.adjustPaddings(this, R.id.api_stt_scroll, EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR))
    }

    override fun onStart() {
        super.onStart()
        val profiles = try {
            ApiEndpointPreferences.getApiEndpointPreferences(this).getApiEndpointsList(this)
        } catch (_: Exception) { emptyList() }
        endpoints = profiles.map { TtsEndpointChoice(it.id, it.label.ifBlank { it.id }) }.sortedBy { it.label.lowercase() }
        openRouterIds = profiles.filter { it.hasOpenRouterCatalogAuthority() }.map { it.id }.toSet()
        // With a single endpoint there is nothing to choose: it is the endpoint.
        if (endpoints.size == 1 && settings.target.endpointId != endpoints[0].id) selectEndpoint(endpoints[0].id)
        render()
    }

    private fun selectEndpoint(id: String) {
        if (id == settings.target.endpointId) return
        // A model and its providers belong to one endpoint.
        settings.target = TtsTarget(id)
        render()
    }

    private fun render() {
        val select = getString(R.string.tts_manager_select)
        val t = settings.target
        val endpointLabel = endpoints.firstOrNull { it.id == t.endpointId }?.label
        val single = endpoints.size == 1
        findViewById<TextView>(R.id.api_stt_endpoint).apply {
            visibility = if (single) View.GONE else View.VISIBLE
            text = endpointLabel ?: select
            isEnabled = endpoints.isNotEmpty()
            AppDropdown.sizeToOptions(this, endpoints.map { it.label } + select) { availableWidth(this) }
        }
        findViewById<TextView>(R.id.api_stt_endpoint_single).apply {
            visibility = if (single) View.VISIBLE else View.GONE
            text = endpointLabel.orEmpty()
        }
        findViewById<TextView>(R.id.api_stt_model_value).text = t.modelId.ifBlank { select }
        // Routing only means something where several providers serve a model.
        findViewById<View>(R.id.api_stt_routing_row).visibility =
            if (t.endpointId in openRouterIds) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.api_stt_routing_mode).apply {
            text = modeLabel(t.routing.mode)
            AppDropdown.sizeToOptions(this, modes.map(::modeLabel)) { availableWidth(this) }
        }
        findViewById<TextView>(R.id.api_stt_language).apply {
            text = languageLabel(settings.language)
            AppDropdown.sizeToOptions(this, languageCodes().map(::languageLabel)) { availableWidth(this) }
        }
        renderVocabulary()
    }

    private fun renderVocabulary() {
        val entries = settings.vocabulary
        list.removeAllViews()
        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.view_removable_list_row, list, false)
            row.findViewById<TextView>(R.id.removable_list_label).text = entry
            row.findViewById<ImageButton>(R.id.removable_list_remove).apply {
                contentDescription = getString(R.string.api_stt_vocabulary_remove_desc, entry)
                setOnClickListener { confirmRemove(entry) }
            }
            list.addView(row)
        }
        findViewById<View>(R.id.api_stt_vocabulary_box).visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        count.text = resources.getQuantityString(R.plurals.api_stt_vocabulary_count, entries.size, entries.size)
        count.isActivated = SttVocabulary.isFull(entries)
        clearButton.isEnabled = entries.isNotEmpty()
        renderVocabularyControls()
    }

    private fun renderVocabularyControls() {
        val full = SttVocabulary.isFull(settings.vocabulary)
        addButton.isEnabled = !full && field.text?.toString()?.isNotBlank() == true
        when {
            full -> { errorLine.setText(R.string.api_stt_vocabulary_full); errorLine.visibility = View.VISIBLE }
            duplicateShown -> { errorLine.setText(R.string.api_stt_vocabulary_duplicate); errorLine.visibility = View.VISIBLE }
            else -> errorLine.visibility = View.GONE
        }
    }

    private fun addEntry() {
        when (val result = SttVocabulary.add(settings.vocabulary, field.text?.toString().orEmpty())) {
            is SttVocabulary.AddResult.Added -> {
                settings.vocabulary = result.entries
                field.setText("")
                duplicateShown = false
                renderVocabulary()
            }
            SttVocabulary.AddResult.Duplicate -> { duplicateShown = true; renderVocabularyControls() }
            SttVocabulary.AddResult.Blank, SttVocabulary.AddResult.Full -> renderVocabularyControls()
        }
    }

    private fun remove(entry: String) {
        settings.vocabulary = settings.vocabulary - entry
        renderVocabulary()
    }

    private fun confirmRemove(entry: String) {
        if (SttVocabularySession.skipRemoveConfirmation) { remove(entry); return }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, content, false)
        val option = layoutInflater.inflate(R.layout.view_dialog_check_option, content, false)
        content.addView(actions)
        content.addView(option)
        val check = option.findViewById<MaterialCheckBox>(R.id.check_dialog_option)
        check.setText(R.string.api_stt_vocabulary_remove_session)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(getString(R.string.api_stt_vocabulary_remove_title, entry))
            .setView(content)
            .setCancelable(true)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener {
                if (check.isChecked) SttVocabularySession.skipRemoveConfirmation = true
                dialog.dismiss()
                remove(entry)
            }
        }
        dialog.show()
    }

    private fun confirmClearAll() {
        val size = settings.vocabulary.size
        if (size == 0) return
        val actions = layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.api_stt_vocabulary_clear_title)
            .setMessage(resources.getQuantityString(R.plurals.api_stt_vocabulary_clear_message, size, size))
            .setView(actions)
            .setCancelable(true)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.api_stt_vocabulary_clear_all)
            setOnClickListener {
                dialog.dismiss()
                settings.vocabulary = emptyList()
                renderVocabulary()
            }
        }
        dialog.show()
    }

    private fun modeLabel(mode: TtsRoutingMode) = getString(when (mode) {
        TtsRoutingMode.AUTOMATIC -> R.string.choose_provider_routing_automatic
        TtsRoutingMode.PREFERRED -> R.string.choose_provider_routing_preferred
        TtsRoutingMode.ONLY -> R.string.choose_provider_routing_only
    })

    // Automatic first, then the platform's ISO 639-1 languages by name.
    private fun languageCodes(): List<String> = listOf(ApiSttSettings.LANGUAGE_AUTOMATIC) +
        Locale.getISOLanguages().sortedBy { languageLabel(it).lowercase() }

    private fun languageLabel(code: String): String =
        if (code == ApiSttSettings.LANGUAGE_AUTOMATIC) getString(R.string.api_stt_language_automatic)
        else Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }

    private fun availableWidth(anchor: TextView): Int =
        (anchor.parent as? View)?.width ?: resources.displayMetrics.widthPixels
}
