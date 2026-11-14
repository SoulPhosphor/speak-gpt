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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.CompanionRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.memory.librarian.LiveRetrieval
import org.teslasoft.assistant.preferences.memory.librarian.SearchTestReport
import org.teslasoft.assistant.preferences.memory.librarian.SearchTuning
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.assistant.ui.widgets.SamplingParameterControl
import org.teslasoft.assistant.ui.widgets.SamplingParameterSpec
import java.util.Locale

/**
 * Memory Search Test (`Memory System/dynamic_memory_plan.md`, step 1.4): the
 * user types a message and sees which memories a chat would send with it and
 * why, with every number shown, and can adjust the search numbers. Testing is
 * read-only and sends nothing to any AI service. Adjusted numbers apply to the
 * on-screen test at once; Use in Chats saves them to the stored retrieval
 * policy, which every chat reads.
 */
class MemorySearchTestActivity : SettingsPageActivity() {

    private lateinit var dropdownTestAs: TextView
    private lateinit var fieldMessage: EditText
    private lateinit var btnRun: MaterialButton
    private lateinit var sectionResult: View
    private lateinit var textSummary: TextView
    private lateinit var textMode: TextView
    private lateinit var textTiming: TextView
    private lateinit var textSectionSearch: TextView
    private lateinit var textSectionTiming: TextView
    private lateinit var headerSent: View
    private lateinit var listSent: LinearLayout
    private lateinit var headerNotSent: View
    private lateinit var listNotSent: LinearLayout
    private lateinit var textPreview: TextView
    private lateinit var textSettingsStatus: TextView
    private lateinit var fieldMaxMemories: EditText
    private lateinit var fieldMemorySpace: EditText
    private lateinit var fieldSmallLibrary: EditText

    private lateinit var sliders: List<Pair<SamplingParameterControl, Slot>>

    /** One slider-backed number: its range spec, reader, and writer. */
    private class Slot(
        val spec: SamplingParameterSpec,
        val read: (LiveRetrieval.Params) -> Double,
        val write: (LiveRetrieval.Params, Double) -> LiveRetrieval.Params
    )

