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
import android.widget.LinearLayout
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
 * The "Alert & Debug" screen, opened from the single full-width tile under the
 * Debug header in Settings. Deliberately NOT tiles: the two error toggles read
 * better as inline switch rows (label + description), and the crash/event logs
 * read better as plain "label >" rows that simply open the log when tapped.
 *
 * The error toggles are global preferences (one error policy per app), matching
 * how they were stored before; chatId is only threaded through so the log
 * screen keeps the same intent contract it had when launched from Settings.
 *
 * The logs are intentionally always available — they are local-only and must
 * not be gated on the (telemetry) installation id (see CLAUDE.md).
 */
class AlertDebugMenuActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchShowChatErrors: MaterialSwitch? = null
    private var switchErrorSound: MaterialSwitch? = null
    private var rowCrashLog: LinearLayout? = null
    private var rowEventLog: LinearLayout? = null

    // Voice diagnostics logging toggles (moved here from Advanced voice &
    // debugging). One per detector; Silero gained its own so its logs are no
    // longer tied to the WebRTC toggle.
    private var switchLogEnergy: MaterialSwitch? = null
    private var switchLogWebrtc: MaterialSwitch? = null
    private var switchLogSilero: MaterialSwitch? = null

    // Audio Health: separate from VAD logging — microphone input-health stats.
    private var switchAudioHealth: MaterialSwitch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_alert_debug_menu)

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

        switchShowChatErrors = findViewById(R.id.switch_show_chat_errors)
        switchErrorSound = findViewById(R.id.switch_error_sound)
        rowCrashLog = findViewById(R.id.row_crash_log)
        rowEventLog = findViewById(R.id.row_event_log)

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
        switchShowChatErrors?.isChecked = p.showChatErrors()
        switchErrorSound?.isChecked = p.getErrorSound()
        switchLogEnergy?.isChecked = p.getVadLoggingEnergy()
        switchLogWebrtc?.isChecked = p.getVadLoggingWebrtc()
        switchLogSilero?.isChecked = p.getVadLoggingSilero()
        switchAudioHealth?.isChecked = p.getAudioHealthLogging()
    }

    private fun initLogic() {
        val p = preferences ?: return

        btnBack?.setOnClickListener { finish() }

        switchShowChatErrors?.setOnCheckedChangeListener { _, checked -> p.setShowChatErrors(checked) }
        switchErrorSound?.setOnCheckedChangeListener { _, checked -> p.setErrorSound(checked) }

        switchLogEnergy?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingEnergy(checked) }
        switchLogWebrtc?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingWebrtc(checked) }
        switchLogSilero?.setOnCheckedChangeListener { _, checked -> p.setVadLoggingSilero(checked) }
        switchAudioHealth?.setOnCheckedChangeListener { _, checked -> p.setAudioHealthLogging(checked) }

        rowCrashLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "crash").putExtra("chatId", chatId))
        }
        rowEventLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "event").putExtra("chatId", chatId))
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
