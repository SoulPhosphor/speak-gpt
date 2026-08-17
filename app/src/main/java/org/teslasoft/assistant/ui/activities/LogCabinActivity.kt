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
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Log Cabin: every diagnostic log in one place, reached from its own row
 * in Settings. Originally seeded with the log-opening rows that used to live
 * under the "Logs" header in Alerts, Errors & Logs (owner instruction, Aug 16
 * 2026) — same rows, same LogsActivity destinations, unchanged behavior — and
 * grows with every log added since. The toggles and retention fields that
 * configure each log stay in Alerts, Errors & Logs; only the "open this log"
 * rows live here.
 *
 * chatId is only threaded through so LogsActivity keeps the same intent
 * contract it already had. The logs are intentionally always available —
 * they are local-only and must not be gated on the (telemetry) installation
 * id (see CLAUDE.md).
 */
class LogCabinActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var rowCrashLog: LinearLayout? = null
    private var rowEventLog: LinearLayout? = null
    private var rowMemoryLog: LinearLayout? = null
    private var rowWhisperPerfLog: LinearLayout? = null
    private var rowMemoryUsageLog: LinearLayout? = null
    private var rowProviderFailLog: LinearLayout? = null
    private var rowResponseLifecycleLog: LinearLayout? = null
    private var rowImageGenErrorsLog: LinearLayout? = null
    private var rowImageGenLog: LinearLayout? = null
    private var rowTtsLifecycleLog: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_log_cabin)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)

        rowCrashLog = findViewById(R.id.row_crash_log)
        rowEventLog = findViewById(R.id.row_event_log)
        rowMemoryLog = findViewById(R.id.row_memory_log)
        rowWhisperPerfLog = findViewById(R.id.row_whisper_perf_log)
        rowMemoryUsageLog = findViewById(R.id.row_memory_usage_log)
        rowProviderFailLog = findViewById(R.id.row_provider_fail_log)
        rowResponseLifecycleLog = findViewById(R.id.row_response_lifecycle_log)
        rowImageGenErrorsLog = findViewById(R.id.row_image_gen_errors_log)
        rowImageGenLog = findViewById(R.id.row_image_gen_log)
        rowTtsLifecycleLog = findViewById(R.id.row_tts_lifecycle_log)
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

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        rowCrashLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "crash").putExtra("chatId", chatId))
        }
        rowEventLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "event").putExtra("chatId", chatId))
        }
        rowMemoryLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "memory").putExtra("chatId", chatId))
        }
        rowWhisperPerfLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "whisper_perf").putExtra("chatId", chatId))
        }
        rowMemoryUsageLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "memory_usage").putExtra("chatId", chatId))
        }
        rowProviderFailLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "provider_fail").putExtra("chatId", chatId))
        }
        rowResponseLifecycleLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "response_lifecycle").putExtra("chatId", chatId))
        }
        rowImageGenErrorsLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "image_gen_errors").putExtra("chatId", chatId))
        }
        rowImageGenLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "image_gen").putExtra("chatId", chatId))
        }
        rowTtsLifecycleLog?.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java).putExtra("type", "tts_lifecycle").putExtra("chatId", chatId))
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
