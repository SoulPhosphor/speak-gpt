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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.tts.TtsVoiceKind
import org.teslasoft.assistant.tts.api.*
import org.teslasoft.assistant.tts.voices.SavedApiVoiceProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.fragments.TileFragment
import org.teslasoft.assistant.ui.fragments.dialogs.LanguageSelectorDialogFragment
import org.teslasoft.assistant.stt.LocalWhisperModels
import org.teslasoft.assistant.stt.LocalWhisperStorage
import org.teslasoft.assistant.util.WindowInsetsUtil
import java.util.EnumSet
import java.util.Locale
import kotlin.math.roundToInt
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.tts.voices.GoogleVoiceNumberRegistry
import org.teslasoft.assistant.tts.voices.VoiceIdentityRegistry

/**
 * One screen that owns every speech-related setting. Reached from the single
 * full-width "Voice & speech" tile on the main Settings page; previously these
 * controls were scattered across the Settings grid. Grouped into Text-to-speech,
 * Speech-to-text, Hands-free & voice activity, and Audio feedback so each knob
 * sits next to the ones it interacts with (e.g. hands-free + auto-send + VAD).
 *
 * Shared tiles and settings rows are reused from the rest of the app. Deeper,
 * per-method options (WebRTC sensitivity, the hands-free timers, the engine
 * picker) open from their tile as dialogs — the "cog goes deeper" pattern the
 * user asked for.
 */
class VoiceSettingsActivity : FragmentActivity() {

    private var rowVoiceBrowser: LinearLayout? = null
    private var valueVoiceBrowser: TextView? = null
    private var rowVoiceLanguage: ConstraintLayout? = null
    private var valueVoiceLanguage: TextView? = null
    private var radioVoiceInputWhisperCloud: RadioButton? = null
    private var radioVoiceInputWhisperLocal: RadioButton? = null
    private var radioVoiceInputGoogle: RadioButton? = null
    private var cogVoiceInputWhisperLocal: ImageButton? = null
    private var rowDictationLanguage: ConstraintLayout? = null
    private var valueDictationLanguage: TextView? = null
    private var tileHandsFreeTiming: TileFragment? = null
    private var tileVadMethod: TileFragment? = null
    private var rowVoiceAdvanced: LinearLayout? = null
    private var rowVoiceDebugging: LinearLayout? = null
    private var switchAlwaysSpeak: MaterialSwitch? = null
    private var switchAutoSend: MaterialSwitch? = null
    private var switchReadFormatting: MaterialSwitch? = null

    private var btnBack: ImageButton? = null
    private var actionBar: ConstraintLayout? = null

    private var chatId = ""
    private var preferences: Preferences? = null
    private var language = "en"
    private val voiceGate = TtsRequestGate()
    private var voiceNotice: androidx.appcompat.app.AlertDialog? = null

