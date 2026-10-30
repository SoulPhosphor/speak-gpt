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
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

class LogsActivity : SettingsPageActivity() {

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
        "tts_lifecycle" -> Logger.getTtsLifecycleLog(this)
        else -> ""
    }

    /**
     * Render the current log into the view: split and order its entries
     * (newest or oldest first) through [Logger.entriesForDisplay], then give
     * each entry its own view with the per-log color pass (Error Log health
     * headers, Response Lifecycle failed outcomes) and, while Show Copy Button
     * on Logs is on, a Copy for that entry. One path used by the initial load,
     * the ordering toggle, and clearing, so all three stay consistent.
     */
    private fun renderLog() {
        val entries = Logger.entriesForDisplay(rawForType(), newestFirst)
        displayedEntries = entries
        displayedLog = entries.joinToString("\n\n")
        val showCopy = preferences?.getShowLogCopyButtons() ?: true
        logEntries?.removeAllViews()
        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.view_log_entry, logEntries, false)
            row.findViewById<TextView>(R.id.log_entry_text).text = when (logType) {
                "crash" -> renderErrorLog(entry)
                "response_lifecycle" -> renderLifecycleLog(entry)
                else -> entry
            }
            row.findViewById<MaterialButton>(R.id.btn_log_entry_copy).apply {
                visibility = if (showCopy) android.view.View.VISIBLE else android.view.View.GONE
                setOnClickListener { copyToClipboard(entry) }
            }
            logEntries?.addView(row)
        }
    }

    /** Copies [content]; false when the phone refused it (too large for the
     *  clipboard), with nothing shown. */
    private fun copyToClipboard(content: String): Boolean {
        // Copy to the clipboard so the user can paste the log to a developer
        // or coding bot before clearing it. Local-only — the stored log
        // already excludes secrets (see ERROR_CODES.md).
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(this.title ?: "log", content))
        } catch (_: RuntimeException) {
            return false
        }
        Toast.makeText(this, R.string.label_log_copied, Toast.LENGTH_SHORT).show()
        return true
    }

    /** Export Log pop-up: Copy All did not fit on the clipboard. */
    private fun showExportDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_log_export, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.log_export_title)
            .setView(view)
            .create()
        view.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.cancel)
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                val json = view.findViewById<android.widget.RadioGroup>(R.id.log_export_format)
                    .checkedRadioButtonId == R.id.radio_log_export_json
                dialog.dismiss()
                pendingExport = if (json) exportJson() else displayedLog
                exportLauncher.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(if (json) "application/json" else "text/plain")
                        .putExtra(Intent.EXTRA_TITLE, "${this@LogsActivity.title}.${if (json) "json" else "txt"}")
                )
            }
        }
        dialog.show()
    }

    /** The displayed entries, in their current order, as JSON. */
    private fun exportJson(): String = org.json.JSONObject()
        .put("log", title?.toString().orEmpty())
        .put("exported_at", java.time.OffsetDateTime.now().toString())
        .put("entries", org.json.JSONArray(displayedEntries))
        .toString(2)

    private var pendingExport: String? = null

    private val exportLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            val content = pendingExport
            pendingExport = null
            val uri = result.data?.data
            if (result.resultCode != RESULT_OK || uri == null || content == null) return@registerForActivityResult
            Thread {
                val saved = try {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } != null
                } catch (_: Exception) {
                    false
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (saved) R.string.message_saved else R.string.log_export_save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }

    private var btnClearLog: MaterialButton? = null
    private var btnCopyLog: MaterialButton? = null
    private var btnBack: ImageButton? = null
    private var btnVoiceAdvanced: ImageButton? = null
    private var actionBar: ConstraintLayout? = null
    private var activityLogsTitle: TextView? = null
    private var activityLogsTitleCentered: TextView? = null
    private var switchNewestFirst: MaterialSwitch? = null
    private var logEntries: android.widget.LinearLayout? = null
    private var displayedLog = ""
    private var displayedEntries: List<String> = emptyList()
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
        logEntries = findViewById(R.id.log_entries)
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
                        "tts_lifecycle" -> setHeader(R.string.title_tts_lifecycle_log, iconHeader = false)
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
                    MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
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
                                "tts_lifecycle" -> Logger.clearTtsLifecycleLog(this)
                            }
                            renderLog()
                        }
                        .setNegativeButton(R.string.no) { _, _ -> }
                        .show()
                }

                btnCopyLog?.setOnClickListener {
                    if (displayedLog.isBlank()) {
                        Toast.makeText(this, R.string.label_log_empty, Toast.LENGTH_SHORT).show()
                    } else if (!copyToClipboard(displayedLog)) {
                        showExportDialog()
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
                root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
                ScreenChrome.apply(this, actionBar, btnBack, btnVoiceAdvanced)
            }
        } catch (_: Exception) {
            root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
            ScreenChrome.apply(this, actionBar, btnBack, btnVoiceAdvanced)
        }
    }

    /** Edge-to-edge (API 35+): the header extends under the status bar and
     *  the bottom actions clear the navigation bar, as on the other settings
     *  screens. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val insets = window.decorView.rootWindowInsets
            actionBar?.setPadding(0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0)
            val params = btnClearLog?.layoutParams as? ConstraintLayout.LayoutParams ?: return
            params.bottomMargin = (24 * resources.displayMetrics.density).toInt() +
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            btnClearLog?.layoutParams = params
        } catch (_: Exception) { /* Window insets are not available yet. */ }
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
