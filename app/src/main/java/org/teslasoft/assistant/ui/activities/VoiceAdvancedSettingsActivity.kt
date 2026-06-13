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
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import kotlin.math.roundToInt

/**
 * The "Advanced & debugging" screen for everything voice. Deliberately NOT
 * tiles: these are dense, explanation-heavy controls, and the tile grid was
 * unreadable for them ("you can't even read half of the things"). Plain
 * full-width rows: switch rows carry their description inline; sliders show
 * the live value in their title.
 *
 * Every control writes its (global) preference immediately, matching the
 * rest of the app. The voice pipeline reads these per turn, so changes apply
 * from the next mic turn without restarting anything.
 */
class VoiceAdvancedSettingsActivity : FragmentActivity() {

    private var preferences: Preferences? = null

    private var labelMinRms: TextView? = null
    private var labelFloorFactor: TextView? = null
    private var labelCeiling: TextView? = null
    private var labelMinSpeech: TextView? = null
    private var labelBeamSize: TextView? = null
    private var labelTemperature: TextView? = null
    private var labelTtsRate: TextView? = null
    private var labelTtsPitch: TextView? = null

    private var switchEnergyGate: MaterialSwitch? = null
    private var sliderMinRms: Slider? = null
    private var sliderFloorFactor: Slider? = null
    private var sliderCeiling: Slider? = null
    private var sliderMinSpeech: Slider? = null
    private var switchHysteresis: MaterialSwitch? = null
    private var labelHystExit: TextView? = null
    private var sliderHystExit: Slider? = null
    private var labelHangover: TextView? = null
    private var sliderHangover: Slider? = null
    private var labelSileroThreshold: TextView? = null
    private var sliderSileroThreshold: Slider? = null
    private var btnResetVad: MaterialButton? = null

    private var radioDecoderBeam: RadioButton? = null
    private var radioDecoderGreedy: RadioButton? = null
    private var sliderBeamSize: Slider? = null
    private var sliderTemperature: Slider? = null
    private var switchSuppressBlank: MaterialSwitch? = null
    private var switchSingleSegment: MaterialSwitch? = null
    private var fieldInitialPrompt: TextInputEditText? = null
    private var switchPrevContext: MaterialSwitch? = null
    private var switchCleanup: MaterialSwitch? = null
    private var switchWhisperDebug: MaterialSwitch? = null

    private var sliderTtsRate: Slider? = null
    private var sliderTtsPitch: Slider? = null