    private var languageChangedListener: LanguageSelectorDialogFragment.StateChangesListener = object : LanguageSelectorDialogFragment.StateChangesListener {
        override fun onSelected(name: String) {
            if (name == "auto") {
                preferences?.setAutoLangDetect(true)
            } else {
                preferences?.setAutoLangDetect(false)
                preferences?.setLanguage(name)
                language = name
            }
            updateVoiceLanguageValue()
        }

        override fun onFormError(name: String) {
            Toast.makeText(this@VoiceSettingsActivity, getString(R.string.language_error_empty), Toast.LENGTH_SHORT).show()
            val languageSelectorDialogFragment: LanguageSelectorDialogFragment = LanguageSelectorDialogFragment.newInstance(name, chatId, showAutomatic = true)
            languageSelectorDialogFragment.setStateChangedListener(this)
            languageSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "LanguageSelectorDialog")
        }
    }

    private var dictationLanguageListener: LanguageSelectorDialogFragment.StateChangesListener = object : LanguageSelectorDialogFragment.StateChangesListener {
        override fun onSelected(name: String) {
            preferences?.setDictationLanguage(name)
            valueDictationLanguage?.text = Locale.forLanguageTag(name).displayLanguage
        }

        override fun onFormError(name: String) {
            Toast.makeText(this@VoiceSettingsActivity, getString(R.string.language_error_empty), Toast.LENGTH_SHORT).show()
            val dialog: LanguageSelectorDialogFragment = LanguageSelectorDialogFragment.newInstance(name, chatId)
            dialog.setStateChangedListener(this)
            dialog.show(supportFragmentManager.beginTransaction(), "DictationLanguageDialog")
        }
    }

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
        setContentView(R.layout.activity_voice_settings)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                finish()
            }
        }

        val expandableWindow = findViewById<ConstraintLayout>(R.id.expandable_window)
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        if (isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack()) {
            expandableWindow?.backgroundTintList = ColorStateList.valueOf(getColor(R.color.amoled_window_background))
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            expandableWindow?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_1.getColor(this))
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        language = preferences?.getLanguage() ?: "en"

        btnBack?.setOnClickListener { finish() }

        createTiles()
        placeTiles()
        initLogic()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Insets must be applied here, not in onCreate: on S+ the util reads
        // window.decorView.rootWindowInsets, which is null before the window
        // attaches — the call silently no-oped and this screen rendered under
        // the status bar with its last tile cut off behind the gesture area.
        adjustPaddings()
    }

    private fun updateVoiceBrowserRow() {
        val prefs = preferences ?: return
        val token = voiceGate.begin()
        lifecycleScope.launch {
            val result = TtsSelectionService(this@VoiceSettingsActivity, prefs).reconcile(token)
            token.deliver {
                val selection = result.getOrNull()
                if (selection == null) {
                    val failure = (result.exceptionOrNull() as? TtsException)?.failure ?: return@deliver
                    valueVoiceBrowser?.text = TtsFailures.message(failure).title
                    voiceNotice?.dismiss()
                    voiceNotice = TtsVoiceDialogs.show(this@VoiceSettingsActivity, chatId, failure, ::updateVoiceBrowserRow)
                } else if (selection.kind == TtsVoiceKind.DEVICE) {
                    val identities = VoiceIdentityRegistry(this@VoiceSettingsActivity)
                    valueVoiceBrowser?.text = getString(R.string.voice_browser_setting_subtitle_provider,
                        getString(R.string.voice_browser_setting_subtitle_google), identities.displayNameFor("google",
                            selection.voiceId, GoogleVoiceNumberRegistry(this@VoiceSettingsActivity).displayNameFor(selection.voiceId)))
                }
            }
            val selection = result.getOrNull()?.takeIf { it.kind == TtsVoiceKind.API } ?: return@launch
            val resolved = withContext(Dispatchers.IO) {
                TtsAndroidServices.resolver(this@VoiceSettingsActivity).saved(selection.sourceId, selection.voiceId)
            }
            val source = resolved.getOrNull() ?: return@launch
            val voiceName = VoiceIdentityRegistry(this@VoiceSettingsActivity).displayNameFor(selection.sourceId,
                selection.voiceId, selection.voiceId)
            token.deliver {
                valueVoiceBrowser?.text = getString(R.string.voice_browser_setting_subtitle_provider,
                    SavedApiVoiceProvider.sourceLabel(source.endpoint.label, source.target.modelId, source.target.routing), voiceName)
            }
            val sourceName = withContext(Dispatchers.IO) { SavedApiVoiceProvider.discoverLabel(source, token) }
            token.deliver {
                valueVoiceBrowser?.text = getString(R.string.voice_browser_setting_subtitle_provider, sourceName, voiceName)
            }
        }
    }

    override fun onPause() {
        voiceGate.cancel()
        voiceNotice?.dismiss()
        super.onPause()
    }

    private fun createTiles() {
        tileHandsFreeTiming = TileFragment.newInstance(
            checked = false,
            checkable = false,
            enabledText = getString(R.string.tile_hands_free_timing_title),
            disabledText = null,
            enabledDesc = getString(
                R.string.tile_hands_free_timing_value,
                preferences?.getHandsFreeSilenceSeconds() ?: 5,
                preferences?.getHandsFreeNoSpeechSeconds() ?: 10
            ),
            disabledDesc = null,
            icon = R.drawable.ic_play,
            disabled = false,
            chatId = chatId,
            functionDesc = getString(R.string.tile_hands_free_timing_desc)
        )

        tileVadMethod = TileFragment.newInstance(
            checked = false,
            checkable = false,
            enabledText = getString(R.string.tile_vad_method_title),
            disabledText = null,
            enabledDesc = vadMethodSubtitle(),
            disabledDesc = null,
            icon = R.drawable.ic_microphone,
            disabled = false,
            chatId = chatId,
            functionDesc = getString(R.string.tile_vad_method_desc)
        )

    }

    private fun placeTiles() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.tile_hands_free_timing, tileHandsFreeTiming!!)
            .replace(R.id.tile_vad_method, tileVadMethod!!)
            .commitNow()
    }

    private fun initLogic() {
        rowVoiceBrowser = findViewById(R.id.row_voice_browser)
        valueVoiceBrowser = findViewById(R.id.value_voice_browser)
        rowVoiceBrowser?.setOnClickListener {
            startActivity(Intent(this, VoiceBrowserActivity::class.java).putExtra(VoiceBrowserActivity.EXTRA_CHAT_ID, chatId))
        }

        rowVoiceLanguage = findViewById(R.id.row_voice_language)
        valueVoiceLanguage = findViewById(R.id.value_voice_language)
        updateVoiceLanguageValue()
        rowVoiceLanguage?.setOnClickListener {
            val current = if (preferences?.getAutoLangDetect() == true) "auto" else language
            val languageSelectorDialogFragment: LanguageSelectorDialogFragment = LanguageSelectorDialogFragment.newInstance(current, chatId, showAutomatic = true)
            languageSelectorDialogFragment.setStateChangedListener(languageChangedListener)
            languageSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "LanguageSelectorDialog")
        }

        switchAlwaysSpeak = findViewById(R.id.switch_always_speak)
        switchAlwaysSpeak?.isChecked = preferences?.getNotSilence() == true
        switchAlwaysSpeak?.setOnCheckedChangeListener { _, isChecked ->
            preferences?.setNotSilence(isChecked)
        }

        switchAutoSend = findViewById(R.id.switch_auto_send)
        switchAutoSend?.isChecked = preferences?.autoSend() == true
        switchAutoSend?.setOnCheckedChangeListener { _, checked ->
            preferences?.setAutoSend(checked)
        }

        radioVoiceInputWhisperCloud = findViewById(R.id.radio_voice_input_whisper_cloud)
        radioVoiceInputWhisperLocal = findViewById(R.id.radio_voice_input_whisper_local)
        radioVoiceInputGoogle = findViewById(R.id.radio_voice_input_google)
        cogVoiceInputWhisperLocal = findViewById(R.id.cog_voice_input_whisper_local)
        rowDictationLanguage = findViewById(R.id.row_dictation_language)
        valueDictationLanguage = findViewById(R.id.value_dictation_language)

        applyVoiceInputSelection(preferences?.getAudioModel() ?: "google")
        valueDictationLanguage?.text = Locale.forLanguageTag(preferences?.getDictationLanguage() ?: "en").displayLanguage

        // The radios manage mutual exclusion by hand because the on-device
        // Whisper row carries a trailing cog and so isn't a direct RadioGroup
        // child. Picking on-device Whisper opens its screen only when no model
        // is active yet, so a user is never stranded with nothing to transcribe
        // with; once a model is active, picking it just selects the engine.
        radioVoiceInputWhisperCloud?.setOnClickListener { onVoiceInputPicked("whisper") }
        radioVoiceInputWhisperLocal?.setOnClickListener { onVoiceInputPicked("whisper-local") }
        radioVoiceInputGoogle?.setOnClickListener { onVoiceInputPicked("google") }

        // The cog is a plain link into the on-device Whisper screen. It does
        // not select the engine.
        cogVoiceInputWhisperLocal?.setOnClickListener {
            startActivity(Intent(this, LocalWhisperModelsActivity::class.java))
        }

        rowDictationLanguage?.setOnClickListener {
            val current = preferences?.getDictationLanguage() ?: "en"
            val dialog = LanguageSelectorDialogFragment.newInstance(current, chatId)
            dialog.setStateChangedListener(dictationLanguageListener)
            dialog.show(supportFragmentManager.beginTransaction(), "DictationLanguageDialog")
        }

        tileHandsFreeTiming?.setOnTileClickListener {
            showHandsFreeTimingDialog()
        }

        tileVadMethod?.setOnTileClickListener {
            vadMethodSelector()
        }

        switchReadFormatting = findViewById(R.id.switch_read_formatting)
        switchReadFormatting?.isChecked = GlobalPreferences.getPreferences(this).getReadFormattingLanguage()
        switchReadFormatting?.setOnCheckedChangeListener { _, checked ->
            GlobalPreferences.getPreferences(this).setReadFormattingLanguage(checked)
        }

        findViewById<LinearLayout>(R.id.row_api_voice_models).setOnClickListener {
            startActivity(Intent(this, ApiVoiceModelsActivity::class.java).putExtra("chatId", chatId))
        }
        rowVoiceAdvanced = findViewById(R.id.tile_voice_advanced)
        rowVoiceDebugging = findViewById(R.id.tile_voice_debugging)

        rowVoiceAdvanced?.setOnClickListener {
            startActivity(Intent(this, VoiceAdvancedSettingsActivity::class.java))
        }

        rowVoiceDebugging?.setOnClickListener {
            startActivity(Intent(this, AudioDebuggingActivity::class.java).putExtra("chatId", chatId))
        }
    }

    private fun showHandsFreeTimingDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, pad, pad, 0)

        val silenceLabel = android.widget.TextView(this)
        silenceLabel.text = getString(R.string.tile_hands_free_silence_title)
        val silenceInput = android.widget.EditText(this)
        silenceInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        silenceInput.setText((preferences?.getHandsFreeSilenceSeconds() ?: 5).toString())

        val noSpeechLabel = android.widget.TextView(this)
        noSpeechLabel.text = getString(R.string.tile_hands_free_no_speech_title)
        val noSpeechInput = android.widget.EditText(this)
        noSpeechInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        noSpeechInput.setText((preferences?.getHandsFreeNoSpeechSeconds() ?: 10).toString())

        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        labelParams.topMargin = (12 * density).toInt()

        container.addView(silenceLabel)
        container.addView(silenceInput)
        noSpeechLabel.layoutParams = labelParams
        container.addView(noSpeechLabel)
        container.addView(noSpeechInput)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.tile_hands_free_timing_title)
            .setView(container)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                silenceInput.text.toString().toIntOrNull()?.coerceIn(1, 120)?.let {
                    preferences?.setHandsFreeSilenceSeconds(it)
                }
                noSpeechInput.text.toString().toIntOrNull()?.coerceIn(1, 120)?.let {
                    preferences?.setHandsFreeNoSpeechSeconds(it)
                }
                tileHandsFreeTiming?.updateSubtitle(
                    getString(
                        R.string.tile_hands_free_timing_value,
                        preferences?.getHandsFreeSilenceSeconds() ?: 5,
                        preferences?.getHandsFreeNoSpeechSeconds() ?: 10
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun updateVoiceLanguageValue() {
        valueVoiceLanguage?.text = if (preferences?.getAutoLangDetect() == true) {
            getString(R.string.voice_language_automatic)
        } else {
            Locale.forLanguageTag(preferences?.getLanguage() ?: "en").displayLanguage
        }
    }

    // Reflect the selected engine in the radios and the dictation-language
    // row without persisting or navigating — used both on load and on tap.
    private fun applyVoiceInputSelection(engine: String) {
        radioVoiceInputWhisperCloud?.isChecked = engine == "whisper"
        radioVoiceInputWhisperLocal?.isChecked = engine == "whisper-local"
        radioVoiceInputGoogle?.isChecked = engine == "google"
        rowDictationLanguage?.visibility = if (engine == "google") View.VISIBLE else View.GONE
    }

    private fun onVoiceInputPicked(engine: String) {
        applyVoiceInputSelection(engine)
        preferences?.setAudioModel(engine)
        if (engine == "whisper-local" && !hasActiveLocalWhisperModel()) {
            startActivity(Intent(this, LocalWhisperModelsActivity::class.java))
        }
    }

    // True only when the stored active model is one we know and its file is
    // actually on disk, so a stale selection pointing at a deleted model still
    // sends the user to the screen to pick one.
    private fun hasActiveLocalWhisperModel(): Boolean {
        val activeId = preferences?.getActiveLocalWhisperModel() ?: ""
        if (activeId.isEmpty()) return false
        val model = LocalWhisperModels.byId(activeId) ?: return false
        return LocalWhisperStorage.isInstalled(this, model)
    }

    // Voice-activity-detection method picker. Only applies to on-device
    // Whisper hands-free (the Google path uses the platform recognizer's own
    // end-of-speech detection). A selectable VAD method plus, optionally, an
    // action to open that method's own options. [openOptions] == null means the
    // method has no tunables (so no cog is shown). New methods — e.g. Silero —
    // drop in here with their own options lambda; the picker scales without
    // further wiring.
    private data class VadMethodEntry(
        val id: String,
        val label: String,
        val openOptions: (() -> Unit)?
    )

    // Custom picker: a radio per method, plus a settings cog on any method that
    // has options (mirrors how Android's own input-method picker lets you
    // configure each entry). A plain single-choice dialog can't host per-row
    // buttons, so the rows are built by hand.
    private fun vadMethodSelector() {
        val entries = listOf(
            // Silero's one tunable (the speech threshold) lives in Advanced &
            // debugging with the rest of the detection knobs, so no cog here.
            VadMethodEntry("silero", getString(R.string.vad_method_silero), null),
            VadMethodEntry("webrtc", getString(R.string.vad_method_webrtc)) { showWebRtcSensitivityDialog() },
            VadMethodEntry("energy", getString(R.string.vad_method_energy), null)
        )

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var selectedId = preferences?.getVadMethod() ?: "energy"
        val radios = ArrayList<RadioButton>()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(8), dp(4))
        }

        for (entry in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
            }

            val radio = RadioButton(this).apply {
                text = entry.label
                isChecked = entry.id == selectedId
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), dp(10), 0, dp(10))
                buttonTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@VoiceSettingsActivity, R.color.accent_900)
                )
            }
            radios.add(radio)

            val select = {
                selectedId = entry.id
                for (r in radios) r.isChecked = false
                radio.isChecked = true
            }
            radio.setOnClickListener { select() }
            row.setOnClickListener { select() }
            row.addView(radio)

            entry.openOptions?.let { open ->
                val cog = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_settings)
                    contentDescription = getString(R.string.vad_method_options_cd)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    val bg = TypedValue()
                    if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, bg, true)) {
                        setBackgroundResource(bg.resourceId)
                    }
                    val tint = TypedValue()
                    if (theme.resolveAttribute(android.R.attr.colorControlNormal, tint, true)) {
                        val color = if (tint.resourceId != 0)
                            ContextCompat.getColor(this@VoiceSettingsActivity, tint.resourceId) else tint.data
                        imageTintList = ColorStateList.valueOf(color)
                    }
                    setOnClickListener { open() }
                }
                row.addView(cog)
            }

            container.addView(row)
        }

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.tile_vad_method_title)
            .setView(container)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                preferences?.setVadMethod(selectedId)
                tileVadMethod?.updateSubtitle(vadMethodSubtitle())
                if (selectedId == "webrtc" && !org.teslasoft.assistant.stt.WebRtcVadNative.ensureLoaded()) {
                    Toast.makeText(this, R.string.vad_webrtc_unavailable, Toast.LENGTH_LONG).show()
                }
                if (selectedId == "silero" &&
                    !org.teslasoft.assistant.stt.SileroVadRuntime.ensureLoaded(applicationContext)
                ) {
                    Toast.makeText(this, R.string.vad_silero_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    // WebRTC aggressiveness, as a user-facing "sensitivity" (inverse of
    // libfvad's mode: index 0 = mode 0 = most sensitive). Persisted immediately
    // so the cog works whether or not the method is currently selected.
    private fun showWebRtcSensitivityDialog() {
        val labels = arrayOf(
            getString(R.string.vad_sensitivity_high),        // mode 0
            getString(R.string.vad_sensitivity_medium_high), // mode 1
            getString(R.string.vad_sensitivity_medium_low),  // mode 2
            getString(R.string.vad_sensitivity_low)          // mode 3
        )
        var selected = (preferences?.getVadWebRtcMode() ?: 0).coerceIn(0, 3)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val radios = ArrayList<RadioButton>()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(8), dp(4))
        }
        for (i in labels.indices) {
            val radio = RadioButton(this).apply {
                text = labels[i]
                isChecked = i == selected
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(8), dp(10), 0, dp(10))
                buttonTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@VoiceSettingsActivity, R.color.accent_900)
                )
            }
            radio.setOnClickListener {
                selected = i
                for (r in radios) r.isChecked = false
                radio.isChecked = true
            }
            radios.add(radio)
            container.addView(radio)
        }

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.vad_sensitivity_title)
            .setView(container)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                preferences?.setVadWebRtcMode(selected)
                tileVadMethod?.updateSubtitle(vadMethodSubtitle())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun webRtcSensitivityShortLabel(): String {
        return when ((preferences?.getVadWebRtcMode() ?: 0).coerceIn(0, 3)) {
            1 -> getString(R.string.vad_sensitivity_short_medium_high)
            2 -> getString(R.string.vad_sensitivity_short_medium_low)
            3 -> getString(R.string.vad_sensitivity_short_low)
            else -> getString(R.string.vad_sensitivity_short_high)
        }
    }

    private fun vadMethodSubtitle(): String {
        return when (preferences?.getVadMethod() ?: "energy") {
            "energy" -> getString(R.string.vad_method_energy)
            "silero" -> getString(R.string.vad_method_silero)
            else -> getString(
                R.string.vad_method_subtitle_webrtc,
                getString(R.string.vad_method_webrtc),
                webRtcSensitivityShortLabel()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateVoiceBrowserRow()
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    private fun adjustPaddings() {
        WindowInsetsUtil.adjustPaddings(this, R.id.action_bar, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS))
        WindowInsetsUtil.adjustPaddings(this, R.id.scrollable, EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS), customPaddingBottom = (48 * resources.displayMetrics.density).roundToInt())
    }
}
