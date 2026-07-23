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
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The "Alerts, Errors & Logs" screen, opened from the single full-width tile
 * under the Debug header in Settings. Holds the NON-audio diagnostics: the two
 * model-error toggles (show chat errors, error sound), a shortcut row down to
 * Audio Debugging (where everything microphone/voice related now lives), and
 * the crash/event log rows. The audio toggles (VAD logging, Audio Health,
 * transcription chime) moved out to [AudioDebuggingActivity] so audio settings
 * sit together under Voice and aren't buried in a non-voice menu.
 *
 * Deliberately NOT tiles: the error toggles read better as inline switch rows
 * (label + description), and the shortcut/log rows read better as plain
 * "label >" rows that open the target when tapped.
 *
 * The error toggles are global preferences (one error policy per app); chatId
 * is only threaded through so the log screen keeps the same intent contract it
 * had when launched from Settings. The logs are intentionally always available
 * — they are local-only and must not be gated on the (telemetry) installation
 * id (see CLAUDE.md).
 */
class AlertDebugMenuActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchShowChatErrors: MaterialSwitch? = null
    private var switchErrorSound: MaterialSwitch? = null
    private var switchMemoryDebug: MaterialSwitch? = null
    private var switchWhisperPerf: MaterialSwitch? = null
    private var switchMemoryUsage: MaterialSwitch? = null
    // Per-log retention blanks (owner spec, July 23 2026). Each of the three
    // configurable logs has its own "Maximum Logs Saved" + "Maximum Days Saved".
    private var fieldMemoryMaxLogs: EditText? = null
    private var fieldMemoryMaxDays: EditText? = null
    private var fieldWhisperMaxLogs: EditText? = null
    private var fieldWhisperMaxDays: EditText? = null
    private var fieldMemUsageMaxLogs: EditText? = null
    private var fieldMemUsageMaxDays: EditText? = null
    // Shortcut down to the audio-only diagnostics, so a user who lands here
    // looking for VAD logging doesn't have to know it lives under Voice.
    private var rowAudioDebugging: LinearLayout? = null
    private var rowCrashLog: LinearLayout? = null
    private var rowEventLog: LinearLayout? = null
    private var rowMemoryLog: LinearLayout? = null
    private var rowWhisperPerfLog: LinearLayout? = null
    private var rowMemoryUsageLog: LinearLayout? = null

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
        switchMemoryDebug = findViewById(R.id.switch_memory_debug)
        switchWhisperPerf = findViewById(R.id.switch_whisper_perf)
        switchMemoryUsage = findViewById(R.id.switch_memory_usage)
        fieldMemoryMaxLogs = findViewById(R.id.field_memory_max_logs)
        fieldMemoryMaxDays = findViewById(R.id.field_memory_max_days)
        fieldWhisperMaxLogs = findViewById(R.id.field_whisper_max_logs)
        fieldWhisperMaxDays = findViewById(R.id.field_whisper_max_days)
        fieldMemUsageMaxLogs = findViewById(R.id.field_memusage_max_logs)
        fieldMemUsageMaxDays = findViewById(R.id.field_memusage_max_days)
        rowAudioDebugging = findViewById(R.id.row_audio_debugging)
        rowCrashLog = findViewById(R.id.row_crash_log)
        rowEventLog = findViewById(R.id.row_event_log)
        rowMemoryLog = findViewById(R.id.row_memory_log)
        rowWhisperPerfLog = findViewById(R.id.row_whisper_perf_log)
        rowMemoryUsageLog = findViewById(R.id.row_memory_usage_log)
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
        switchMemoryDebug?.isChecked = p.getMemoryDebugLogging()
        switchWhisperPerf?.isChecked = p.getWhisperPerfLogging()
        switchMemoryUsage?.isChecked = p.getMemoryUsageLogging()

        // Show each log's currently-configured retention. Set BEFORE the
        // text-watchers are wired in initLogic, so seeding the fields never
        // counts as a user edit and never re-saves a default over a real value.
        fieldMemoryMaxLogs?.setText(p.getMemoryLogMaxEntries().toString())
        fieldMemoryMaxDays?.setText(p.getMemoryLogMaxDays().toString())
        fieldWhisperMaxLogs?.setText(p.getWhisperPerfLogMaxEntries().toString())
        fieldWhisperMaxDays?.setText(p.getWhisperPerfLogMaxDays().toString())
        fieldMemUsageMaxLogs?.setText(p.getMemoryUsageLogMaxEntries().toString())
        fieldMemUsageMaxDays?.setText(p.getMemoryUsageLogMaxDays().toString())
    }

    private fun initLogic() {
        val p = preferences ?: return

        btnBack?.setOnClickListener { finish() }

        switchShowChatErrors?.setOnCheckedChangeListener { _, checked -> p.setShowChatErrors(checked) }
        switchErrorSound?.setOnCheckedChangeListener { _, checked -> p.setErrorSound(checked) }
        switchMemoryDebug?.setOnCheckedChangeListener { _, checked -> p.setMemoryDebugLogging(checked) }
        switchWhisperPerf?.setOnCheckedChangeListener { _, checked -> p.setWhisperPerfLogging(checked) }
        switchMemoryUsage?.setOnCheckedChangeListener { _, checked -> p.setMemoryUsageLogging(checked) }

        wireRetentionField(
            fieldMemoryMaxLogs, Preferences.LOG_MAX_ENTRIES_LIMIT,
            { p.getMemoryLogMaxEntries() }, { v -> p.setMemoryLogMaxEntries(v) },
            R.string.dialog_max_logs_exceeded
        )
        wireRetentionField(
            fieldMemoryMaxDays, Preferences.LOG_MAX_DAYS_LIMIT,
            { p.getMemoryLogMaxDays() }, { v -> p.setMemoryLogMaxDays(v) },
            R.string.dialog_max_days_exceeded
        )
        wireRetentionField(
            fieldWhisperMaxLogs, Preferences.LOG_MAX_ENTRIES_LIMIT,
            { p.getWhisperPerfLogMaxEntries() }, { v -> p.setWhisperPerfLogMaxEntries(v) },
            R.string.dialog_max_logs_exceeded
        )
        wireRetentionField(
            fieldWhisperMaxDays, Preferences.LOG_MAX_DAYS_LIMIT,
            { p.getWhisperPerfLogMaxDays() }, { v -> p.setWhisperPerfLogMaxDays(v) },
            R.string.dialog_max_days_exceeded
        )
        wireRetentionField(
            fieldMemUsageMaxLogs, Preferences.LOG_MAX_ENTRIES_LIMIT,
            { p.getMemoryUsageLogMaxEntries() }, { v -> p.setMemoryUsageLogMaxEntries(v) },
            R.string.dialog_max_logs_exceeded
        )
        wireRetentionField(
            fieldMemUsageMaxDays, Preferences.LOG_MAX_DAYS_LIMIT,
            { p.getMemoryUsageLogMaxDays() }, { v -> p.setMemoryUsageLogMaxDays(v) },
            R.string.dialog_max_days_exceeded
        )

        rowAudioDebugging?.setOnClickListener {
            startActivity(Intent(this, AudioDebuggingActivity::class.java).putExtra("chatId", chatId))
        }

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
    }

    /**
     * Wire one retention blank (owner spec, July 23 2026). "Saved as typed":
     * any in-range value is persisted immediately on each edit. The ceiling is
     * enforced on commit (focus loss / IME Done) rather than mid-keystroke so a
     * dialog can't interrupt typing: a value above [cap] is clamped to [cap],
     * the field updated to show the clamp, the value stored, and the
     * owner-worded single-button dialog shown; a blank or zero field is
     * restored to the stored value. Re-seeding the text with an in-range value
     * can't recurse — the in-range branch never rewrites the field.
     */
    private fun wireRetentionField(
        targetField: EditText?,
        cap: Int,
        getStored: () -> Int,
        store: (Int) -> Unit,
        overCapMessageRes: Int
    ) {
        val editText = targetField ?: return

        editText.doAfterTextChanged { text ->
            val value = text?.toString()?.toIntOrNull()
            if (value != null && value in 1..cap) store(value)
        }

        val commit = {
            val value = editText.text?.toString()?.toIntOrNull()
            when {
                value == null || value < 1 -> editText.setText(getStored().toString())
                value > cap -> {
                    store(cap)
                    editText.setText(cap.toString())
                    editText.setSelection(editText.text.length)
                    showCapDialog(overCapMessageRes)
                }
                else -> store(value)
            }
        }

        editText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) commit()
            false
        }
    }

    /**
     * The owner's single-button ("Okay") over-ceiling notice. Reuses the shared
     * single-action dialog shape (dialog_single_action.xml +
     * App.MaterialAlertDialog): the statement is the dialog TITLE (a single
     * sentence with no separate subtext, per the house dialog rule) and the one
     * primary button dismisses it.
     */
    private fun showCapDialog(messageRes: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(messageRes)
            .setCancelable(true)
            .setView(view)
            .create()
        view.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.okay)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
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