    private var switchLogEnergy: MaterialSwitch? = null
    private var switchLogWebrtc: MaterialSwitch? = null
    private var btnOpenEventLog: MaterialButton? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_advanced)

        // These settings are global (one voice pipeline per device), so no
        // chatId is needed regardless of where the screen was opened from.
        preferences = Preferences.getPreferences(this, "")

        bindViews()
        applyTheme()
        loadValues()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)

        labelMinRms = findViewById(R.id.label_min_rms)
        labelFloorFactor = findViewById(R.id.label_floor_factor)
        labelCeiling = findViewById(R.id.label_ceiling)
        labelMinSpeech = findViewById(R.id.label_min_speech)
        labelBeamSize = findViewById(R.id.label_beam_size)
        labelTemperature = findViewById(R.id.label_temperature)
        labelTtsRate = findViewById(R.id.label_tts_rate)
        labelTtsPitch = findViewById(R.id.label_tts_pitch)

        switchEnergyGate = findViewById(R.id.switch_energy_gate)
        sliderMinRms = findViewById(R.id.slider_min_rms)
        sliderFloorFactor = findViewById(R.id.slider_floor_factor)
        sliderCeiling = findViewById(R.id.slider_ceiling)
        sliderMinSpeech = findViewById(R.id.slider_min_speech)
        switchHysteresis = findViewById(R.id.switch_hysteresis)
        labelHystExit = findViewById(R.id.label_hyst_exit)
        sliderHystExit = findViewById(R.id.slider_hyst_exit)
        labelHangover = findViewById(R.id.label_hangover)
        sliderHangover = findViewById(R.id.slider_hangover)
        labelSileroThreshold = findViewById(R.id.label_silero_threshold)
        sliderSileroThreshold = findViewById(R.id.slider_silero_threshold)
        btnResetVad = findViewById(R.id.btn_reset_vad)

        radioDecoderBeam = findViewById(R.id.radio_decoder_beam)
        radioDecoderGreedy = findViewById(R.id.radio_decoder_greedy)
        sliderBeamSize = findViewById(R.id.slider_beam_size)
        sliderTemperature = findViewById(R.id.slider_temperature)
        switchSuppressBlank = findViewById(R.id.switch_suppress_blank)
        switchSingleSegment = findViewById(R.id.switch_single_segment)
        fieldInitialPrompt = findViewById(R.id.field_initial_prompt)
        switchPrevContext = findViewById(R.id.switch_prev_context)
        switchCleanup = findViewById(R.id.switch_cleanup)
        switchWhisperDebug = findViewById(R.id.switch_whisper_debug)

        sliderTtsRate = findViewById(R.id.slider_tts_rate)
        sliderTtsPitch = findViewById(R.id.slider_tts_pitch)

        switchLogEnergy = findViewById(R.id.switch_log_energy)
        switchLogWebrtc = findViewById(R.id.switch_log_webrtc)
        btnOpenEventLog = findViewById(R.id.btn_open_event_log)
    }

    private fun applyTheme() {
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true)

        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }
    }

    /** Snap a stored value onto the slider's grid so Slider never rejects it. */
    private fun snap(value: Float, slider: Slider): Float {
        val step = slider.stepSize
        if (step <= 0f) return value.coerceIn(slider.valueFrom, slider.valueTo)
        val steps = ((value - slider.valueFrom) / step).roundToInt()
        return (slider.valueFrom + steps * step).coerceIn(slider.valueFrom, slider.valueTo)
    }

    private fun loadValues() {
        val p = preferences ?: return

        switchEnergyGate?.isChecked = p.getVadEnergyGateEnabled()
        sliderMinRms?.let { it.value = snap(p.getVadMinSpeechRms().toFloat(), it) }
        sliderFloorFactor?.let { it.value = snap(p.getVadFloorFactor(), it) }
        sliderCeiling?.let { it.value = snap(p.getVadEnergyCeiling().toFloat(), it) }
        sliderMinSpeech?.let { it.value = snap(p.getVadMinSpeechMs().toFloat(), it) }
        switchHysteresis?.isChecked = p.getVadHysteresisEnabled()
        sliderHystExit?.let { it.value = snap(p.getVadHysteresisExitPercent().toFloat(), it) }
        sliderHystExit?.isEnabled = p.getVadHysteresisEnabled()
        sliderHangover?.let { it.value = snap(p.getVadHangoverMs().toFloat(), it) }
        sliderSileroThreshold?.let { it.value = snap(p.getVadSileroThreshold().toFloat(), it) }

        val beam = p.getWhisperDecoder() != "greedy"
        radioDecoderBeam?.isChecked = beam
        radioDecoderGreedy?.isChecked = !beam
        sliderBeamSize?.let { it.value = snap(p.getWhisperBeamSize().toFloat(), it) }
        sliderBeamSize?.isEnabled = beam
        sliderTemperature?.let { it.value = snap(p.getWhisperTemperature(), it) }
        switchSuppressBlank?.isChecked = p.getWhisperSuppressBlank()
        switchSingleSegment?.isChecked = p.getWhisperSingleSegment()
        fieldInitialPrompt?.setText(p.getWhisperInitialPrompt())
        switchPrevContext?.isChecked = p.getWhisperUsePrevContext()
        switchCleanup?.isChecked = p.getWhisperCleanupTranscript()
        switchWhisperDebug?.isChecked = p.getWhisperDebugParams()

        sliderTtsRate?.let { it.value = snap(p.getTtsSpeechRate(), it) }
        sliderTtsPitch?.let { it.value = snap(p.getTtsPitch(), it) }

        switchLogEnergy?.isChecked = p.getVadLoggingEnergy()
        switchLogWebrtc?.isChecked = p.getVadLoggingWebrtc()

        refreshLabels()
    }

    /** Slider titles double as live value readouts. */
    private fun refreshLabels() {
        labelMinRms?.text = "${getString(R.string.adv_min_rms_title)}: ${sliderMinRms?.value?.toInt() ?: 0}"
        labelFloorFactor?.text = "${getString(R.string.adv_floor_factor_title)}: ${"%.1f".format(sliderFloorFactor?.value ?: 0f)}"
        labelCeiling?.text = "${getString(R.string.adv_ceiling_title)}: ${sliderCeiling?.value?.toInt() ?: 0}"
        labelMinSpeech?.text = "${getString(R.string.adv_min_speech_title)}: ${sliderMinSpeech?.value?.toInt() ?: 0} ms"
        labelHystExit?.text = "${getString(R.string.adv_hyst_exit_title)}: ${sliderHystExit?.value?.toInt() ?: 0}%"
        labelHangover?.text = "${getString(R.string.adv_hangover_title)}: ${sliderHangover?.value?.toInt() ?: 0} ms"
        labelSileroThreshold?.text = "${getString(R.string.adv_silero_threshold_title)}: ${sliderSileroThreshold?.value?.toInt() ?: 0}%"
        labelBeamSize?.text = "${getString(R.string.adv_beam_size_title)}: ${sliderBeamSize?.value?.toInt() ?: 0}"
        labelTemperature?.text = "${getString(R.string.adv_temperature_title)}: ${"%.2f".format(sliderTemperature?.value ?: 0f)}"
        labelTtsRate?.text = "${getString(R.string.adv_tts_rate_title)}: ${"%.1f".format(sliderTtsRate?.value ?: 1f)}x"
        labelTtsPitch?.text = "${getString(R.string.adv_tts_pitch_title)}: ${"%.1f".format(sliderTtsPitch?.value ?: 1f)}x"
    }

    private fun initLogic() {
        val p = preferences ?: return

        btnBack?.setOnClickListener { finish() }

        switchEnergyGate?.setOnCheckedChangeListener { _, checked -> p.setVadEnergyGateEnabled(checked) }
        sliderMinRms?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setVadMinSpeechRms(value.toInt())
            refreshLabels()
        }
        sliderFloorFactor?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setVadFloorFactor(value)
            refreshLabels()
        }
        sliderCeiling?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setVadEnergyCeiling(value.toInt())
            refreshLabels()
        }
        sliderMinSpeech?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setVadMinSpeechMs(value.toInt())
            refreshLabels()
        }
        switchHysteresis?.setOnCheckedChangeListener { _, checked ->
            p.setVadHysteresisEnabled(checked)
            sliderHystExit?.isEnabled = checked
        }
        sliderHystExit?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setVadHysteresisExitPercent(value.toInt())
            refreshLabels()
        }
        sliderHangover?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setVadHangoverMs(value.toInt())
            refreshLabels()
        }
        sliderSileroThreshold?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setVadSileroThreshold(value.toInt())
            refreshLabels()
        }
        btnResetVad?.setOnClickListener {
            p.setVadEnergyGateEnabled(true)
            p.setVadMinSpeechRms(600)
            p.setVadFloorFactor(2.5f)
            p.setVadEnergyCeiling(1400)
            p.setVadMinSpeechMs(0)
            p.setVadHysteresisEnabled(true)
            p.setVadHysteresisExitPercent(50)
            p.setVadHangoverMs(0)
            p.setVadSileroThreshold(50)
            loadValues()
        }

        radioDecoderBeam?.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                p.setWhisperDecoder("beam")
                sliderBeamSize?.isEnabled = true
            }
        }
        radioDecoderGreedy?.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                p.setWhisperDecoder("greedy")
                sliderBeamSize?.isEnabled = false
            }
        }
        sliderBeamSize?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setWhisperBeamSize(value.toInt())
            refreshLabels()
        }
        sliderTemperature?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setWhisperTemperature(value)
            refreshLabels()
        }
        switchSuppressBlank?.setOnCheckedChangeListener { _, checked -> p.setWhisperSuppressBlank(checked) }
        switchSingleSegment?.setOnCheckedChangeListener { _, checked -> p.setWhisperSingleSegment(checked) }
        fieldInitialPrompt?.doAfterTextChanged { text -> p.setWhisperInitialPrompt(text?.toString()?.trim() ?: "") }
        switchPrevContext?.setOnCheckedChangeListener { _, checked -> p.setWhisperUsePrevContext(checked) }
        switchCleanup?.setOnCheckedChangeListener { _, checked -> p.setWhisperCleanupTranscript(checked) }
        switchWhisperDebug?.setOnCheckedChangeListener { _, checked -> p.setWhisperDebugParams(checked) }

        sliderTtsRate?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setTtsSpeechRate(value)
            refreshLabels()
        }
        sliderTtsPitch?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) p.setTtsPitch(value)
            refreshLabels()
        }

        switchLogEnergy?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingEnergy(checked) }
        switchLogWebrtc?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingWebrtc(checked) }
        btnOpenEventLog?.setOnClickListener {
            val intent = Intent(this, LogsActivity::class.java)
            intent.putExtra("type", "event")
            startActivity(intent)
        }
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
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }
}
