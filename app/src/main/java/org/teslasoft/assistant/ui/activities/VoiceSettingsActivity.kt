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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.fragments.TileFragment
import org.teslasoft.assistant.ui.fragments.dialogs.LanguageSelectorDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.VoiceSelectorDialogFragment
import org.teslasoft.assistant.util.WindowInsetsUtil
import java.util.EnumSet
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One screen that owns every speech-related setting. Reached from the single
 * full-width "Voice & speech" tile on the main Settings page; previously these
 * controls were scattered across the Settings grid. Grouped into Text-to-speech,
 * Speech-to-text, Hands-free & voice activity, and Audio feedback so each knob
 * sits next to the ones it interacts with (e.g. hands-free + auto-send + VAD).
 *
 * Tiles are reused from the rest of the app (TileFragment), so the look matches
 * Settings exactly; the deeper, per-method options (WebRTC sensitivity, the
 * hands-free timers, the engine picker) open from their tile as dialogs — the
 * "cog goes deeper" pattern the user asked for.
 */
class VoiceSettingsActivity : FragmentActivity() {

    private var tileTTS: TileFragment? = null
    private var tileVoice: TileFragment? = null
    private var tileVoiceLanguage: TileFragment? = null
    private var tileSilentMode: TileFragment? = null
    private var tileAlwaysSpeak: TileFragment? = null
    private var tileSTT: TileFragment? = null
    private var tileLangDetect: TileFragment? = null
    private var tileHandsFree: TileFragment? = null
    private var tileAutoSend: TileFragment? = null
    private var tileHandsFreeTiming: TileFragment? = null
    private var tileVadMethod: TileFragment? = null
    private var tileTranscriptionSound: TileFragment? = null

    private var btnBack: ImageButton? = null

    private var chatId = ""
    private var preferences: Preferences? = null
    private var language = "en"
    private var voice = ""
    private var ttsEngine = "google"

