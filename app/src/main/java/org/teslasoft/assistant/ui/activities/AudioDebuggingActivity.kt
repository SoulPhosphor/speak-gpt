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
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager

/**
 * "Audio Debugging" — everything microphone/voice related in one place, reached
 * from the Voice Debugging tile in Voice & Speech (and from a shortcut row in
 * the Alerts, Errors & Logs menu). Holds the transcription-finished chime, the
 * per-detector VAD logging toggles (Energy/WebRTC/Silero), and microphone Audio
 * Health. Split out of the old Alert & Debug screen so that audio things live
 * together and non-audio error settings stay with the error logs.
 *
 * Plain inline switch rows, not tiles — same convention as the sibling debug
 * screens. All toggles are global preferences; chatId is threaded through only
 * to keep the Preferences contract identical to the old call sites.
 */
class AudioDebuggingActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchTranscriptionSound: MaterialSwitch? = null
    private var switchLogEnergy: MaterialSwitch? = null
    private var switchLogWebrtc: MaterialSwitch? = null
    private var switchLogSilero: MaterialSwitch? = null
    private var switchAudioHealth: MaterialSwitch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_audio_debugging)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        loadValues()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)

        switchTranscriptionSound = findViewById(R.id.switch_transcription_sound)
        switchLogEnergy = findViewById(R.id.switch_log_energy)
        switchLogWebrtc = findViewById(R.id.switch_log_webrtc)
        switchLogSilero = findViewById(R.id.switch_log_silero)
        switchAudioHealth = findViewById(R.id.switch_audio_health)
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

    private fun loadValues() {
        val p = preferences ?: return
        switchTranscriptionSound?.isChecked = p.getTranscriptionDoneSound()
        switchLogEnergy?.isChecked = p.getVadLoggingEnergy()
        switchLogWebrtc?.isChecked = p.getVadLoggingWebrtc()
        switchLogSilero?.isChecked = p.getVadLoggingSilero()
        switchAudioHealth?.isChecked = p.getAudioHealthLogging()
    }

    private fun initLogic() {
        val p = preferences ?: return

        btnBack?.setOnClickListener { finish() }

        switchTranscriptionSound?.setOnCheckedChangeListener { _, checked -> p.setTranscriptionDoneSound(checked) }
        switchLogEnergy?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingEnergy(checked) }
        switchLogWebrtc?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingWebrtc(checked) }
        switchLogSilero?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingSilero(checked) }
        switchAudioHealth?.setOnCheckedChangeListener { _, checked -> p.setAudioHealthLogging(checked) }
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