    private var saved = SearchTuning()
    private var trial = SearchTuning()
    private var companions: List<CompanionRecord> = emptyList()
    /** -1 = Ordinary Chat; otherwise an index into [companions]. */
    private var testAsIndex = -1
    private var prepared: Librarian.PreparedSearchTest? = null
    private var useImportanceRatings = true
    private var populating = false
    /** Increases with every test request; a result from an older request is
     *  discarded, so a slow earlier test can never replace a newer one. */
    private var testGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_search_test)

        val actionBar = findViewById<View>(R.id.action_bar)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        ScreenChrome.fitSystemBarsAndKeyboard(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))

        bindViews()
        btnBack.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }

        useImportanceRatings = try {
            Preferences.getPreferences(this, "").getUseImportanceRatings()
        } catch (_: Exception) { true }

        dropdownTestAs.text = getString(R.string.memory_search_test_ordinary_chat)
        dropdownTestAs.setOnClickListener { openTestAs() }
        btnRun.setOnClickListener { runTest() }
        findViewById<MaterialButton>(R.id.btn_use_in_chats).setOnClickListener { useInChats() }
        findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener {
            trial = SearchTuning().normalized()
            populateControls(trial)
            textSettingsStatus.text = getString(R.string.memory_search_reset_done)
            rescore()
        }

        lifecycleScope.launch {
            val (tuning, people) = withContext(Dispatchers.IO) {
                if (!MemoryStore.isProvisioned(this@MemorySearchTestActivity)) {
                    SearchTuning() to emptyList()
                } else {
                    val store = MemoryStore.getInstance(this@MemorySearchTestActivity)
                    val t = try { SearchTuning.fromPolicyJson(store.getRetrievalPolicyJson()) } catch (_: Exception) { SearchTuning() }
                    val c = try {
                        store.getCompanions().filter { it.status != "draft" && it.memoryParticipation != "none" }
                    } catch (_: Exception) { emptyList() }
                    t to c
                }
            }
            saved = tuning
            trial = tuning
            companions = people
            populateControls(trial)
        }
    }

    private fun bindViews() {
        dropdownTestAs = findViewById(R.id.dropdown_test_as)
        fieldMessage = findViewById(R.id.field_message)
        btnRun = findViewById(R.id.btn_run)
        sectionResult = findViewById(R.id.section_result)
        textSummary = findViewById(R.id.text_summary)
        textMode = findViewById(R.id.text_mode)
        textTiming = findViewById(R.id.text_timing)
        textSectionSearch = findViewById(R.id.text_section_search)
        textSectionTiming = findViewById(R.id.text_section_timing)
        headerSent = findViewById(R.id.header_sent)
        listSent = findViewById(R.id.list_sent)
        headerNotSent = findViewById(R.id.header_not_sent)
        listNotSent = findViewById(R.id.list_not_sent)
        textPreview = findViewById(R.id.text_preview)
        textSettingsStatus = findViewById(R.id.text_settings_status)
        fieldMaxMemories = findViewById(R.id.field_max_memories)
        fieldMemorySpace = findViewById(R.id.field_memory_space)
        fieldSmallLibrary = findViewById(R.id.field_small_library)

        sliders = listOf(
            findViewById<SamplingParameterControl>(R.id.slider_minimum_match) to Slot(
                SamplingParameterSpec.MEMORY_MINIMUM_MATCH, { it.similarityFloor }, { p, v -> p.copy(similarityFloor = v) }
            ),
            findViewById<SamplingParameterControl>(R.id.slider_stand_out) to Slot(
                SamplingParameterSpec.MEMORY_STAND_OUT, { it.standoutMin }, { p, v -> p.copy(standoutMin = v) }
            ),
            findViewById<SamplingParameterControl>(R.id.slider_keyword_match) to Slot(
                SamplingParameterSpec.MEMORY_KEYWORD_MATCH, { it.keywordMin }, { p, v -> p.copy(keywordMin = v) }
            ),
            findViewById<SamplingParameterControl>(R.id.slider_importance) to Slot(
                SamplingParameterSpec.MEMORY_IMPORTANCE_INFLUENCE, { it.importanceWeight }, { p, v -> p.copy(importanceWeight = v) }
            ),
            findViewById<SamplingParameterControl>(R.id.slider_small_library_match) to Slot(
                SamplingParameterSpec.MEMORY_SMALL_LIBRARY_MATCH, { it.smallLibraryFloor }, { p, v -> p.copy(smallLibraryFloor = v) }
            ),
            findViewById<SamplingParameterControl>(R.id.slider_full_strength) to Slot(
                SamplingParameterSpec.MEMORY_FULL_STRENGTH_MATCH, { it.fullStrengthSimilarity }, { p, v -> p.copy(fullStrengthSimilarity = v) }
            ),
            findViewById<SamplingParameterControl>(R.id.slider_agreement) to Slot(
                SamplingParameterSpec.MEMORY_AGREEMENT_BONUS, { it.agreementBonus }, { p, v -> p.copy(agreementBonus = v) }
            ),
            findViewById<SamplingParameterControl>(R.id.slider_min_spread) to Slot(
                SamplingParameterSpec.MEMORY_MINIMUM_SPREAD, { it.minSpread }, { p, v -> p.copy(minSpread = v) }
            )
        )

        bindWholeNumber(fieldMaxMemories, SearchTuning.MAX_MEMORIES_RANGE, { trial.maxMemories }) { v ->
            applyTrial(trial.copy(maxMemories = v))
        }
        bindWholeNumber(fieldMemorySpace, SearchTuning.MEMORY_SPACE_RANGE, { trial.memorySpace }) { v ->
            applyTrial(trial.copy(memorySpace = v))
        }
        bindWholeNumber(fieldSmallLibrary, 0..SearchTuning.SMALL_LIBRARY_SIZE_MAX, { trial.params.smallLibrarySize }) { v ->
            applyTrial(trial.copy(params = trial.params.copy(smallLibrarySize = v)))
        }
    }

    /* ---------------------------- tuning controls ---------------------------- */

    private fun populateControls(t: SearchTuning) {
        populating = true
        for ((control, slot) in sliders) {
            control.configure(slot.spec, slot.read(t.params).toFloat()) { value ->
                if (populating) return@configure
                applyTrial(trial.copy(params = slot.write(trial.params, value.toDouble())))
            }
        }
        fieldMaxMemories.setText(t.maxMemories.toString())
        fieldMemorySpace.setText(t.memorySpace.toString())
        fieldSmallLibrary.setText(t.params.smallLibrarySize.toString())
        populating = false
    }

    /** Applies a changed number using the same bounds chats apply, shows any
     *  corrected value in the controls, and re-scores the test. */
    private fun applyTrial(requested: SearchTuning) {
        val normalized = requested.normalized()
        trial = normalized
        if (normalized != requested) populateControls(normalized)
        markUnsaved()
        rescore()
    }

    /** A whole-number field: valid typing applies at once; anything else is
     *  restored to the current value when the field loses focus. */
    private fun bindWholeNumber(
        field: EditText,
        range: IntRange,
        current: () -> Int,
        apply: (Int) -> Unit
    ) {
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (populating) return
                val v = s?.toString()?.trim()?.toIntOrNull() ?: return
                if (v !in range || v == current()) return
                apply(v)
            }
        })
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val v = field.text?.toString()?.trim()?.toIntOrNull()
            if (v == null || v !in range) {
                populating = true
                field.setText(current().toString())
                populating = false
            }
        }
    }

    private fun markUnsaved() {
        textSettingsStatus.text = if (trial != saved) getString(R.string.memory_search_unsaved) else ""
    }

    private fun useInChats() {
        currentFocus?.clearFocus()
        if (!MemoryStore.isProvisioned(this)) {
            textSettingsStatus.text = getString(R.string.memory_search_test_not_set_up)
            return
        }
        val toSave = trial
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val store = MemoryStore.getInstance(this@MemorySearchTestActivity)
                    store.setRetrievalPolicyJson(SearchTuning.mergeIntoPolicyJson(store.getRetrievalPolicyJson(), toSave))
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            if (error == null) {
                saved = toSave
                textSettingsStatus.text = getString(R.string.memory_search_saved)
            } else {
                textSettingsStatus.text = getString(R.string.memory_search_save_failed, error)
            }
        }
    }

    private fun leave() {
        if (trial != saved) {
            DiscardChangesDialog.show(this) { finish() }
        } else {
            finish()
        }
    }

    /* ------------------------------- the test -------------------------------- */

    private fun openTestAs() {
        val labels = listOf(getString(R.string.memory_search_test_ordinary_chat)) + companions.map { it.currentName }
        AppDropdown.show(dropdownTestAs, labels, selectedIndex = testAsIndex + 1) { picked ->
            testAsIndex = picked - 1
            dropdownTestAs.text = labels[picked]
            prepared = null
            if (fieldMessage.text.isNotBlank() && sectionResult.visibility == View.VISIBLE) runTest()
        }
    }

    private fun currentScope(): RetrievalScope {
        val companion = companions.getOrNull(testAsIndex)
        val companionId = companion?.takeIf { it.memoryParticipation == "full" }?.companionId
        return RetrievalScope(companionId, null, null, null)
    }

    private fun runTest() {
        val message = fieldMessage.text?.toString()?.trim().orEmpty()
        sectionResult.visibility = View.VISIBLE
        if (message.isEmpty()) {
            showMessageOnly(getString(R.string.memory_search_test_message_needed))
            return
        }
        btnRun.isEnabled = false
        btnRun.text = getString(R.string.memory_search_test_running)
        val scope = currentScope()
        val generation = ++testGeneration
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Result.success(Librarian.getInstance(this@MemorySearchTestActivity).prepareSearchTest(scope, message))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (generation != testGeneration) return@launch
            btnRun.isEnabled = true
            btnRun.text = getString(R.string.memory_search_test_run)
            outcome.onSuccess { p ->
                prepared = p
                if (p == null) {
                    showMessageOnly(getString(R.string.memory_search_test_not_set_up))
                } else {
                    rescore()
                }
            }.onFailure { e ->
                prepared = null
                showMessageOnly(getString(R.string.memory_search_test_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Re-scores the prepared message with the current numbers. Cheap: the
     *  message is embedded once per test, not once per adjustment. */
    private fun rescore() {
        val p = prepared ?: return
        if (p.corpus.isEmpty()) {
            showMessageOnly(getString(R.string.memory_search_test_no_memories))
            return
        }
        val report = SearchTestReport.build(
            p.query, p.queryVector, p.corpus, trial, useImportanceRatings, sections = p.sections
        )
        showReport(p, report)
    }

    private fun showMessageOnly(text: String) {
        textSummary.text = text
        textMode.visibility = View.GONE
        textTiming.visibility = View.GONE
        textSectionSearch.visibility = View.GONE
        textSectionTiming.visibility = View.GONE
        headerSent.visibility = View.GONE
        headerNotSent.visibility = View.GONE
        listSent.removeAllViews()
        listNotSent.removeAllViews()
        textPreview.text = getString(R.string.memory_search_test_preview_empty)
    }

    private fun showReport(p: Librarian.PreparedSearchTest, report: SearchTestReport.Report) {
        val sentCount = report.sent.size
        textSummary.text = if (sentCount == 0) {
            getString(R.string.memory_search_test_none_sent)
        } else {
            resources.getQuantityString(R.plurals.memory_search_test_sent_count, sentCount, sentCount)
        }
        val d = report.diagnostics
        textMode.visibility = View.VISIBLE
        textMode.text = when {
            !p.hasModel -> getString(R.string.memory_search_test_mode_no_model)
            d.withVector > 0 && !d.meaningRan -> getString(R.string.memory_search_test_mode_embed_failed)
            d.withVector < d.eligible -> getString(R.string.memory_search_test_mode_partial, d.eligible - d.withVector, d.eligible)
            else -> getString(R.string.memory_search_test_mode_both)
        }
        textTiming.visibility = View.VISIBLE
        textTiming.text = getString(R.string.memory_search_test_timing, p.embedMillis, d.eligible, p.loadMillis)
        // A long message is also searched in sections. Say when that search
        // stopped early, and how long it took.
        val s = report.sections
        val stopped = when {
            s.timedOut -> R.string.memory_search_test_sections_time_limit
            s.sectionLimitReached -> R.string.memory_search_test_sections_limit
            else -> null
        }
        textSectionSearch.visibility = if (stopped == null) View.GONE else View.VISIBLE
        if (stopped != null) textSectionSearch.text = getString(stopped, s.checked, s.sections.size)
        textSectionTiming.visibility = if (s.checked == 0) View.GONE else View.VISIBLE
        if (s.checked > 0) {
            textSectionTiming.text = getString(R.string.memory_search_test_sections_timing, s.checked, s.millis)
        }

        headerSent.visibility = if (report.sent.isEmpty()) View.GONE else View.VISIBLE
        headerNotSent.visibility = if (report.notSent.isEmpty()) View.GONE else View.VISIBLE
        fill(listSent, report.sent, d)
        fill(listNotSent, report.notSent, d)
        textPreview.text = report.preview.ifBlank { getString(R.string.memory_search_test_preview_empty) }
    }

    private fun fill(list: LinearLayout, rows: List<SearchTestReport.Row>, d: LiveRetrieval.Diagnostics) {
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val params = trial.params
        val small = d.withVector < params.smallLibrarySize
        for (row in rows) {
            val e = row.explanation
            val card = inflater.inflate(R.layout.view_memory_search_result, list, false)
            card.findViewById<TextView>(R.id.text_memory).text = e.memory.content.trim()
            card.findViewById<TextView>(R.id.text_outcome).text = getString(outcomeText(row, small))
            row.section?.let { match ->
                val found = card.findViewById<TextView>(R.id.text_section)
                found.visibility = View.VISIBLE
                found.text = getString(
                    R.string.memory_search_test_detail_section,
                    match.section.number, match.sectionCount, match.section.opening()
                )
            }
            card.findViewById<TextView>(R.id.text_meaning).text = when {
                e.similarity == null && d.meaningRan -> getString(R.string.memory_search_test_detail_meaning_no_vector)
                e.similarity == null -> getString(R.string.memory_search_test_detail_meaning_not_run)
                small -> getString(
                    R.string.memory_search_test_detail_meaning_small,
                    num(e.similarity), num(maxOf(params.similarityFloor, params.smallLibraryFloor))
                )
                else -> getString(
                    R.string.memory_search_test_detail_meaning,
                    num(e.similarity), num(params.similarityFloor),
                    num(e.standout ?: 0.0, 1), num(params.standoutMin, 1)
                )
            }
            card.findViewById<TextView>(R.id.text_keyword).text = if (e.matchedTerms.isEmpty()) {
                getString(R.string.memory_search_test_detail_keyword_none, num(params.keywordMin))
            } else {
                getString(
                    R.string.memory_search_test_detail_keyword,
                    num(e.keywordStrength), num(params.keywordMin), e.matchedTerms.joinToString(", ")
                )
            }
            val rating = if (e.memory.importance > 0) "+${e.memory.importance}" else e.memory.importance.toString()
            val nudge = (if (e.importanceBonus > 0) "+" else "") + num(e.importanceBonus)
            card.findViewById<TextView>(R.id.text_score).text =
                getString(R.string.memory_search_test_detail_score, rating, nudge, num(e.score))
            list.addView(card)
        }
    }

    private fun outcomeText(row: SearchTestReport.Row, small: Boolean): Int = when (row.outcome) {
        SearchTestReport.Outcome.SENT -> R.string.memory_search_test_outcome_sent
        SearchTestReport.Outcome.SENT_PAST_LIMIT -> R.string.memory_search_test_outcome_sent_past_limit
        SearchTestReport.Outcome.LIMIT_FULL -> R.string.memory_search_test_outcome_limit_full
        SearchTestReport.Outcome.NO_SPACE -> R.string.memory_search_test_outcome_no_space
        SearchTestReport.Outcome.NOT_A_MATCH -> {
            val e = row.explanation
            val floor = if (small) maxOf(trial.params.similarityFloor, trial.params.smallLibraryFloor) else trial.params.similarityFloor
            when {
                e.similarity == null -> R.string.memory_search_test_outcome_no_match
                e.similarity < floor -> R.string.memory_search_test_outcome_below_minimum
                !small -> R.string.memory_search_test_outcome_not_standing_out
                else -> R.string.memory_search_test_outcome_no_match
            }
        }
    }

    private fun num(value: Double, decimals: Int = 2): String =
        String.format(Locale.getDefault(), "%.${decimals}f", value)
}