    private var languageChangedListener: LanguageSelectorDialogFragment.StateChangesListener = object : LanguageSelectorDialogFragment.StateChangesListener {
        override fun onSelected(name: String) {
            preferences?.setLanguage(name)
            language = name
            tileVoiceLanguage?.updateSubtitle(Locale.forLanguageTag(name).displayLanguage)
        }

        override fun onFormError(name: String) {
            Toast.makeText(this@VoiceSettingsActivity, getString(R.string.language_error_empty), Toast.LENGTH_SHORT).show()
            val languageSelectorDialogFragment: LanguageSelectorDialogFragment = LanguageSelectorDialogFragment.newInstance(name, chatId)
            languageSelectorDialogFragment.setStateChangedListener(this)
            languageSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "LanguageSelectorDialog")
        }
    }

    private var voiceSelectorListener: VoiceSelectorDialogFragment.OnVoiceSelectedListener =
        VoiceSelectorDialogFragment.OnVoiceSelectedListener { voice ->
            this@VoiceSettingsActivity.voice = voice
            tileVoice?.updateSubtitle(voice)
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
        setContentView(R.layout.activity_voice_settings)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                finish()
            }
        }

        val expandableWindow = findViewById<LinearLayout>(R.id.expandable_window)
        if (isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack()) {
            expandableWindow?.backgroundTintList = ColorStateList.valueOf(getColor(R.color.amoled_window_background))
        } else {
            expandableWindow?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_1.getColor(this))
        }

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        language = preferences?.getLanguage() ?: "en"
        ttsEngine = preferences?.getTtsEngine() ?: "google"
        voice = if (ttsEngine == "google") preferences?.getVoice() ?: "" else preferences?.getOpenAIVoice() ?: ""

        btnBack = findViewById(R.id.btn_back)
        btnBack?.setOnClickListener { finish() }

        createTiles()
        placeTiles()
        initLogic()
        adjustPaddings()
    }

    private fun createTiles() {
        tileTTS = TileFragment.newInstance(
            preferences?.getTtsEngine() == "openai",
            true,
            getString(R.string.tile_openai_tts),
            null,
            getString(R.string.on),
            getString(R.string.tile_google_tts),
            R.drawable.ic_tts,
            false,
            chatId,
            getString(R.string.tile_tts_desc)
        )

        tileVoice = TileFragment.newInstance(
            checked = false,
            checkable = false,
            enabledText = getString(R.string.tile_tts_voice_title),
            disabledText = null,
            enabledDesc = voice,
            disabledDesc = null,
            icon = R.drawable.ic_voice,
            disabled = false,
            chatId = chatId,
            functionDesc = getString(R.string.tile_tts_voice_desc)
        )

        tileVoiceLanguage = TileFragment.newInstance(
            checked = false,
            checkable = false,
            enabledText = getString(R.string.tile_voice_lang_title),
            disabledText = null,
            enabledDesc = Locale.forLanguageTag(preferences?.getLanguage()!!).displayLanguage,
            disabledDesc = null,
            icon = R.drawable.ic_language,
            disabled = false,
            chatId = chatId,
            functionDesc = getString(R.string.tile_voice_lang_desc)
        )

        tileSilentMode = TileFragment.newInstance(
            preferences?.getSilence() == true,
            true,
            getString(R.string.tile_silent_mode_title),
            null,
            getString(R.string.on),
            getString(R.string.off),
            R.drawable.ic_mute,
            preferences?.getNotSilence() == true,
            chatId,
            getString(R.string.tile_silent_mode_desc)
        )

        tileAlwaysSpeak = TileFragment.newInstance(
            preferences?.getNotSilence() == true,
            true,
            getString(R.string.tile_always_speak_title),
            null,
            getString(R.string.on),
            getString(R.string.off),
            R.drawable.ic_volume_up,
            preferences?.getSilence() == true,
            chatId,
            getString(R.string.tile_always_speak_desc)
        )

        tileSTT = TileFragment.newInstance(
            checked = false,
            checkable = false,
            enabledText = getString(R.string.tile_voice_input_title),
            disabledText = null,
            enabledDesc = voiceInputSubtitle(),
            disabledDesc = null,
            icon = R.drawable.ic_microphone,
            disabled = false,
            chatId = chatId,
            functionDesc = getString(R.string.tile_voice_input_desc)
        )

        tileLangDetect = TileFragment.newInstance(
            preferences?.getAutoLangDetect() == true,
            true,
            getString(R.string.tile_ale_title),
            null,
            getString(R.string.on),
            getString(R.string.off),
            R.drawable.ic_language,
            false,
            chatId,
            getString(R.string.tile_ale_desc)
        )

        tileHandsFree = TileFragment.newInstance(
            preferences?.getHandsFreeMode() == true,
            true,
            getString(R.string.tile_hands_free_title),
            null,
            getString(R.string.on),
            getString(R.string.off),
            R.drawable.ic_microphone,
            false,
            chatId,
            getString(R.string.tile_hands_free_desc)
        )

        tileAutoSend = TileFragment.newInstance(
            preferences?.autoSend()!!,
            true,
            getString(R.string.tile_autosend_title),
            null,
            getString(R.string.on),
            getString(R.string.off),
            R.drawable.ic_send,
            false,
            chatId,
            getString(R.string.tile_autosend_desc)
        )

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

        tileTranscriptionSound = TileFragment.newInstance(
            preferences?.getTranscriptionDoneSound() == true,
            true,
            getString(R.string.tile_transcription_sound_title),
            null,
            getString(R.string.on),
            getString(R.string.off),
            R.drawable.ic_volume_up,
            false,
            chatId,
            getString(R.string.tile_transcription_sound_desc)
        )
    }

    private fun placeTiles() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.tile_tts, tileTTS!!)
            .replace(R.id.tile_voice, tileVoice!!)
            .replace(R.id.tile_voice_language, tileVoiceLanguage!!)
            .replace(R.id.tile_silent_mode, tileSilentMode!!)
            .replace(R.id.tile_always_speak, tileAlwaysSpeak!!)
            .replace(R.id.tile_stt, tileSTT!!)
            .replace(R.id.tile_auto_language_detection, tileLangDetect!!)
            .replace(R.id.tile_hands_free, tileHandsFree!!)
            .replace(R.id.tile_autosend, tileAutoSend!!)
            .replace(R.id.tile_hands_free_timing, tileHandsFreeTiming!!)
            .replace(R.id.tile_vad_method, tileVadMethod!!)
            .replace(R.id.tile_transcription_sound, tileTranscriptionSound!!)
            .commitNow()
    }

    private fun initLogic() {
        tileTTS?.setOnCheckedChangeListener { isChecked ->
            if (isChecked) {
                preferences?.setTtsEngine("openai")
                ttsEngine = "openai"
            } else {
                preferences?.setTtsEngine("google")
                ttsEngine = "google"
            }
            voice = if (!isChecked) preferences?.getVoice() ?: "" else preferences?.getOpenAIVoice() ?: ""
            tileVoice?.updateSubtitle(voice)
        }

        tileVoice?.setOnTileClickListener {
            val voiceSelectorDialogFragment: VoiceSelectorDialogFragment = VoiceSelectorDialogFragment.newInstance(if (ttsEngine == "google") preferences?.getVoice() ?: "" else preferences?.getOpenAIVoice() ?: "", chatId, ttsEngine)
            voiceSelectorDialogFragment.setVoiceSelectedListener(voiceSelectorListener)
            voiceSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "VoiceSelectorDialogFragment")
        }

        tileVoiceLanguage?.setOnTileClickListener {
            val languageSelectorDialogFragment: LanguageSelectorDialogFragment = LanguageSelectorDialogFragment.newInstance(language, chatId)
            languageSelectorDialogFragment.setStateChangedListener(languageChangedListener)
            languageSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "LanguageSelectorDialog")
        }

        tileSilentMode?.setOnCheckedChangeListener { isChecked ->
            if (isChecked) {
                preferences?.setSilence(true)
                preferences?.setNotSilence(false)
                tileAlwaysSpeak?.setChecked(false)
                tileAlwaysSpeak?.setEnabled(false)
            } else {
                preferences?.setSilence(false)
                tileAlwaysSpeak?.setEnabled(true)
            }
        }

        tileAlwaysSpeak?.setOnCheckedChangeListener { isChecked ->
            if (isChecked) {
                preferences?.setNotSilence(true)
                preferences?.setSilence(false)
                tileSilentMode?.setChecked(false)
                tileSilentMode?.setEnabled(false)
            } else {
                preferences?.setNotSilence(false)
                tileSilentMode?.setEnabled(true)
            }
        }

        tileSTT?.setOnTileClickListener {
            showVoiceInputEnginePicker()
        }

        tileLangDetect?.setOnCheckedChangeListener { isChecked ->
            if (isChecked) {
                preferences?.setAutoLangDetect(true)
            } else {
                preferences?.setAutoLangDetect(false)
            }
        }

        tileHandsFree?.setOnCheckedChangeListener { isChecked ->
            preferences?.setHandsFreeMode(isChecked)
            if (isChecked) {
                // Hands-free can't work without auto-send: the conversation
                // loop relies on each transcript being sent automatically
                // rather than parked in the input box. Force it on and mirror
                // that in the auto-send tile so the dependency is visible.
                preferences?.setAutoSend(true)
                tileAutoSend?.setChecked(true)
            }
        }

        tileAutoSend?.setOnCheckedChangeListener { isChecked ->
            if (isChecked) {
                preferences?.setAutoSend(true)
            } else {
                preferences?.setAutoSend(false)
            }
        }

        tileHandsFreeTiming?.setOnTileClickListener {
            showHandsFreeTimingDialog()
        }

        tileVadMethod?.setOnTileClickListener {
            vadMethodSelector()
        }

        tileTranscriptionSound?.setOnCheckedChangeListener { isChecked ->
            preferences?.setTranscriptionDoneSound(isChecked)
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
            .setPositiveButton(android.R.string.ok) { _, _ ->
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

    private fun voiceInputSubtitle(): String {
        val engine = preferences?.getAudioModel() ?: "google"
        return when (engine) {
            "whisper" -> getString(R.string.voice_engine_whisper_cloud)
            "whisper-local" -> {
                val model = preferences?.getActiveLocalWhisperModel() ?: ""
                if (model.isNotEmpty()) {
                    getString(R.string.voice_engine_whisper_local) + " · " + model
                } else {
                    getString(R.string.voice_engine_whisper_local)
                }
            }
            else -> getString(R.string.voice_engine_google)
        }
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
            .setPositiveButton(android.R.string.ok) { _, _ ->
                preferences?.setVadMethod(selectedId)
                tileVadMethod?.updateSubtitle(vadMethodSubtitle())
                if (selectedId == "webrtc" && !org.teslasoft.assistant.stt.WebRtcVadNative.ensureLoaded()) {
                    Toast.makeText(this, R.string.vad_webrtc_unavailable, Toast.LENGTH_LONG).show()
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
            .setPositiveButton(android.R.string.ok) { _, _ ->
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
            else -> getString(
                R.string.vad_method_subtitle_webrtc,
                getString(R.string.vad_method_webrtc),
                webRtcSensitivityShortLabel()
            )
        }
    }

    private fun showVoiceInputEnginePicker() {
        val engines = arrayOf("google", "whisper", "whisper-local")
        val labels = arrayOf(
            getString(R.string.voice_engine_google),
            getString(R.string.voice_engine_whisper_cloud),
            getString(R.string.voice_engine_whisper_local)
        )
        val current = engines.indexOf(preferences?.getAudioModel() ?: "google").coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.voice_engine_picker_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked = engines[which]
                preferences?.setAudioModel(picked)
                tileSTT?.updateSubtitle(voiceInputSubtitle())
                dialog.dismiss()
                if (picked == "whisper-local") {
                    startActivity(Intent(this, LocalWhisperModelsActivity::class.java))
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    override fun onResume() {
        super.onResume()
        tileSTT?.updateSubtitle(voiceInputSubtitle())
        Preferences.getPreferences(this, chatId)
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    private fun adjustPaddings() {
        WindowInsetsUtil.adjustPaddings(this, R.id.scrollable, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS), customPaddingBottom = (48 * resources.displayMetrics.density).roundToInt())
    }
}
