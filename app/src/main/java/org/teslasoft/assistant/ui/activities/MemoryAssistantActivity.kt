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
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.memory.ArchivistRunRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistFailure
import org.teslasoft.assistant.service.MemoryAnalysisForegroundService
import org.teslasoft.assistant.service.MemoryAnalysisState
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.DatabaseRecoveryFlows
import org.teslasoft.assistant.ui.activities.memory.MemoryBrowserActivity
import org.teslasoft.assistant.ui.activities.memory.MemoryBrowserFilterState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Memory Assistant — the Phase 6 Archivist's user-facing surface. Layout
 * and wording are the owner's approved design
 * (`Memory System/memory_assistant_design.md` §1–§2 + the July 8 2026 evening
 * answers) plus the owner-sanctioned status/failure wording implemented
 * verbatim from `Memory System/archivist_status_wording_spec.md`: the
 * not-ready state sits ABOVE the disabled run button; every post-run state
 * (full/partial failure with reasons, nothing to extract, no new memories,
 * interrupted) shows BENEATH it with a Title Case label, an explanation, and
 * an action button — never a silent reset, never color alone. Recent Memory
 * Analysis lists the last five runs (date → information → Rerun on the far
 * right) with the "Some Memories Deleted Later" badge where it applies.
 */
class MemoryAssistantActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var factSinceRun: TextView? = null
    private var factPending: TextView? = null
    private var factLastRun: TextView? = null
    private var notReadyContainer: LinearLayout? = null
    private var btnSetup: MaterialButton? = null
    private var btnAnalyze: MaterialButton? = null

    // A3 (§15.2a): the hard database-health block — stronger than not-ready,
    // with WORKING Repair / Revert buttons (owner: "make those buttons that
    // work").
    private var degradedContainer: LinearLayout? = null
    private var btnDegradedRepair: MaterialButton? = null
    private var btnDegradedRevert: MaterialButton? = null
    private var analysisProgressContainer: LinearLayout? = null
    private var analysisBar: LinearProgressIndicator? = null
    private var analysisPercent: TextView? = null
    private var statusLabel: TextView? = null
    private var textRunStatus: TextView? = null
    private var statusDetails: TextView? = null
    private var btnStatusAction: MaterialButton? = null
    private var linkViewPending: TextView? = null
    private var runsContainer: LinearLayout? = null

    private var running = false
    /** True after a run finished during THIS visit — Complete! is not sticky
     *  across a fresh visit (owner behavior rule). */
    private var completedThisVisit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_assistant)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        factSinceRun = findViewById(R.id.fact_since_run)
        factPending = findViewById(R.id.fact_pending)
        factLastRun = findViewById(R.id.fact_last_run)
        notReadyContainer = findViewById(R.id.not_ready_container)
        btnSetup = findViewById(R.id.btn_setup)
        btnAnalyze = findViewById(R.id.btn_analyze)
        analysisProgressContainer = findViewById(R.id.analysis_progress_container)
        analysisBar = findViewById(R.id.analysis_bar)
        analysisPercent = findViewById(R.id.analysis_percent)
        statusLabel = findViewById(R.id.status_label)
        textRunStatus = findViewById(R.id.text_run_status)
        statusDetails = findViewById(R.id.status_details)
        btnStatusAction = findViewById(R.id.btn_status_action)
        linkViewPending = findViewById(R.id.link_view_pending)
        runsContainer = findViewById(R.id.runs_container)

        degradedContainer = findViewById(R.id.degraded_container)
        btnDegradedRepair = findViewById(R.id.btn_degraded_repair)
        btnDegradedRevert = findViewById(R.id.btn_degraded_revert)

        applyTheme()
        btnBack?.setOnClickListener { finish() }
        btnAnalyze?.setOnClickListener { if (!running) startRun(null) }
        btnSetup?.setOnClickListener { openArchivistSettings() }
        linkViewPending?.setOnClickListener { openPendingBrowser() }
        // The A3 buttons act on the memory database — the store the Archivist
        // writes to (the block itself trips on ANY database problem, owner
        // rule, but memory is what these two actions repair).
        btnDegradedRepair?.setOnClickListener {
            DatabaseRecoveryFlows.runRepair(this, BackupType.MEMORY) { refreshFactsAndRuns() }
        }
        btnDegradedRevert?.setOnClickListener {
            DatabaseRecoveryFlows.runRevert(this, BackupType.MEMORY) { refreshFactsAndRuns() }
        }

        // A terminal outcome left by a run that finished while no screen was
        // watching is history, not news: the Recent Memory Analysis list
        // carries it. Only a LIVE run is picked up on a fresh visit.
        if (MemoryAnalysisForegroundService.state.value is MemoryAnalysisState.Finished) {
            MemoryAnalysisForegroundService.state.value = null
        }
        observeServiceState()
    }

    override fun onResume() {
        super.onResume()
        // Owner behavior rule: a finished "Complete!" is not sticky across a
        // fresh visit — coming back with nothing new shows the idle button.
        if (!running && completedThisVisit) {
            completedThisVisit = false
            btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
            clearStatusBlock()
        }
        refreshFactsAndRuns()
    }

    private fun clearStatusBlock() {
        analysisProgressContainer?.visibility = View.GONE
        statusLabel?.visibility = View.GONE
        textRunStatus?.visibility = View.GONE
        statusDetails?.visibility = View.GONE
        btnStatusAction?.visibility = View.GONE
        linkViewPending?.visibility = View.GONE
    }

    /* ---------------- data ---------------- */

    private fun refreshFactsAndRuns() {
        lifecycleScope.launch(Dispatchers.IO) {
            // A3 hard block (§15.2a): with ANY database problem the Archivist
            // must never run (it writes to the store), and a degraded memory
            // store refuses to open at all — so the facts are computed only
            // when healthy.
            val anyDegraded = DatabaseHealthState.anyDegraded(this@MemoryAssistantActivity)
            val storeUsable = MemoryStore.isProvisioned(this@MemoryAssistantActivity) &&
                !DatabaseHealthState.isDegraded(this@MemoryAssistantActivity, BackupType.MEMORY)
            val eligible = if (storeUsable) Archivist.eligibleConversationCount(this@MemoryAssistantActivity) else 0
            val pendingChats = if (storeUsable)
                MemoryStore.getInstance(this@MemoryAssistantActivity).pendingReviewCount() else 0
            val runs = if (storeUsable)
                MemoryStore.getInstance(this@MemoryAssistantActivity).getArchivistRuns(RECENT_RUNS) else emptyList()
            // "Some Memories Deleted Later" badge: which runs reference memory
            // ids that no longer exist. Computed live so the badge appears as
            // soon as a deletion happens.
            val deletedLater = HashSet<String>()
            if (storeUsable) {
                val store = MemoryStore.getInstance(this@MemoryAssistantActivity)
                for (run in runs) {
                    val ids = jsonIds(run.memoryIdsJson)
                    if (ids.isNotEmpty() && store.existingMemoryIds(ids).size < ids.size) {
                        deletedLater.add(run.runId)
                    }
                }
            }
            val configured = isArchivistConfigured()
            withContext(Dispatchers.Main) {
                factSinceRun?.text = getString(R.string.memory_assistant_fact_since_run, eligible)
                factPending?.text = getString(R.string.memory_assistant_fact_pending, pendingChats)
                val lastRun = runs.firstOrNull()
                if (lastRun != null) {
                    factLastRun?.visibility = View.VISIBLE
                    factLastRun?.text = getString(
                        R.string.memory_assistant_fact_last_run, formatDate(lastRun.startedAt)
                    )
                } else {
                    // No run has ever happened — the line has nothing truthful
                    // to say, so it stays hidden rather than inventing a value.
                    factLastRun?.visibility = View.GONE
                }
                // A3 hard block outranks the ordinary not-ready state: the
                // degraded note (with WORKING Repair/Revert buttons) shows,
                // the not-ready block hides, and the run button is disabled
                // no matter how well configured the Archivist is.
                degradedContainer?.visibility = if (anyDegraded) View.VISIBLE else View.GONE
                // Not-ready state (spec 1): message above the button, run
                // button visibly disabled; Set Up opens the settings screen.
                notReadyContainer?.visibility =
                    if (configured || anyDegraded) View.GONE else View.VISIBLE
                if (!running) btnAnalyze?.isEnabled = configured && !anyDegraded
                renderRuns(runs, deletedLater)
            }
        }
    }

    private fun isArchivistConfigured(): Boolean = try {
        val prefs = Preferences.getPreferences(this, "")
        val endpointId = prefs.getArchivistEndpointId()
        if (endpointId.isBlank()) false else {
            val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(this)
                .getApiEndpoint(this, endpointId)
            endpoint.host.isNotBlank() &&
                (prefs.getArchivistModel().isNotBlank() || endpoint.model.isNotBlank())
        }
    } catch (_: Exception) {
        false
    }

    private fun renderRuns(runs: List<ArchivistRunRecord>, deletedLater: Set<String>) {
        val container = runsContainer ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (run in runs) {
            val row = inflater.inflate(R.layout.view_archivist_run_row, container, false)
            row.findViewById<TextView>(R.id.run_date)?.text = formatDate(run.startedAt)
            row.findViewById<TextView>(R.id.run_result)?.text =
                if (run.foundCount > 0) getString(R.string.memory_assistant_run_found, run.foundCount)
                else getString(R.string.memory_assistant_run_none)
            row.findViewById<TextView>(R.id.run_status)?.text = runStatusLabel(run)
            val badge = row.findViewById<TextView>(R.id.run_badge)
            if (run.runId in deletedLater) {
                badge?.visibility = View.VISIBLE
                badge?.text = getString(R.string.mem_arch_deleted_badge)
                // The expanded explanation (spec 8): a tap explains without
                // implying the run deleted anything.
                badge?.setOnClickListener {
                    MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                        .setTitle(R.string.mem_arch_deleted_badge)
                        .setMessage(R.string.mem_arch_deleted_explain)
                        .setPositiveButton(android.R.string.ok) { _, _ -> }
                        .show()
                }
            }
            row.findViewById<MaterialButton>(R.id.btn_rerun)?.setOnClickListener {
                if (!running) startRun(run.runId)
            }
            container.addView(row)
        }
    }

    /** Recent-row label per the spec's list. Legacy rows (before the outcome
     *  column) fall back to a derivation from what they stored. */
    private fun runStatusLabel(run: ArchivistRunRecord): String = when (run.outcome) {
        "completed" -> getString(R.string.mem_arch_row_completed)
        "full_failed" -> getString(R.string.mem_arch_full_label)
        "partial_failed" -> getString(R.string.mem_arch_partial_label)
        "nothing" -> getString(R.string.mem_arch_nothing_label)
        "no_new" -> getString(R.string.mem_arch_nonew_label)
        "interrupted" -> getString(R.string.mem_arch_interrupted_label)
        else -> when {
            run.status == "failed" -> getString(R.string.mem_arch_full_label)
            run.foundCount > 0 -> getString(R.string.mem_arch_row_completed)
            else -> getString(R.string.mem_arch_nonew_label)
        }
    }

    /* ---------------- the run ---------------- */

    /**
     * Launch the run in the Memory Analysis foreground service (counterplan
     * §4(a), Step 1.3). This screen no longer owns the analysis coroutine —
     * the run survives leaving this screen, app switching, and screen-off;
     * [observeServiceState] renders whatever the durable run is doing. If
     * the service cannot start, nothing ran and nothing was claimed: show
     * the persistent failure state and leave retry available (never fall
     * back to an Activity-owned run).
     */
    private fun startRun(rerunOfRunId: String?) {
        // Belt for the A3 hard block: the Analyze/Rerun surfaces are disabled
        // while any database problem exists, but the rule is "the Archivist
        // must NEVER run against a bad database", so the entry point enforces
        // it too (Rerun rows call here directly).
        if (DatabaseHealthState.anyDegraded(this)) {
            refreshFactsAndRuns()
            return
        }
        running = true
        btnAnalyze?.setText(R.string.memory_assistant_btn_running)
        btnAnalyze?.isEnabled = false
        // Spinner + "Analyzing Conversations" immediately; the determinate bar
        // and its percent wait until the run reports its fixed total.
        showAnalyzing(null)

        if (!MemoryAnalysisForegroundService.start(this, rerunOfRunId)) {
            running = false
            btnAnalyze?.isEnabled = true
            showOutcome(
                Archivist.RunOutcome(
                    null, 0, 0, 0, 0, emptyList(),
                    outcome = "full_failed",
                    failureReason = ArchivistFailure.UNKNOWN,
                    error = "analysis service could not start"
                )
            )
            refreshFactsAndRuns()
        }
    }

    /** Render the service-owned run: progress while it lives, the outcome
     *  once, then consume it so a fresh visit never replays it (owner rule:
     *  Complete! is not sticky across visits). */
    private fun observeServiceState() {
        lifecycleScope.launch {
            MemoryAnalysisForegroundService.state.collect { s ->
                when (s) {
                    is MemoryAnalysisState.Running -> {
                        running = true
                        btnAnalyze?.setText(R.string.memory_assistant_btn_running)
                        btnAnalyze?.isEnabled = false
                        showAnalyzing(s.progress)
                    }
                    is MemoryAnalysisState.Finished -> {
                        running = false
                        btnAnalyze?.isEnabled = true
                        showOutcome(s.outcome)
                        refreshFactsAndRuns()
                        MemoryAnalysisForegroundService.state.value = null
                    }
                    null -> { /* idle */ }
                }
            }
        }
    }

    /** Every terminal state is visible (spec: never a silent reset). */
    private fun showOutcome(o: Archivist.RunOutcome) {
        clearStatusBlock()
        completedThisVisit = true
        when (o.outcome) {
            "completed" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_done)
                showStatus(null, getString(R.string.memory_assistant_done_found, o.memoriesFound), null, null, null)
                linkViewPending?.visibility = View.VISIBLE
            }
            "no_new" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_done)
                showStatus(
                    getString(R.string.mem_arch_nonew_label),
                    getString(R.string.mem_arch_nonew_msg),
                    if (o.duplicatesSkipped > 0) getString(R.string.mem_arch_nonew_dupes)
                    else getString(R.string.mem_arch_nonew_nothing),
                    getString(R.string.mem_arch_btn_run_again),
                    neutralLabelColor()
                ) { startRun(null) }
            }
            "nothing" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                showStatus(
                    getString(R.string.mem_arch_nothing_label),
                    getString(R.string.mem_arch_nothing_msg),
                    null,
                    getString(R.string.mem_arch_btn_try_again),
                    neutralLabelColor()
                ) { startRun(null) }
            }
            "partial_failed" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                val counts = getString(
                    R.string.mem_arch_partial_counts,
                    o.memoriesFound, o.conversationsAnalyzed, o.failedChatIds.size
                )
                val reason = partialReason(o.failureReason)
                showStatus(
                    getString(R.string.mem_arch_partial_label),
                    getString(R.string.mem_arch_partial_msg),
                    counts + "\n\n" + reason,
                    getString(R.string.mem_arch_btn_try_again),
                    MaterialColors.getColor(statusLabel!!, com.google.android.material.R.attr.colorTertiary)
                ) { startRun(null) }
            }
            "full_failed" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                val reason = o.failureReason ?: ArchivistFailure.UNKNOWN
                showStatus(
                    getString(R.string.mem_arch_full_label),
                    getString(R.string.mem_arch_full_msg),
                    fullReason(reason),
                    if (reason.settingsRelated) getString(R.string.mem_arch_btn_check_settings)
                    else getString(R.string.mem_arch_btn_try_again),
                    // colorError lives in appcompat's attrs (material inherits
                    // it rather than declaring it — material.R has no entry).
                    MaterialColors.getColor(statusLabel!!, androidx.appcompat.R.attr.colorError)
                ) {
                    if (reason.settingsRelated) openArchivistSettings() else startRun(null)
                }
            }
            "interrupted" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                showStatus(
                    getString(R.string.mem_arch_interrupted_label),
                    getString(R.string.mem_arch_interrupted_msg),
                    if (o.memoriesFound > 0) getString(R.string.mem_arch_interrupted_saved)
                    else getString(R.string.mem_arch_interrupted_none),
                    getString(R.string.mem_arch_btn_try_again),
                    MaterialColors.getColor(statusLabel!!, com.google.android.material.R.attr.colorTertiary)
                ) { startRun(null) }
            }
            "not_configured" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                // The above-button block + disabled button carry this state.
            }
            "already_running" -> {
                // Safety belt (counterplan §4(a)): another live run won the
                // durable one-run gate; nothing was claimed or written for
                // this attempt. The winner's own instance shows its
                // progress; this one just returns to idle. Presenting this
                // state properly belongs to the foreground-service redesign.
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
            }
        }
    }

    private fun neutralLabelColor(): Int =
        ResourcesCompat.getColor(resources, R.color.text_title, theme)

    private fun showStatus(
        label: String?,
        message: String,
        details: String?,
        actionText: String?,
        labelColor: Int?,
        action: (() -> Unit)? = null
    ) {
        if (label != null) {
            statusLabel?.visibility = View.VISIBLE
            statusLabel?.text = label
            labelColor?.let { statusLabel?.setTextColor(it) }
        }
        textRunStatus?.visibility = View.VISIBLE
        textRunStatus?.text = message
        if (details != null) {
            statusDetails?.visibility = View.VISIBLE
            statusDetails?.text = details
        }
        if (actionText != null) {
            btnStatusAction?.visibility = View.VISIBLE
            btnStatusAction?.text = actionText
            btnStatusAction?.setOnClickListener { if (!running) action?.invoke() }
        }
    }

    private fun fullReason(r: ArchivistFailure): String = getString(
        when (r) {
            ArchivistFailure.UNREACHABLE -> R.string.mem_arch_full_unreachable
            ArchivistFailure.REJECTED -> R.string.mem_arch_full_rejected
            ArchivistFailure.LIMIT -> R.string.mem_arch_full_limit
            ArchivistFailure.UNREADABLE -> R.string.mem_arch_full_unreadable
            ArchivistFailure.SAVE_FAILED -> R.string.mem_arch_full_save
            ArchivistFailure.INTERRUPTED -> R.string.mem_arch_full_interrupted
            ArchivistFailure.UNKNOWN -> R.string.mem_arch_full_unknown
        }
    )

    private fun partialReason(r: ArchivistFailure?): String = getString(
        when (r) {
            ArchivistFailure.UNREACHABLE -> R.string.mem_arch_part_unreachable
            ArchivistFailure.REJECTED -> R.string.mem_arch_part_rejected
            ArchivistFailure.LIMIT -> R.string.mem_arch_part_limit
            ArchivistFailure.UNREADABLE -> R.string.mem_arch_part_unreadable
            ArchivistFailure.SAVE_FAILED -> R.string.mem_arch_part_save
            ArchivistFailure.INTERRUPTED -> R.string.mem_arch_part_interrupted
            else -> R.string.mem_arch_part_unknown
        }
    )

    /**
     * The live-run surface (approved pattern, plan_one_page.md): an
     * indeterminate spinner and "Analyzing Conversations" whenever a run is
     * active; the determinate bar and its **X%** appear only once the run's
     * fixed total is known. The percentage is completed conversations divided
     * by the fixed total the run sealed at its start — never invented before
     * that total exists, and never advanced on a timer. A conversation still
     * in flight is not yet complete, so the bar reaches 100% only after the
     * run itself finishes (owner rule: 100% is not success on its own).
     */
    private fun showAnalyzing(p: Archivist.Progress?) {
        clearStatusBlock()
        analysisProgressContainer?.visibility = View.VISIBLE
        if (p != null && p.overallCount > 0) {
            val completed = (p.overallIndex - 1).coerceAtLeast(0)
            val percent = (completed * 100 / p.overallCount).coerceIn(0, 100)
            analysisBar?.visibility = View.VISIBLE
            analysisBar?.isIndeterminate = false
            analysisBar?.max = 100
            analysisBar?.setProgressCompat(percent, true)
            analysisPercent?.visibility = View.VISIBLE
            analysisPercent?.text = getString(R.string.memory_assistant_analyzing_percent, percent)
        } else {
            analysisBar?.visibility = View.GONE
            analysisPercent?.visibility = View.GONE
        }
    }

    /* ---------------- navigation ---------------- */

    /** Owner answer 2: the link opens the Memory Browser showing everything
     *  pending — roleplay drafts NOT separated from the rest. */
    private fun openPendingBrowser() {
        MemoryBrowserFilterState.reset()
        MemoryBrowserFilterState.status.clear()
        MemoryBrowserFilterState.status.add("draft")
        startActivity(Intent(this, MemoryBrowserActivity::class.java))
    }

    /** "Set Up Archivist Model" / "Check Archivist Settings": the Memory
     *  Assistant endpoint + model live on the Memory Controls screen (the
     *  reorged Memory Settings, July 9 2026). */
    private fun openArchivistSettings() {
        startActivity(Intent(this, MemoryControlsActivity::class.java).putExtra("chatId", chatId))
    }

    private fun jsonIds(json: String): List<String> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun formatDate(iso: String): String = try {
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    } catch (_: Exception) {
        iso
    }

    /* ---------------- theming (house pattern) ---------------- */

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
        } catch (_: Exception) { /* unused */ }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    companion object {
        /** Owner answer 3: the Recent Memory Analysis list shows 5 runs. */
        private const val RECENT_RUNS = 5
    }
}
