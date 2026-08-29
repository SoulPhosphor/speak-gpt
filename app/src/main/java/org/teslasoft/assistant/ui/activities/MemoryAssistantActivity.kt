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
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.memory.ArchivistRunRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistFailure
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistFailureCategory
import org.teslasoft.assistant.providers.DedicatedModelRoutingPolicy
import org.teslasoft.assistant.util.providerDetailBlock
import org.teslasoft.assistant.service.MemoryAnalysisForegroundService
import org.teslasoft.assistant.service.MemoryAnalysisState
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.DatabaseRecoveryFlows
import org.teslasoft.assistant.ui.activities.memory.MemoryBrowserActivity
import org.teslasoft.assistant.ui.activities.memory.MemoryBrowserFilterState
import org.teslasoft.assistant.ui.activities.memory.MemoryDateFormatter
import org.teslasoft.assistant.ui.widgets.AppDropdown

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
    private var btnCancel: MaterialButton? = null
    private var textAnalysisTypeValue: TextView? = null
    /** True when the run whose outcome is currently shown was a Lorebook
     *  Memories run — routes the View link to the Lorebooks Pending area. */
    private var lastOutcomeLorebook = false

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
        btnCancel = findViewById(R.id.btn_cancel)
        textAnalysisTypeValue = findViewById(R.id.text_analysis_type_value)
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
        // Cancel is the user asking to stop the live run (owner ruling, Aug 1
        // 2026): a clean stop, not an error. It is only visible while a run is
        // active. Disable it on tap so a second press can't double-fire.
        btnCancel?.setOnClickListener {
            btnCancel?.isEnabled = false
            MemoryAnalysisForegroundService.requestCancel(this)
        }
        btnSetup?.setOnClickListener { openArchivistSettings() }
        // The View link routes to whichever Pending area the shown run filled:
        // the Memory Browser for saved memories, the Lorebooks area for lore
        // book suggestions (Step 1.7).
        linkViewPending?.setOnClickListener {
            if (lastOutcomeLorebook) openLorebookPending() else openPendingBrowser()
        }
        textAnalysisTypeValue?.setOnClickListener { showAnalysisTypePicker() }
        refreshAnalysisTypeRow()
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
        // Cancel only belongs on screen while a run is live.
        if (!running) btnCancel?.visibility = View.GONE
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
                    // A Lorebook run's stored ids point at pending lore
                    // suggestions, not memory rows (Fix #4): existingMemoryIds
                    // would never find them and would flag every Lorebook run as
                    // "Some Memories Deleted Later". The badge is only about saved
                    // memories, so Lorebook runs are skipped entirely.
                    if (run.analysisType == "lorebook") continue
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
            val model = prefs.getArchivistModel()
            val routingReady = if (endpoint.isOpenRouterRouting() && model.isNotBlank()) {
                val favorite = FavoriteModelsPreferences.getPreferences(this)
                    .getFavorite(model, endpointId)
                !DedicatedModelRoutingPolicy.needsSetup(
                    prefs.getArchivistRoutingType(), favorite
                )
            } else {
                true
            }
            endpoint.host.isNotBlank() && model.isNotBlank() && routingReady
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
            // Which analysis produced this run (Fix #4): the same "Associative
            // Memories" / "Lorebook Memories" wording as the picker.
            row.findViewById<TextView>(R.id.run_type)?.text = analysisTypeLabel(run.analysisType)
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
                        .setPositiveButton(R.string.btn_ok) { _, _ -> }
                        .show()
                }
            }
            row.findViewById<MaterialButton>(R.id.btn_rerun)?.setOnClickListener {
                // A Rerun re-runs the original run's OWN analysis type (Fix #2),
                // not whatever the picker currently shows.
                if (!running) startRun(run.runId, run.analysisType)
            }
            container.addView(row)
        }
    }

    /** Recent-row label per the spec's list, type-aware (Fix #4). A Lorebook
     *  run never shows Associative-specific wording ("No New Memories Added",
     *  "Run Fully Failed"); it reuses the approved live Lorebook titles. The row
     *  persists only the coarse failure reason, so a Lorebook full failure maps
     *  that reason to the closest approved Lorebook title. Legacy rows (before
     *  the outcome column) fall back to a derivation from what they stored. */
    private fun runStatusLabel(run: ArchivistRunRecord): String {
        val lore = run.analysisType == "lorebook"
        return when (run.outcome) {
            "completed" -> getString(R.string.mem_arch_row_completed)
            // A Lorebook run that added nothing new still completed cleanly; the
            // neutral "None found" count carries the nuance, so the row reads
            // "Completed" instead of the Associative "No New Memories Added".
            "no_new" -> if (lore) getString(R.string.mem_arch_row_completed)
                        else getString(R.string.mem_arch_nonew_label)
            "nothing" -> getString(R.string.mem_arch_nothing_label)
            "partial_failed" -> if (lore) getString(R.string.mem_arch_part_incomplete_title)
                                else getString(R.string.mem_arch_partial_label)
            "full_failed" -> if (lore) getString(loreRowFailureTitle(run.failureReason))
                             else getString(R.string.mem_arch_full_label)
            "interrupted" -> if (lore) getString(R.string.mem_arch_part_interrupted_title)
                             else getString(R.string.mem_arch_interrupted_label)
            // In-process stops, now persisted with their distinction (Fix #6): a
            // generic system stop, the runtime limit, and the user's own Cancel.
            "cancelled" -> getString(R.string.mem_arch_stopped_early_title)
            "stopped_time_limit" -> getString(R.string.mem_arch_time_limit_title)
            "cancelled_user" -> getString(R.string.mem_arch_fail_cancelled_title)
            else -> when {
                run.status == "failed" -> if (lore) getString(R.string.mem_arch_fail_unknown_title)
                                          else getString(R.string.mem_arch_full_label)
                run.foundCount > 0 -> getString(R.string.mem_arch_row_completed)
                else -> if (lore) getString(R.string.mem_arch_row_completed)
                        else getString(R.string.mem_arch_nonew_label)
            }
        }
    }

    /** The closest approved live-Lorebook failure title for a history row,
     *  chosen from the coarse failure reason the row persists (the fine provider
     *  category is only known at run time, and is not stored). Never
     *  Associative-worded. */
    private fun loreRowFailureTitle(reasonKey: String?): Int =
        when (ArchivistFailure.fromKey(reasonKey)) {
            ArchivistFailure.UNREACHABLE -> R.string.mem_arch_fail_connection_title
            ArchivistFailure.REJECTED -> R.string.mem_arch_fail_rejected_title
            ArchivistFailure.LIMIT -> R.string.mem_arch_fail_usage_limit_title
            ArchivistFailure.UNREADABLE -> R.string.mem_arch_fail_invalid_result_title
            ArchivistFailure.SAVE_FAILED -> R.string.mem_arch_fail_save_title
            ArchivistFailure.INTERRUPTED -> R.string.mem_arch_fail_interrupted_title
            else -> R.string.mem_arch_fail_unknown_title
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
    private fun startRun(
        rerunOfRunId: String?,
        // A fresh Analyze / Run again uses the picker's current selection; a
        // Rerun passes the ORIGINAL run's stored analysis type (Fix #2) so a
        // Lorebook rerun is never silently converted to Associative.
        analysisType: String = Preferences.getPreferences(this, "").getMemoryAnalysisType()
    ) {
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
        btnCancel?.isEnabled = true
        btnCancel?.visibility = View.VISIBLE
        // Spinner + "Analyzing Conversations" immediately; the determinate bar
        // and its percent wait until the run reports its fixed total.
        showAnalyzing(null)

        if (!MemoryAnalysisForegroundService.start(this, rerunOfRunId, analysisType)) {
            running = false
            btnAnalyze?.isEnabled = true
            showOutcome(
                Archivist.RunOutcome(
                    null, 0, 0, 0, 0, emptyList(),
                    outcome = "full_failed",
                    failureReason = ArchivistFailure.UNKNOWN,
                    error = "analysis service could not start",
                    // Carry the requested type through the immediate start
                    // failure (Fix #3): never default this surface to Associative.
                    analysisType = analysisType
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
                        btnCancel?.isEnabled = true
                        btnCancel?.visibility = View.VISIBLE
                        showAnalyzing(s.progress)
                    }
                    is MemoryAnalysisState.Finished -> {
                        running = false
                        btnAnalyze?.isEnabled = true
                        btnCancel?.visibility = View.GONE
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
        lastOutcomeLorebook = o.analysisType == "lorebook"
        when (o.outcome) {
            "completed" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_done)
                // Step 1.7: a Lorebook Memories run reports its suggestions with
                // the approved "Potential Lorebook Memories found: N" line, and
                // the View link opens the Lorebooks area on Pending instead of
                // the Memory Browser.
                if (lastOutcomeLorebook) {
                    showStatus(null, getString(R.string.memory_assistant_lore_found, o.memoriesFound), null, null, null)
                    linkViewPending?.text = getString(R.string.memory_assistant_view_lore_pending)
                } else {
                    showStatus(null, getString(R.string.memory_assistant_done_found, o.memoriesFound), null, null, null)
                    linkViewPending?.text = getString(R.string.memory_assistant_view_pending)
                }
                linkViewPending?.visibility = View.VISIBLE
            }
            "no_new" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_done)
                if (lastOutcomeLorebook) {
                    // A Lorebook run that found nothing new reports through the
                    // same approved result surface with a count of zero — no
                    // View link, since there is nothing new to review.
                    showStatus(
                        null,
                        getString(R.string.memory_assistant_lore_found, o.memoriesFound),
                        null,
                        getString(R.string.mem_arch_btn_run_again),
                        null
                    ) { startRun(null) }
                } else {
                    showStatus(
                        getString(R.string.mem_arch_nonew_label),
                        getString(R.string.mem_arch_nonew_msg),
                        if (o.duplicatesSkipped > 0) getString(R.string.mem_arch_nonew_dupes)
                        else getString(R.string.mem_arch_nonew_nothing),
                        getString(R.string.mem_arch_btn_run_again),
                        neutralLabelColor()
                    ) { startRun(null) }
                }
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
                if (lastOutcomeLorebook) {
                    // Some conversations succeeded and some failed. One specific
                    // title when every failed conversation shares a cause; a mix
                    // uses Lorebook Analysis Incomplete with a short breakdown.
                    // The saved count is reported separately (owner ruling).
                    val counts = o.failureCategoryCounts
                    val category = resolvePartialCategory(counts)
                    val (titleRes, msgRes) = if (category != null) lorePartialStrings(category)
                        else R.string.mem_arch_part_incomplete_title to R.string.mem_arch_part_incomplete_msg
                    val foundLine = getString(R.string.memory_assistant_lore_found, o.memoriesFound)
                    // A mixed run appends a per-cause breakdown; an engine-level
                    // abort with saved suggestions (Fix #5) has no per-conversation
                    // categories, so only the found line is shown — no dangling gap.
                    val breakdown = if (category == null) loreBreakdown(counts) else ""
                    val details = if (breakdown.isNotBlank()) foundLine + "\n\n" + breakdown else foundLine
                    val settingsRelated = category != null && category in LORE_FAIL_SETTINGS
                    showStatus(
                        getString(titleRes),
                        getString(msgRes),
                        details,
                        if (settingsRelated) getString(R.string.mem_arch_btn_check_settings)
                        else getString(R.string.mem_arch_btn_try_again),
                        loreTertiaryColor()
                    ) { if (settingsRelated) openArchivistSettings() else startRun(null) }
                    showLoreCountAndView(o.memoriesFound)
                } else {
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
            }
            "full_failed" -> {
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                val reason = o.failureReason ?: ArchivistFailure.UNKNOWN
                val errorColor = MaterialColors.getColor(statusLabel!!, androidx.appcompat.R.attr.colorError)
                if (lastOutcomeLorebook) {
                    // Map the failure to the owner-approved Lorebook state
                    // (title + subtitle) and show the shared provider-detail
                    // block beneath it (Function: Archiving). Configuration,
                    // model, and rejection causes point at settings; the rest
                    // offer a retry.
                    val category = resolveRejectionCategory(
                        ArchivistFailureCategory.of(o.failureReason, o.genError),
                        o.failureCategoryCounts
                    )
                    val (titleRes, msgRes) = loreFailureStrings(category)
                    val settingsRelated = category in LORE_FAIL_SETTINGS
                    showStatus(
                        getString(titleRes),
                        getString(msgRes),
                        loreProviderBlock(o),
                        if (settingsRelated) getString(R.string.mem_arch_btn_check_settings)
                        else getString(R.string.mem_arch_btn_try_again),
                        errorColor
                    ) {
                        if (settingsRelated) openArchivistSettings() else startRun(null)
                    }
                } else {
                    showStatus(
                        getString(R.string.mem_arch_full_label),
                        getString(R.string.mem_arch_full_msg),
                        fullReason(reason),
                        if (reason.settingsRelated) getString(R.string.mem_arch_btn_check_settings)
                        else getString(R.string.mem_arch_btn_try_again),
                        // colorError lives in appcompat's attrs (material inherits
                        // it rather than declaring it — material.R has no entry).
                        errorColor
                    ) {
                        if (reason.settingsRelated) openArchivistSettings() else startRun(null)
                    }
                }
            }
            "interrupted" -> {
                // Process death the durable run could not resume (owner wording).
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                if (lastOutcomeLorebook) {
                    showStatus(
                        getString(R.string.mem_arch_part_interrupted_title),
                        getString(R.string.mem_arch_part_interrupted_msg),
                        if (o.memoriesFound > 0) getString(R.string.memory_assistant_lore_found, o.memoriesFound) else null,
                        getString(R.string.mem_arch_btn_try_again),
                        loreTertiaryColor()
                    ) { startRun(null) }
                    showLoreCountAndView(o.memoriesFound)
                } else {
                    showAssociativeStopped(o)
                }
            }
            "cancelled" -> {
                // A generic in-process system stop (owner wording: Analysis
                // Stopped Early). The dataSync runtime limit is its own state
                // below; the user's Cancel is the neutral state after that.
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                if (lastOutcomeLorebook) {
                    showStatus(
                        getString(R.string.mem_arch_stopped_early_title),
                        getString(R.string.mem_arch_stopped_early_msg),
                        if (o.memoriesFound > 0) getString(R.string.memory_assistant_lore_found, o.memoriesFound) else null,
                        getString(R.string.mem_arch_btn_try_again),
                        loreTertiaryColor()
                    ) { startRun(null) }
                    showLoreCountAndView(o.memoriesFound)
                } else {
                    showAssociativeStopped(o)
                }
            }
            "stopped_time_limit" -> {
                // The Android 15+ dataSync runtime limit ended the run.
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                if (lastOutcomeLorebook) {
                    showStatus(
                        getString(R.string.mem_arch_time_limit_title),
                        getString(R.string.mem_arch_time_limit_msg),
                        if (o.memoriesFound > 0) getString(R.string.memory_assistant_lore_found, o.memoriesFound) else null,
                        getString(R.string.mem_arch_btn_try_again),
                        loreTertiaryColor()
                    ) { startRun(null) }
                    showLoreCountAndView(o.memoriesFound)
                } else {
                    showAssociativeStopped(o)
                }
            }
            "cancelled_user" -> {
                // The user pressed Cancel: neutral, never an error (owner ruling,
                // Aug 1 2026). A Lorebook run confirms what was saved with View +
                // Done; associative simply returns to idle (clearStatusBlock ran).
                btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                if (lastOutcomeLorebook) {
                    val saved = o.memoriesFound > 0
                    showStatus(
                        getString(R.string.mem_arch_fail_cancelled_title),
                        if (saved) getString(R.string.mem_arch_cancel_saved_msg)
                        else getString(R.string.mem_arch_cancel_none_msg),
                        if (saved) getString(R.string.memory_assistant_lore_found, o.memoriesFound) else null,
                        getString(R.string.mem_arch_btn_done),
                        neutralLabelColor()
                    ) { clearStatusBlock() }
                    showLoreCountAndView(o.memoriesFound)
                }
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

    /** Full failure: [category] is the run's already-chosen dominant reason. If
     *  it is one of the three rejection subtypes but the run's failed
     *  conversations show more than one distinct rejection subtype, downgrade
     *  to the generic Request Rejected state (owner ruling, Aug 3 2026) —
     *  otherwise the subtype stands as-is. Categories outside the rejection
     *  family are untouched. */
    private fun resolveRejectionCategory(category: String, counts: Map<String, Int>): String {
        if (category !in REJECTION_SUBTYPES) return category
        return if (counts.keys.count { it in REJECTION_SUBTYPES } <= 1) category
        else ArchivistFailureCategory.REJECTED
    }

    /** Partial failure: a specific title requires every failed conversation to
     *  share ONE category. A run whose failures are uniformly within the
     *  rejection family but differ in subtype uses the generic Some Requests
     *  Were Rejected state (owner ruling, Aug 3 2026) rather than the
     *  mixed-cause Lorebook Analysis Incomplete state, which stays reserved for
     *  a mix that includes a non-rejection cause. Null means genuinely mixed —
     *  the caller falls back to Incomplete. */
    private fun resolvePartialCategory(counts: Map<String, Int>): String? = when {
        counts.keys.size == 1 -> counts.keys.first()
        counts.keys.isNotEmpty() && counts.keys.all { it in REJECTION_SUBTYPES } ->
            ArchivistFailureCategory.REJECTED
        else -> null
    }

    /** The owner-approved Lorebook full-failure title + subtitle for a mapped
     *  failure category (Aug 1 2026). */
    private fun loreFailureStrings(category: String): Pair<Int, Int> = when (category) {
        ArchivistFailureCategory.CONNECTION ->
            R.string.mem_arch_fail_connection_title to R.string.mem_arch_fail_connection_msg
        ArchivistFailureCategory.TIMEOUT ->
            R.string.mem_arch_fail_timeout_title to R.string.mem_arch_fail_timeout_msg
        ArchivistFailureCategory.REJECTED ->
            R.string.mem_arch_fail_rejected_title to R.string.mem_arch_fail_rejected_msg
        ArchivistFailureCategory.API_KEY_REJECTED ->
            R.string.mem_arch_fail_api_key_title to R.string.mem_arch_fail_api_key_msg
        ArchivistFailureCategory.ACCESS_DENIED ->
            R.string.mem_arch_fail_access_denied_title to R.string.mem_arch_fail_access_denied_msg
        ArchivistFailureCategory.CONTENT_REFUSED ->
            R.string.mem_arch_fail_content_refused_title to R.string.mem_arch_fail_content_refused_msg
        ArchivistFailureCategory.RATE_LIMIT ->
            R.string.mem_arch_fail_rate_limit_title to R.string.mem_arch_fail_rate_limit_msg
        ArchivistFailureCategory.USAGE_LIMIT ->
            R.string.mem_arch_fail_usage_limit_title to R.string.mem_arch_fail_usage_limit_msg
        ArchivistFailureCategory.CREDITS ->
            R.string.mem_arch_fail_credits_title to R.string.mem_arch_fail_credits_msg
        ArchivistFailureCategory.MODEL_UNAVAILABLE ->
            R.string.mem_arch_fail_model_unavailable_title to R.string.mem_arch_fail_model_unavailable_msg
        ArchivistFailureCategory.REQUEST_TOO_LARGE ->
            R.string.mem_arch_fail_too_large_title to R.string.mem_arch_fail_too_large_msg
        ArchivistFailureCategory.CONFIG ->
            R.string.mem_arch_fail_config_title to R.string.mem_arch_fail_config_msg
        ArchivistFailureCategory.PROVIDER_ERROR ->
            R.string.mem_arch_fail_provider_error_title to R.string.mem_arch_fail_provider_error_msg
        ArchivistFailureCategory.UNREADABLE ->
            R.string.mem_arch_fail_unreadable_title to R.string.mem_arch_fail_unreadable_msg
        ArchivistFailureCategory.INVALID_RESULT ->
            R.string.mem_arch_fail_invalid_result_title to R.string.mem_arch_fail_invalid_result_msg
        ArchivistFailureCategory.SAVE_FAILED ->
            R.string.mem_arch_fail_save_title to R.string.mem_arch_fail_save_msg
        ArchivistFailureCategory.PROCESS_LOCAL ->
            R.string.mem_arch_fail_process_title to R.string.mem_arch_fail_process_msg
        else ->
            R.string.mem_arch_fail_unknown_title to R.string.mem_arch_fail_unknown_msg
    }

    /** The partial-failure title + subtitle for a single uniform cause. config
     *  and unknown have no dedicated partial title, so they use the "could not
     *  be processed" bucket. */
    private fun lorePartialStrings(category: String): Pair<Int, Int> = when (category) {
        ArchivistFailureCategory.CONNECTION ->
            R.string.mem_arch_part_connection_title to R.string.mem_arch_part_connection_msg
        ArchivistFailureCategory.TIMEOUT ->
            R.string.mem_arch_part_timeout_title to R.string.mem_arch_part_timeout_msg
        ArchivistFailureCategory.REJECTED ->
            R.string.mem_arch_part_rejected_title to R.string.mem_arch_part_rejected_msg
        ArchivistFailureCategory.API_KEY_REJECTED ->
            R.string.mem_arch_part_api_key_title to R.string.mem_arch_part_api_key_msg
        ArchivistFailureCategory.ACCESS_DENIED ->
            R.string.mem_arch_part_access_denied_title to R.string.mem_arch_part_access_denied_msg
        ArchivistFailureCategory.CONTENT_REFUSED ->
            R.string.mem_arch_part_content_refused_title to R.string.mem_arch_part_content_refused_msg
        ArchivistFailureCategory.RATE_LIMIT ->
            R.string.mem_arch_part_rate_limit_title to R.string.mem_arch_part_rate_limit_msg
        ArchivistFailureCategory.USAGE_LIMIT ->
            R.string.mem_arch_part_usage_limit_title to R.string.mem_arch_part_usage_limit_msg
        ArchivistFailureCategory.CREDITS ->
            R.string.mem_arch_part_credits_title to R.string.mem_arch_part_credits_msg
        ArchivistFailureCategory.MODEL_UNAVAILABLE ->
            R.string.mem_arch_part_model_title to R.string.mem_arch_part_model_msg
        ArchivistFailureCategory.REQUEST_TOO_LARGE ->
            R.string.mem_arch_part_too_large_title to R.string.mem_arch_part_too_large_msg
        ArchivistFailureCategory.PROVIDER_ERROR ->
            R.string.mem_arch_part_provider_error_title to R.string.mem_arch_part_provider_error_msg
        ArchivistFailureCategory.UNREADABLE ->
            R.string.mem_arch_part_unreadable_title to R.string.mem_arch_part_unreadable_msg
        ArchivistFailureCategory.INVALID_RESULT ->
            R.string.mem_arch_part_invalid_title to R.string.mem_arch_part_invalid_msg
        ArchivistFailureCategory.SAVE_FAILED ->
            R.string.mem_arch_part_save_title to R.string.mem_arch_part_save_msg
        // config, process_local, and unknown all use the "could not be
        // processed" bucket.
        else ->
            R.string.mem_arch_part_process_title to R.string.mem_arch_part_process_msg
    }

    private fun breakdownPluralRes(category: String): Int = when (category) {
        ArchivistFailureCategory.CONNECTION -> R.plurals.mem_arch_break_connection
        ArchivistFailureCategory.TIMEOUT -> R.plurals.mem_arch_break_timeout
        ArchivistFailureCategory.REJECTED -> R.plurals.mem_arch_break_rejected
        ArchivistFailureCategory.RATE_LIMIT -> R.plurals.mem_arch_break_rate_limit
        ArchivistFailureCategory.USAGE_LIMIT -> R.plurals.mem_arch_break_usage_limit
        ArchivistFailureCategory.CREDITS -> R.plurals.mem_arch_break_credits
        ArchivistFailureCategory.MODEL_UNAVAILABLE -> R.plurals.mem_arch_break_model
        ArchivistFailureCategory.REQUEST_TOO_LARGE -> R.plurals.mem_arch_break_too_large
        ArchivistFailureCategory.CONFIG -> R.plurals.mem_arch_break_config
        ArchivistFailureCategory.PROVIDER_ERROR -> R.plurals.mem_arch_break_provider_error
        ArchivistFailureCategory.UNREADABLE -> R.plurals.mem_arch_break_unreadable
        ArchivistFailureCategory.INVALID_RESULT -> R.plurals.mem_arch_break_invalid
        ArchivistFailureCategory.SAVE_FAILED -> R.plurals.mem_arch_break_save
        // process_local and unknown both read as "could not be processed".
        else -> R.plurals.mem_arch_break_unknown
    }

    /** A short mixed-cause breakdown, each a grammatical counted clause with
     *  singular/plural agreement, e.g. "1 request timed out, 2 requests were
     *  rejected, and 1 result could not be saved." Save failures are placed
     *  last so a lost save is the memorable tail (owner ruling: a mixed failure
     *  with a save failure must explicitly mention it). This breakdown only
     *  renders when the run is genuinely mixed across DIFFERENT failure
     *  families (resolvePartialCategory returned null); the three rejection
     *  subtypes are collapsed back into the single generic "rejected" clause
     *  here so the mixed-cause wording stays exactly as before (owner ruling,
     *  Aug 3 2026: sub-reason detail is shown only in the uniform case). */
    private fun loreBreakdown(counts: Map<String, Int>): String {
        val merged = HashMap<String, Int>()
        for ((key, n) in counts) {
            val bucket = if (key in REJECTION_SUBTYPES) ArchivistFailureCategory.REJECTED else key
            merged[bucket] = (merged[bucket] ?: 0) + n
        }
        val order = listOf(
            ArchivistFailureCategory.CONNECTION, ArchivistFailureCategory.TIMEOUT,
            ArchivistFailureCategory.REJECTED, ArchivistFailureCategory.RATE_LIMIT,
            ArchivistFailureCategory.USAGE_LIMIT, ArchivistFailureCategory.CREDITS,
            ArchivistFailureCategory.MODEL_UNAVAILABLE, ArchivistFailureCategory.REQUEST_TOO_LARGE,
            ArchivistFailureCategory.CONFIG, ArchivistFailureCategory.PROVIDER_ERROR,
            ArchivistFailureCategory.UNREADABLE, ArchivistFailureCategory.INVALID_RESULT,
            ArchivistFailureCategory.PROCESS_LOCAL, ArchivistFailureCategory.UNKNOWN,
            ArchivistFailureCategory.SAVE_FAILED
        )
        val parts = order.filter { merged.containsKey(it) }.map {
            val n = merged.getValue(it)
            resources.getQuantityString(breakdownPluralRes(it), n, n)
        }
        val sentence = when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
        }
        return if (sentence.isEmpty()) sentence else "$sentence."
    }

    /** Show the found-count line and the review link when a lorebook outcome
     *  saved at least one suggestion. */
    private fun showLoreCountAndView(found: Int) {
        if (found > 0) {
            linkViewPending?.text = getString(R.string.memory_assistant_view_lore_pending)
            linkViewPending?.visibility = View.VISIBLE
        }
    }

    private fun loreTertiaryColor(): Int =
        MaterialColors.getColor(statusLabel!!, com.google.android.material.R.attr.colorTertiary)

    /** The shared provider-detail block for an archivist failure, with
     *  Function: Archiving. Null when the run did not fail against a provider. */
    private fun loreProviderBlock(o: Archivist.RunOutcome): String? {
        val gen = o.genError ?: return null
        val notReported = getString(R.string.provider_value_not_reported)
        return gen.providerDetailBlock(
            this, o.error,
            o.apiProvider ?: notReported,
            o.upstreamProvider ?: notReported,
            o.model ?: notReported,
            getString(R.string.mem_arch_function_archiving),
            o.providerMessage
        )
    }

    /** Associative run stopped/interrupted before finishing: the existing
     *  saved-memory wording (Lorebook runs use their own approved states). */
    private fun showAssociativeStopped(o: Archivist.RunOutcome) {
        showStatus(
            getString(R.string.mem_arch_interrupted_label),
            getString(R.string.mem_arch_interrupted_msg),
            if (o.memoriesFound > 0) getString(R.string.mem_arch_interrupted_saved)
            else getString(R.string.mem_arch_interrupted_none),
            getString(R.string.mem_arch_btn_try_again),
            MaterialColors.getColor(statusLabel!!, com.google.android.material.R.attr.colorTertiary)
        ) { startRun(null) }
    }

    private fun showStatus(
        label: String?,
        message: String?,
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
        if (!message.isNullOrBlank()) {
            textRunStatus?.visibility = View.VISIBLE
            textRunStatus?.text = message
        }
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

    /** Open the Lorebooks area on Pending — the review home for lore book
     *  suggestions a Lorebook Memories run produced (Step 1.7). */
    private fun openLorebookPending() {
        startActivity(
            Intent(this, LoreBooksListActivity::class.java)
                .putExtra(LoreBooksListActivity.EXTRA_OPEN_PENDING, true)
        )
    }

    /* ---------------- memory analysis type (Step 1.7) ---------------- */

    private fun analysisTypeLabel(type: String): String = getString(
        if (type == "lorebook") R.string.memory_analysis_type_lorebook
        else R.string.memory_analysis_type_associative
    )

    private fun refreshAnalysisTypeRow() {
        val type = Preferences.getPreferences(this, "").getMemoryAnalysisType()
        val anchor = textAnalysisTypeValue ?: return
        val labels = listOf(
            getString(R.string.memory_analysis_type_associative),
            getString(R.string.memory_analysis_type_lorebook)
        )
        anchor.text = analysisTypeLabel(type)
        AppDropdown.sizeToOptions(anchor, labels) {
            (anchor.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        }
    }

    /** The Memory Analysis Type dropdown (owner design): exactly two choices —
     *  Associative Memories (default) and Lorebook Memories. One run creates
     *  one kind; there is no "Both". */
    private fun showAnalysisTypePicker() {
        val anchor = textAnalysisTypeValue ?: return
        val types = listOf("associative", "lorebook")
        val labels = listOf(
            getString(R.string.memory_analysis_type_associative),
            getString(R.string.memory_analysis_type_lorebook)
        )
        val current = types.indexOf(Preferences.getPreferences(this, "").getMemoryAnalysisType())
            .coerceAtLeast(0)
        AppDropdown.show(anchor, labels, current) { position ->
            Preferences.getPreferences(this, "").setMemoryAnalysisType(types[position])
            refreshAnalysisTypeRow()
        }
    }

    private fun jsonIds(json: String): List<String> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun formatDate(iso: String): String = MemoryDateFormatter.format(iso)

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

        /** Lorebook failure categories whose recovery is a settings fix, so the
         *  action button points at the Memory Assistant settings instead of a
         *  plain retry. */
        private val LORE_FAIL_SETTINGS = setOf(
            ArchivistFailureCategory.CONFIG,
            ArchivistFailureCategory.MODEL_UNAVAILABLE,
            ArchivistFailureCategory.REJECTED,
            ArchivistFailureCategory.API_KEY_REJECTED,
            ArchivistFailureCategory.ACCESS_DENIED,
            ArchivistFailureCategory.CONTENT_REFUSED,
            // Request Too Large: the fix is choosing a larger-context model.
            ArchivistFailureCategory.REQUEST_TOO_LARGE
        )

        /** The three rejection subtypes a specific title can name (owner
         *  ruling, Aug 3 2026); [ArchivistFailureCategory.REJECTED] itself is
         *  the generic fallback shown when a run's rejections don't all share
         *  one of these. */
        private val REJECTION_SUBTYPES = setOf(
            ArchivistFailureCategory.API_KEY_REJECTED,
            ArchivistFailureCategory.ACCESS_DENIED,
            ArchivistFailureCategory.CONTENT_REFUSED
        )
    }
}
