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
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.tts.TtsVoiceKind
import org.teslasoft.assistant.tts.api.*
import org.teslasoft.assistant.tts.voices.SavedApiVoiceProvider
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.fragments.dialogs.LanguageSelectorDialogFragment
import org.teslasoft.assistant.stt.LocalWhisperModels
import org.teslasoft.assistant.stt.LocalWhisperStorage
import org.teslasoft.assistant.ui.widgets.AppDropdown
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
 * Shared settings rows are reused from the rest of the app. Speech-to-text
 * groups its controls inline on this screen: the engine radios, the voice
 * detection radios (with WebRTC's sensitivity dropdown shown only when WebRTC
 * is selected), and the hands-free timer blanks.
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
    private var groupVadMethod: RadioGroup? = null
    private var vadWebRtcSensitivity: TextView? = null
    private var fieldHandsFreeSilence: TextInputEditText? = null
    private var fieldHandsFreeNoSpeech: TextInputEditText? = null
    private var rowVoiceAdvanced: LinearLayout? = null
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

        setupVoiceDetection()
        setupHandsFreeTimers()

        switchReadFormatting = findViewById(R.id.switch_read_formatting)
        switchReadFormatting?.isChecked = GlobalPreferences.getPreferences(this).getReadFormattingLanguage()
        switchReadFormatting?.setOnCheckedChangeListener { _, checked ->
            GlobalPreferences.getPreferences(this).setReadFormattingLanguage(checked)
        }

        findViewById<LinearLayout>(R.id.row_api_voice_models).setOnClickListener {
            startActivity(Intent(this, ApiVoiceModelsActivity::class.java).putExtra("chatId", chatId))
        }
        rowVoiceAdvanced = findViewById(R.id.tile_voice_advanced)

        rowVoiceAdvanced?.setOnClickListener {
            startActivity(Intent(this, VoiceAdvancedSettingsActivity::class.java))
        }
    }

    // Hands-free timers as inline write-in blanks. Each keystroke that parses
    // to a whole number is coerced into the accepted 1..120 range and saved, so
    // the stored value is always valid; blank or unparseable input is ignored
    // until a digit is typed. Same bounds the old timing dialog enforced on OK.
    private fun setupHandsFreeTimers() {
        fieldHandsFreeSilence = findViewById(R.id.field_hands_free_silence)
        fieldHandsFreeNoSpeech = findViewById(R.id.field_hands_free_no_speech)

        fieldHandsFreeSilence?.setText((preferences?.getHandsFreeSilenceSeconds() ?: 5).toString())
        fieldHandsFreeNoSpeech?.setText((preferences?.getHandsFreeNoSpeechSeconds() ?: 10).toString())

        fieldHandsFreeSilence?.doAfterTextChanged { text ->
            text?.toString()?.toIntOrNull()?.coerceIn(1, 120)?.let {
                preferences?.setHandsFreeSilenceSeconds(it)
            }
        }
        fieldHandsFreeNoSpeech?.doAfterTextChanged { text ->
            text?.toString()?.toIntOrNull()?.coerceIn(1, 120)?.let {
                preferences?.setHandsFreeNoSpeechSeconds(it)
            }
        }
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

    // Voice-activity-detection method as inline radios (Silero / Energy /
    // WebRTC). Only affects on-device Whisper hands-free — the Google path uses
    // the platform recognizer's own end-of-speech detection. WebRTC is last so
    // its sensitivity dropdown, shown only while WebRTC is selected, never
    // shifts the radios above it. The dropdown's choice is persisted on its own
    // key, so switching away from WebRTC and back keeps it.
    private fun setupVoiceDetection() {
        groupVadMethod = findViewById(R.id.group_vad_method)
        vadWebRtcSensitivity = findViewById(R.id.vad_webrtc_sensitivity)

        val method = preferences?.getVadMethod() ?: "silero"
        // check() before wiring the listener so the initial state doesn't fire
        // a spurious re-save or availability toast on every screen open.
        groupVadMethod?.check(vadRadioId(method))
        vadWebRtcSensitivity?.visibility = if (method == "webrtc") View.VISIBLE else View.GONE
        vadWebRtcSensitivity?.text = webRtcSensitivityLabel()

        groupVadMethod?.setOnCheckedChangeListener { _, checkedId ->
            val picked = vadMethodOf(checkedId)
            preferences?.setVadMethod(picked)
            vadWebRtcSensitivity?.visibility = if (picked == "webrtc") View.VISIBLE else View.GONE
            if (picked == "webrtc" && !org.teslasoft.assistant.stt.WebRtcVadNative.ensureLoaded()) {
                Toast.makeText(this, R.string.vad_webrtc_unavailable, Toast.LENGTH_LONG).show()
            }
            if (picked == "silero" &&
                !org.teslasoft.assistant.stt.SileroVadRuntime.ensureLoaded(applicationContext)
            ) {
                Toast.makeText(this, R.string.vad_silero_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        vadWebRtcSensitivity?.setOnClickListener {
            val anchor = vadWebRtcSensitivity ?: return@setOnClickListener
            val labels = listOf(
                getString(R.string.vad_sensitivity_high),        // mode 0
                getString(R.string.vad_sensitivity_medium_high), // mode 1
                getString(R.string.vad_sensitivity_medium_low),  // mode 2
                getString(R.string.vad_sensitivity_low)          // mode 3
            )
            val current = (preferences?.getVadWebRtcMode() ?: 1).coerceIn(0, 3)
            AppDropdown.show(anchor, labels, current) { index ->
                preferences?.setVadWebRtcMode(index)
                vadWebRtcSensitivity?.text = labels[index]
            }
        }
    }

    private fun vadRadioId(method: String): Int = when (method) {
        "silero" -> R.id.radio_vad_silero
        "webrtc" -> R.id.radio_vad_webrtc
        else -> R.id.radio_vad_energy
    }

    private fun vadMethodOf(radioId: Int): String = when (radioId) {
        R.id.radio_vad_silero -> "silero"
        R.id.radio_vad_webrtc -> "webrtc"
        else -> "energy"
    }

    // Full sensitivity label for the current WebRTC mode (index 0 = mode 0 =
    // most sensitive). Defaults to medium-high.
    private fun webRtcSensitivityLabel(): String {
        return when ((preferences?.getVadWebRtcMode() ?: 1).coerceIn(0, 3)) {
            0 -> getString(R.string.vad_sensitivity_high)
            2 -> getString(R.string.vad_sensitivity_medium_low)
            3 -> getString(R.string.vad_sensitivity_low)
            else -> getString(R.string.vad_sensitivity_medium_high)
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
