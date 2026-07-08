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
import com.google.android.material.elevation.SurfaceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.ArchivistRunRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.activities.memory.MemoryBrowserActivity
import org.teslasoft.assistant.ui.activities.memory.MemoryBrowserFilterState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Memory Assistant — the Phase 6 Archivist's user-facing surface. Layout,
 * wording and behavior are the owner's approved design
 * (`Memory System/memory_assistant_design.md` §1–§2 + the July 8 2026 evening
 * answers): helper text, three facts lines ("Date of Last Run" per answer 1),
 * the Analyze Conversations button with live per-conversation progress
 * (batch wording when the run is size-batched, answer 4), completion states,
 * "View Pending Memories." opening the browser filtered to Pending (answer 2),
 * and the last five runs under "Recent Memory Analysis" with a far-left Rerun
 * (answer 3).
 *
 * FAILURE STATES ARE DELIBERATELY SILENT for now: the owner supplies failure
 * wording after seeing the real cases (design doc §1) — a failed or
 * unconfigured run resets the button and logs to the Memory log, showing no
 * invented copy. The "deleted since this run" flag on recent rows is stored
 * but not yet displayed for the same reason.
 */
class MemoryAssistantActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var factSinceRun: TextView? = null
    private var factPending: TextView? = null
    private var factLastRun: TextView? = null
    private var btnAnalyze: MaterialButton? = null
    private var textRunStatus: TextView? = null
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
        btnAnalyze = findViewById(R.id.btn_analyze)
        textRunStatus = findViewById(R.id.text_run_status)
        linkViewPending = findViewById(R.id.link_view_pending)
        runsContainer = findViewById(R.id.runs_container)

        applyTheme()
        btnBack?.setOnClickListener { finish() }
        btnAnalyze?.setOnClickListener { if (!running) startRun(null) }
        linkViewPending?.setOnClickListener { openPendingBrowser() }
    }

    override fun onResume() {
        super.onResume()
        // Owner behavior rule: a finished "Complete!" is not sticky across a
        // fresh visit — coming back with nothing new shows the idle button.
        if (!running && completedThisVisit) {
            completedThisVisit = false
            btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
            textRunStatus?.visibility = android.view.View.GONE
            linkViewPending?.visibility = android.view.View.GONE
        }
        refreshFactsAndRuns()
    }

    /* ---------------- data ---------------- */

    private fun refreshFactsAndRuns() {
        lifecycleScope.launch(Dispatchers.IO) {
            val provisioned = MemoryStore.isProvisioned(this@MemoryAssistantActivity)
            val eligible = if (provisioned) Archivist.eligibleConversationCount(this@MemoryAssistantActivity) else 0
            val pendingChats = if (provisioned)
                MemoryStore.getInstance(this@MemoryAssistantActivity).pendingReviewCount() else 0
            val runs = if (provisioned)
                MemoryStore.getInstance(this@MemoryAssistantActivity).getArchivistRuns(RECENT_RUNS) else emptyList()
            withContext(Dispatchers.Main) {
                factSinceRun?.text = getString(R.string.memory_assistant_fact_since_run, eligible)
                factPending?.text = getString(R.string.memory_assistant_fact_pending, pendingChats)
                val lastRun = runs.firstOrNull()
                if (lastRun != null) {
                    factLastRun?.visibility = android.view.View.VISIBLE
                    factLastRun?.text = getString(
                        R.string.memory_assistant_fact_last_run, formatDate(lastRun.startedAt)
                    )
                } else {
                    // No run has ever happened — the line has nothing truthful
                    // to say, so it stays hidden rather than inventing a value.
                    factLastRun?.visibility = android.view.View.GONE
                }
                renderRuns(runs)
            }
        }
    }

    private fun renderRuns(runs: List<ArchivistRunRecord>) {
        val container = runsContainer ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (run in runs) {
            val row = inflater.inflate(R.layout.view_archivist_run_row, container, false)
            row.findViewById<TextView>(R.id.run_date)?.text = formatDate(run.startedAt)
            row.findViewById<TextView>(R.id.run_result)?.text =
                if (run.foundCount > 0) getString(R.string.memory_assistant_run_found, run.foundCount)
                else getString(R.string.memory_assistant_run_none)
            row.findViewById<MaterialButton>(R.id.btn_rerun)?.setOnClickListener {
                if (!running) startRun(run.runId)
            }
            container.addView(row)
        }
    }

    /* ---------------- the run ---------------- */

    private fun startRun(rerunOfRunId: String?) {
        running = true
        btnAnalyze?.setText(R.string.memory_assistant_btn_running)
        btnAnalyze?.isEnabled = false
        linkViewPending?.visibility = android.view.View.GONE
        textRunStatus?.visibility = android.view.View.VISIBLE
        textRunStatus?.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            val onProgress: (Archivist.Progress) -> Unit = { p ->
                runOnUiThread { showProgress(p) }
            }
            val outcome = try {
                if (rerunOfRunId == null) Archivist.analyze(this@MemoryAssistantActivity, onProgress)
                else Archivist.rerun(this@MemoryAssistantActivity, rerunOfRunId, onProgress)
            } catch (e: Exception) {
                Archivist.RunOutcome(null, 0, 0, 0, emptyList(), error = e.message)
            }
            withContext(Dispatchers.Main) {
                running = false
                btnAnalyze?.isEnabled = true
                if (outcome.notConfigured || outcome.error != null) {
                    // Failure copy is the owner's to write (design doc §1) —
                    // until then the button resets and the Memory log carries
                    // the diagnosis; nothing invented is shown.
                    btnAnalyze?.setText(R.string.memory_assistant_btn_idle)
                    textRunStatus?.visibility = android.view.View.GONE
                } else {
                    completedThisVisit = true
                    btnAnalyze?.setText(R.string.memory_assistant_btn_done)
                    if (outcome.memoriesFound > 0) {
                        textRunStatus?.text =
                            getString(R.string.memory_assistant_done_found, outcome.memoriesFound)
                        linkViewPending?.visibility = android.view.View.VISIBLE
                    } else {
                        textRunStatus?.text = getString(R.string.memory_assistant_done_none)
                    }
                }
                refreshFactsAndRuns()
            }
        }
    }

    private fun showProgress(p: Archivist.Progress) {
        textRunStatus?.text = if (p.batchCount <= 1) {
            // Small run: the design doc's plain form.
            getString(R.string.memory_assistant_running_moment) + "\n" +
                getString(R.string.memory_assistant_running_conversation, p.conversationInBatch, p.conversationsInBatch)
        } else {
            // Size-batched run: the owner's batch wording (answer 4), repeated
            // per batch with the batch number spelled out ("Batch One").
            getString(R.string.memory_assistant_running_while) + "\n" +
                getString(R.string.memory_assistant_running_batched) + "\n" +
                getString(R.string.memory_assistant_running_batch, batchWord(p.batchIndex)) + "\n" +
                getString(R.string.memory_assistant_running_batch_conversations, p.conversationInBatch, p.conversationsInBatch)
        }
    }

    /** "Batch One", "Batch Two"… — the owner wrote the batch number as a word.
     *  Past twenty (unrealistic) the digit form keeps it honest. */
    private fun batchWord(n: Int): String {
        val words = arrayOf(
            "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen", "Twenty"
        )
        return if (n in 1..20) words[n - 1] else n.toString()
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
