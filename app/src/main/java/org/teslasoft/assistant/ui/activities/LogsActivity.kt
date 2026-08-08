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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager

class LogsActivity : FragmentActivity() {

    companion object {
        /** An Error Log entry header immediately followed by the database-
         *  health tag (§15.15). Group 1 is the "[timestamp]" portion —
         *  exactly the part the owner wants rendered in red. The optional
         *  seconds / AM-PM pieces mirror Logger's header-matching regex so
         *  entries from older formats still match. */
        private val HEALTH_HEADER = Regex(
            "(?m)^(\\[\\d{4}-\\d{2}-\\d{2} \\d{1,2}:\\d{2}(?::\\d{2})?(?: [AP]M)?]) \\[DatabaseHealth]"
        )

        /** A whole "Outcome: Incomplete" or "Outcome: Empty" line in a Response
         *  Lifecycle entry — the outcomes the owner wants in red, so a cut-off
         *  or empty reply is easy to spot. Stopped, Cancelled and Complete stay
         *  the default color, which keeps them visually distinct from a failure. */
        private val LIFECYCLE_RED_OUTCOME = Regex("(?m)^Outcome: (?:Incomplete|Empty)$")
    }

    /**
     * Response Lifecycle rendering: color every "Outcome: Incomplete" and
     * "Outcome: Empty" line red. Nothing else is recolored, so intentional
     * Stopped/Cancelled outcomes and normal Complete outcomes remain plain and
     * clearly not failures.
     */
    private fun renderLifecycleLog(raw: String): CharSequence {
        if (raw.isEmpty() ||
            (!raw.contains("Outcome: Incomplete") && !raw.contains("Outcome: Empty"))
        ) return raw
        return try {
            val spannable = android.text.SpannableString(raw)
            val red = androidx.core.content.res.ResourcesCompat.getColor(resources, R.color.light_red, theme)
            for (match in LIFECYCLE_RED_OUTCOME.findAll(raw)) {
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(red),
                    match.range.first, match.range.last + 1,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            spannable
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * Error Log rendering (§15.15, owner B13 ruling): database-health lines'
     * date+time render in RED so they stand out when scanning; everything
     * else stays plain. Only the "crash" channel gets this pass.
     */
    private fun renderErrorLog(raw: String): CharSequence {
        if (raw.isEmpty() || !raw.contains("[DatabaseHealth]")) return raw
        return try {
            val spannable = android.text.SpannableString(raw)
            val red = androidx.core.content.res.ResourcesCompat.getColor(resources, R.color.light_red, theme)
            for (match in HEALTH_HEADER.findAll(raw)) {
                val group = match.groups[1] ?: continue
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(red),
                    group.range.first, group.range.last + 1,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            spannable
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * Show the correct header for this log. The Voice Debug log has a trailing
     * icon, so it uses the left-aligned icon-aware title; every other log uses
     * the plain centered title. The unused title view is hidden so only one is
     * ever laid out.
     */
    private fun setHeader(titleRes: Int, iconHeader: Boolean) {
        val text = getString(titleRes)
        this.title = text
        if (iconHeader) {
            activityLogsTitle?.text = text
            activityLogsTitle?.visibility = android.view.View.VISIBLE
            activityLogsTitleCentered?.visibility = android.view.View.GONE
        } else {
            activityLogsTitleCentered?.text = text
            activityLogsTitleCentered?.visibility = android.view.View.VISIBLE
            activityLogsTitle?.visibility = android.view.View.GONE
        }
    }

    /** The current log's stored contents, before ordering/spacing. */
    private fun rawForType(): String = when (logType) {
        "crash" -> Logger.getCrashLog(this)
        "event" -> Logger.getEventLog(this)
        "memory" -> Logger.getMemoryLog(this)
        "performance" -> Logger.getPerformanceLog(this)
        "whisper_perf" -> Logger.getWhisperPerfLog(this)
        "memory_usage" -> Logger.getMemoryUsageLog(this)
        "provider_fail" -> Logger.getProviderFailLog(this)
        "response_lifecycle" -> Logger.getResponseLifecycleLog(this)
        "image_gen_errors" -> Logger.getImageGenErrorsLog(this)
        "image_gen" -> Logger.getImageGenLog(this)
        else -> ""
    }

    /**
     * Render the current log into the view: order it (newest or oldest first)
     * and space its entries through [Logger.formatForDisplay], then apply the
     * per-log color pass (Error Log health headers, Response Lifecycle failed
     * outcomes). One path used by the initial load, the ordering toggle, and
     * clearing, so all three stay consistent.
     */
    private fun renderLog() {
        val ordered = Logger.formatForDisplay(rawForType(), newestFirst)
        textLog?.text = when (logType) {
            "crash" -> renderErrorLog(ordered)
            "response_lifecycle" -> renderLifecycleLog(ordered)
            else -> ordered
        }
    }

    private var btnClearLog: MaterialButton? = null
    private var btnCopyLog: MaterialButton? = null
    private var btnBack: ImageButton? = null
    private var btnVoiceAdvanced: ImageButton? = null
    private var actionBar: ConstraintLayout? = null
    private var activityLogsTitle: TextView? = null
    private var activityLogsTitleCentered: TextView? = null
    private var switchNewestFirst: MaterialSwitch? = null
    private var textLog: TextView? = null
    private var logType = ""
    private var newestFirst = true
    private var root: ConstraintLayout? = null
    private var preferences: Preferences? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        setContentView(R.layout.activity_logs)

        btnClearLog = findViewById(R.id.btn_clear_log)
        btnCopyLog = findViewById(R.id.btn_copy_log)
        btnBack = findViewById(R.id.btn_back)
        btnVoiceAdvanced = findViewById(R.id.btn_voice_advanced)
        actionBar = findViewById(R.id.action_bar)
        activityLogsTitle = findViewById(R.id.activity_logs_title)
        activityLogsTitleCentered = findViewById(R.id.activity_logs_title_centered)
        switchNewestFirst = findViewById(R.id.switch_newest_first)
        textLog = findViewById(R.id.text_log)
        root = findViewById(R.id.root)

        Thread {
            val extras = intent.extras
            var chatId = ""

            if (extras != null) {
                chatId = extras.getString("chatId", "")
            }

            preferences = Preferences.getPreferences(this, chatId)

            runOnUiThread {
                try {
                    logType = intent.extras?.getString("type").toString()

                    when (logType) {
                        // Only the Voice Debug log carries a trailing header icon
                        // (the jump to Advanced Voice settings), so it uses the
                        // icon-aware header; every other log uses the plain
                        // centered header with no icon.
                        "crash" -> setHeader(R.string.title_crash_log, iconHeader = false)
                        "event" -> {
                            setHeader(R.string.title_event_log, iconHeader = true)
                            // The voice pipeline writes its diagnostics here, so
                            // offer a direct hop to the screen that tunes it.
                            btnVoiceAdvanced?.visibility = android.view.View.VISIBLE
                            btnVoiceAdvanced?.setOnClickListener {
                                startActivity(Intent(this, VoiceAdvancedSettingsActivity::class.java))
                            }
                        }
                        "memory" -> setHeader(R.string.title_memory_log, iconHeader = false)
                        "performance" -> setHeader(R.string.title_performance_log, iconHeader = false)
                        "whisper_perf" -> setHeader(R.string.title_whisper_perf_log, iconHeader = false)
                        "memory_usage" -> setHeader(R.string.title_memory_usage_log, iconHeader = false)
                        "provider_fail" -> setHeader(R.string.title_provider_fail_log, iconHeader = false)
                        "response_lifecycle" -> setHeader(R.string.title_response_lifecycle_log, iconHeader = false)
                        "image_gen_errors" -> setHeader(R.string.title_image_gen_errors_log, iconHeader = false)
                        "image_gen" -> setHeader(R.string.title_image_gen_log, iconHeader = false)
                        else -> {
                            finish()
                            return@runOnUiThread
                        }
                    }

                    // Restore the remembered ordering and wire the toggle. Set
                    // the switch state before its listener so seeding it never
                    // counts as a user flip.
                    newestFirst = preferences?.getLogsNewestFirst() ?: true
                    switchNewestFirst?.isChecked = newestFirst
                    switchNewestFirst?.setOnCheckedChangeListener { _, checked ->
                        newestFirst = checked
                        preferences?.setLogsNewestFirst(checked)
                        renderLog()
                    }

                    renderLog()
                } catch (_: Exception) {
                    finish()
                }

                btnClearLog?.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.label_clear_log)
                        .setMessage(R.string.msg_clear_log_confirm)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            when (logType) {
                                "crash" -> Logger.clearCrashLog(this)
                                "event" -> Logger.clearEventLog(this)
                                "memory" -> Logger.clearMemoryLog(this)
                                "performance" -> Logger.clearPerformanceLog(this)
                                "whisper_perf" -> Logger.clearWhisperPerfLog(this)
                                "memory_usage" -> Logger.clearMemoryUsageLog(this)
                                "provider_fail" -> Logger.clearProviderFailLog(this)
                                "response_lifecycle" -> Logger.clearResponseLifecycleLog(this)
                                "image_gen_errors" -> Logger.clearImageGenErrorsLog(this)
                                "image_gen" -> Logger.clearImageGenLog(this)
                            }
                            renderLog()
                        }
                        .setNegativeButton(R.string.no) { _, _ -> }
                        .show()
                }

                btnCopyLog?.setOnClickListener {
                    val content = textLog?.text?.toString().orEmpty()
                    if (content.isBlank()) {
                        Toast.makeText(this, R.string.label_log_empty, Toast.LENGTH_SHORT).show()
                    } else {
                        // Copy to the clipboard so the user can paste the log to a
                        // developer or coding bot before clearing it. Local-only —
                        // the stored log already excludes secrets (see ERROR_CODES.md).
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(this.title ?: "log", content))
                        Toast.makeText(this, R.string.label_log_copied, Toast.LENGTH_SHORT).show()
                    }
                }

                btnBack?.setOnClickListener {
                    finish()
                }

                reloadAmoled()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        reloadAmoled()
    }

    private fun reloadAmoled() {
        try {
            if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack()!!) {
                window.setBackgroundDrawableResource(R.color.amoled_window_background)
                root?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme))
                actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
                val tint = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
                btnBack?.backgroundTintList = tint
                btnVoiceAdvanced?.backgroundTintList = tint
            } else {
                window.setBackgroundDrawableResource(R.color.window_background)
                root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
                actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
                val tint = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
                btnBack?.backgroundTintList = tint
                btnVoiceAdvanced?.backgroundTintList = tint
            }
        } catch (_: Exception) {
            window.setBackgroundDrawableResource(R.color.window_background)
            root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
            val tint = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = tint
        }
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
