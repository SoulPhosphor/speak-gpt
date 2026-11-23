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

package org.teslasoft.assistant.ui.activities.memory

import org.teslasoft.assistant.ui.util.ScreenChrome

import androidx.activity.addCallback
import androidx.core.widget.doAfterTextChanged
import org.teslasoft.assistant.ui.widgets.MemoryTokenMeter
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import org.teslasoft.assistant.preferences.memory.librarian.MemoryTokenBudget

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.teslasoft.assistant.ui.activities.SettingsPageActivity
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.PendingPairSnapshot
import org.teslasoft.assistant.preferences.memory.PendingMatchLoader
import org.teslasoft.assistant.preferences.memory.PendingMatchPresentation
import org.teslasoft.assistant.ui.adapters.memory.PendingMatchText
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The Possible Match Review screen (Step 1.5): a dedicated full-page comparison
 * where a proposed Associative Memory is weighed against the existing memories
 * it may overlap, and one of the three atomic resolutions is applied.
 *
 * The proposed memory is the first card and scrolls normally (never pinned);
 * the Related Memory (the match opened from the Pending card, or the closest
 * one) follows, then the Additional Matches (Stage 6C), each in the normal
 * memory-card format with its status and match method, a checkbox (top-left;
 * only the opened match starts ticked, and Pending matches have dedicated
 * Stage 6D controls instead) and an Info control (top-right). Resolutions
 * live in their own section after the last match:
 *   - Save & Edit Old Memory — one selection only; activates the proposal, keeps
 *     the old memory active, and applies inline title/content edits to it;
 *   - Save & Supersede — activates the proposal, marks the checked memories
 *     superseded (history preserved);
 *   - Save & Replace — activates the proposal, permanently deletes the checked
 *     memories.
 *
 * Every resolution is revalidated against the live store and applied through the
 * store's atomic operations — never partially. If the proposal or a selected
 * memory changed or vanished since it was shown (checked inside the store
 * transaction), nothing is applied and the user stays here.
 */
class MemoryPossibleMatchReviewActivity : SettingsPageActivity() {

    companion object {
        /** Stage 6C: the related memory tapped on the Pending card. */
        const val EXTRA_RELATED_ID = "relatedMemoryId"
        /** Stage 6C: open at the Additional Matches section. */
        const val EXTRA_TO_ADDITIONAL = "toAdditionalMatches"
    }

    private var preferences: Preferences? = null
    private var chatId: String = ""
    private var draftId: String = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var container: LinearLayout? = null
    private var resolution: View? = null
    private var selectHint: TextView? = null
    private var btnEdit: MaterialButton? = null
    private var btnSupersede: MaterialButton? = null
    private var btnReplace: MaterialButton? = null

    /** One existing possible-match card and its controls. */
    private class MatchHolder(
        val record: MemoryRecord,
        val card: LinearLayout,
        val check: MaterialCheckBox,
        val editSection: View,
        val editTitle: TextInputEditText,
        val editContent: TextInputEditText
    )

    private val matchHolders = ArrayList<MatchHolder>()

    /** Stage 6C: the related memory the user tapped on the Pending card (null
     *  when Review was opened from its button; then the match shown first),
     *  kept selected across reloads, and whether the first load opens at the
     *  additional matches. */
    private var requestedRelatedId: String? = null
    private var scrollToAdditional = false
    private var firstLoad = true

    /** Set once the tapped memory was found missing and another match was shown
     *  in its place: that match was not explicitly opened, so it is never
     *  preselected, on this load or a reload. */
    private var substituted = false

    /** What the user is looking at: the proposal's and every shown match's
     *  [PendingMatchPresentation.reviewFingerprint]. A resolution applies only
     *  while these still hold (checked inside the store transaction). */
    private var shownFingerprints: Map<String, String> = emptyMap()

    /** The card currently in inline-edit mode (Save & Edit Old Memory), or null. */
    private var editingCardId: String? = null

    /** True while a resolution is being applied, to block double-taps. */
    private var applying = false
    private var pendingPair: PendingPairReviewController? = null
    private var loadGeneration = 0
    private val pairNavigation = ArrayList<View>()
    private val importanceControls = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_possible_match_review)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        draftId = intent.extras?.getString("draftId", "") ?: ""
        requestedRelatedId = intent.extras?.getString(EXTRA_RELATED_ID)
        scrollToAdditional = intent.extras?.getBoolean(EXTRA_TO_ADDITIONAL, false) ?: false
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        container = findViewById(R.id.review_container)
        resolution = findViewById(R.id.review_resolution)
        selectHint = findViewById(R.id.review_select_hint)
        btnEdit = findViewById(R.id.btn_review_edit)
        btnSupersede = findViewById(R.id.btn_review_supersede)
        btnReplace = findViewById(R.id.btn_review_replace)

        applyTheme()
        btnBack?.setOnClickListener { if (pendingPair?.handleBack() != true) finish() }
        onBackPressedDispatcher.addCallback(this) { if (pendingPair?.handleBack() != true) finish() }
        btnEdit?.setOnClickListener { onEditClick() }
        btnSupersede?.setOnClickListener { applyResolution(ResolutionKind.SUPERSEDE) }
        btnReplace?.setOnClickListener { applyResolution(ResolutionKind.REPLACE) }

        load()
    }

    /* ------------------------------ load ------------------------------ */

    private fun load() {
        editingCardId = null
        pendingPair = null
        val generation = ++loadGeneration
        val requested = requestedRelatedId
        runOffThread {
            if (!MemoryStore.isProvisioned(this)) {
                runOnUiThread { finish() }
                return@runOffThread
            }
            val store = MemoryStore.getInstance(this)
            val proposal = store.getMemory(draftId)?.takeIf { it.status == "draft" }
            if (proposal == null) {
                runOnUiThread {
                    Toast.makeText(this, R.string.mem_review_gone, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@runOffThread
            }
            // Fresh detection — the same finder the browser used, recomputed here
            // so Review always reflects the current library.
            val loaded = PendingMatchLoader.load(this, draftId)
            val openedNow = PendingMatchPresentation.open(loaded.ordered, requested)
            val pair = openedNow?.selected?.takeIf { it.reviewOnly }?.let {
                store.capturePendingPair(draftId, it.memoryId)
            }
            // Match metadata must describe the exact wordings displayed.
            val raced = openedNow?.selected?.reviewOnly == true && (pair == null ||
                PendingMatchPresentation.reviewFingerprint(pair.proposal) != PendingMatchPresentation.reviewFingerprint(proposal) ||
                PendingMatchPresentation.reviewFingerprint(pair.related) != loaded.records[openedNow.selected.memoryId]?.let {
                    PendingMatchPresentation.reviewFingerprint(it)
                } || (openedNow.selected.method == PendingMatchPresentation.Method.ALTERNATIVE &&
                    (pair.related.memoryId !in pair.links[pair.proposal.memoryId].orEmpty() ||
                        pair.proposal.memoryId !in pair.links[pair.related.memoryId].orEmpty())))
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                if (raced) { load(); return@runOnUiThread }
                // The opened match stays the selected one across reloads (an
                // importance change or a stale decision) while it is still a match.
                val opened = openedNow
                if (opened == null) {
                    // The overlap is gone; nothing to resolve. The browser will
                    // show this suggestion as conflict-free after it re-checks.
                    Toast.makeText(this, R.string.mem_review_no_matches, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                if (opened.requestedMissing) {
                    Toast.makeText(this, R.string.mem_review_related_changed, Toast.LENGTH_LONG).show()
                    substituted = true
                }
                requestedRelatedId = opened.selected.memoryId
                val scrollAdditional = firstLoad && scrollToAdditional
                firstLoad = false
                buildUi(proposal, opened, loaded.records, scrollAdditional, pair)
            }
        }
    }

    private fun buildUi(
        proposal: MemoryRecord,
        opened: PendingMatchPresentation.Opened,
        records: Map<String, MemoryRecord>,
        scrollAdditional: Boolean,
        pair: PendingPairSnapshot?
    ) {
        val c = container ?: return
        c.removeAllViews()
        matchHolders.clear()
        pairNavigation.clear()
        importanceControls.clear()
        if (pair != null) pendingPair = PendingPairReviewController(
            this, pair, ::runOffThread,
            setLocked = { locked -> applying = locked; setButtonsEnabled(!locked) },
            reload = ::load, finish = { finish() }
        )

        // Stage 6C order (owner design): the full proposal, the match the user
        // opened, then the additional matches.
        addIntro(c)
        if (pair != null) c.addView(layoutInflater.inflate(R.layout.view_pending_pair_intro, c, false))
        addHeader(c, getString(R.string.mem_review_proposed_header))
        val proposalHolder = addMemoryCard(c, proposal, match = null, checked = false)
        pendingPair?.bindCard(proposalHolder.card, proposal)
        addHeader(c, getString(R.string.mem_match_related_header))
        val preselected = if (substituted) emptySet() else opened.preselected
        val relatedHolder = addMatch(c, opened.selected, records, preselected)
        if (pair != null && relatedHolder != null) {
            pendingPair?.bindCard(relatedHolder.card, pair.related)
            pendingPair?.addKeepBoth(c)
        }
        var additionalHeader: View? = null
        if (opened.additional.isNotEmpty()) {
            additionalHeader = addHeader(c, getString(R.string.mem_match_additional_header))
            for (m in opened.additional) {
                val holder = addMatch(c, m, records, preselected)
                if (m.reviewOnly && holder != null) {
                    val open = {
                        intent.putExtra(EXTRA_RELATED_ID, m.memoryId)
                        requestedRelatedId = m.memoryId
                        substituted = false
                        load()
                    }
                    val controller = pendingPair
                    if (controller != null) controller.addOpenPair(holder.card, open)
                    else {
                        val button = layoutInflater.inflate(R.layout.view_pending_pair_open, holder.card, false)
                        holder.card.addView(button)
                        pairNavigation.add(button)
                        button.setOnClickListener { if (!applying && editingCardId == null) open() }
                    }
                }
            }
        }
        shownFingerprints = (listOf(proposal) + (listOf(opened.selected) + opened.additional)
            .mapNotNull { records[it.memoryId] })
            .associate { it.memoryId to PendingMatchPresentation.reviewFingerprint(it) }

        resolution?.visibility = View.VISIBLE
        refreshResolutionButtons()
        if (scrollAdditional && additionalHeader != null) {
            val header = additionalHeader
            findViewById<ScrollView>(R.id.scroll)?.let { scroll ->
                scroll.post {
                    // The header's offset within the scrolled content.
                    var y = header.top
                    var parent = header.parent
                    while (parent is View && parent !== scroll) {
                        if (parent.parent !== scroll) y += parent.top
                        parent = parent.parent
                    }
                    scroll.smoothScrollTo(0, y)
                }
            }
        }
    }

    private fun addMatch(
        parent: LinearLayout,
        match: PendingMatchPresentation.Presented,
        records: Map<String, MemoryRecord>,
        preselected: Set<String>
    ): MatchHolder? {
        val record = records[match.memoryId] ?: return null
        val holder = addMemoryCard(parent, record, match, checked = match.memoryId in preselected)
        // Pending matches have dedicated controls, never legacy resolution targets.
        if (!match.reviewOnly) matchHolders.add(holder)
        return holder
    }

    private fun addIntro(parent: LinearLayout) {
        val tv = TextView(this).apply {
            text = getString(R.string.mem_review_intro)
            setTextColor(ResourcesCompat.getColor(resources, R.color.text_subtitle, theme))
            textSize = 14f
            val h = dp(20); setPadding(h, dp(4), h, dp(12))
        }
        parent.addView(tv)
    }

    private fun addHeader(parent: LinearLayout, text: String): View {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        parent.addView(tv)
        return tv
    }

    private fun addMemoryCard(
        parent: LinearLayout,
        record: MemoryRecord,
        match: PendingMatchPresentation.Presented?,
        checked: Boolean
    ): MatchHolder {
        val withCheckbox = match != null && !match.reviewOnly
        val card = layoutInflater.inflate(R.layout.view_review_memory_card, parent, false)
        val meter = card.findViewById<MemoryTokenMeter>(R.id.memory_length_meter)
        meter.bind(record)
        val inlineContent = card.findViewById<TextInputEditText>(R.id.review_edit_content)
        inlineContent.doAfterTextChanged {
            meter.refresh(MemoryTokenBudget.Input.forEdit(record, it?.toString().orEmpty(), record.tagsJson))
        }
        card.findViewById<ImageView>(R.id.review_icon).setImageResource(iconForScope(record.scope))
        // Titles are retired (§3.1): the review card shows content, not a title.
        card.findViewById<TextView>(R.id.review_title).visibility = View.GONE
        val tags = parseTags(record.tagsJson)
        card.findViewById<TextView>(R.id.review_tags).apply {
            if (tags.isEmpty()) visibility = View.GONE
            else { visibility = View.VISIBLE; text = formatTags(tags) }
        }
        card.findViewById<TextView>(R.id.review_content).text = record.content
        card.findViewById<TextView>(R.id.review_match_detail).apply {
            if (match == null) visibility = View.GONE
            else { visibility = View.VISIBLE; text = PendingMatchText.detail(this@MemoryPossibleMatchReviewActivity, match) }
        }
        card.findViewById<MaterialButton>(R.id.review_importance).apply {
            importanceControls.add(this)
            if (preferences?.getUseImportanceRatings() ?: true) {
                visibility = View.VISIBLE
                text = getString(R.string.mem_importance_value_fmt, importanceLabel(record.importance))
                setOnClickListener { showImportancePicker(record) }
            } else {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
        card.findViewById<ImageButton>(R.id.review_info).setOnClickListener {
            MemoryInfoDialog.show(this, record)
        }
        val check = card.findViewById<MaterialCheckBox>(R.id.review_check)
        if (withCheckbox) {
            check.visibility = View.VISIBLE
            check.isChecked = checked
            check.setOnCheckedChangeListener { _, _ -> onSelectionChanged() }
        } else {
            check.visibility = View.GONE
        }
        parent.addView(card)
        return MatchHolder(
            record, card as LinearLayout, check,
            card.findViewById(R.id.review_edit_section),
            card.findViewById(R.id.review_edit_title),
            card.findViewById(R.id.review_edit_content)
        )
    }

    private fun showImportancePicker(record: MemoryRecord) {
        if (applying || editingCardId != null) return
        val values = listOf(-2, -1, 0, 1, 2, 3)
        val labels = values.map { importanceLabel(it) }.toTypedArray()
        val selected = values.indexOf(record.importance.coerceIn(-2, 3)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val picked = values[which]
                dialog.dismiss()
                runOffThread {
                    val store = MemoryStore.getInstance(this)
                    val live = store.getMemory(record.memoryId) ?: return@runOffThread
                    store.updateMemory(
                        live.copy(importance = picked),
                        getString(R.string.memory_change_edited)
                    )
                    runOnUiThread { load() }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun importanceLabel(value: Int): String = getString(
        when (value.coerceIn(-2, 3)) {
            -2 -> R.string.mem_importance_minus_2
            -1 -> R.string.mem_importance_minus_1
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            else -> R.string.mem_importance_3
        }
    )

    /* ------------------------------ selection ------------------------------ */

    private fun checkedHolders(): List<MatchHolder> = matchHolders.filter { it.check.isChecked }

    private fun onSelectionChanged() {
        // Leaving a single selection cancels an in-progress inline edit.
        val editing = editingCardId
        if (editing != null && checkedHolders().singleOrNull()?.record?.memoryId != editing) {
            matchHolders.firstOrNull { it.record.memoryId == editing }?.let { holder ->
                holder.editSection.visibility = View.GONE
                (holder.editSection.parent as View).findViewById<MemoryTokenMeter>(R.id.memory_length_meter)
                    ?.bind(holder.record)
            }
            editingCardId = null
        }
        refreshResolutionButtons()
    }

    private fun refreshResolutionButtons() {
        val controlsEnabled = !applying && editingCardId == null
        pairNavigation.forEach { it.isEnabled = controlsEnabled }
        importanceControls.forEach { it.isEnabled = controlsEnabled }
        if (applying) return
        pendingPair?.setExternalEnabled(!applying && editingCardId == null)
        val count = checkedHolders().size
        val editing = editingCardId != null
        btnSupersede?.isEnabled = count >= 1 && !editing
        btnReplace?.isEnabled = count >= 1 && !editing
        // Save & Edit Old Memory: exactly one selection (or already editing it).
        btnEdit?.isEnabled = count == 1 || editing
        btnEdit?.text = getString(if (editing) R.string.mem_review_edit_save else R.string.mem_review_save_edit)
        selectHint?.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    /* ------------------------------ edit-old ------------------------------ */

    private fun onEditClick() {
        if (applying) return
        val editing = editingCardId
        if (editing == null) {
            // First tap: reveal the single selected card's inline editor. Titles
            // are retired (§3.1): only the content is editable.
            val holder = checkedHolders().singleOrNull() ?: return
            holder.editContent.setText(holder.record.content)
            holder.editSection.visibility = View.VISIBLE
            editingCardId = holder.record.memoryId
            refreshResolutionButtons()
        } else {
            // Second tap: apply the edit.
            applyResolution(ResolutionKind.EDIT_OLD)
        }
    }

    /* ------------------------------ apply ------------------------------ */

    private enum class ResolutionKind { EDIT_OLD, SUPERSEDE, REPLACE }

    private fun applyResolution(kind: ResolutionKind) {
        if (applying) return
        val checked = checkedHolders()
        if (checked.isEmpty()) return  // buttons are disabled here, belt-and-braces

        // For Edit Old, gather and validate the inline fields up front.
        var editedOld: MemoryRecord? = null
        if (kind == ResolutionKind.EDIT_OLD) {
            val holder = checked.singleOrNull() ?: return
            val content = holder.editContent.text?.toString().orEmpty()
            if (content.isBlank()) {
                Toast.makeText(this, R.string.mem_edit_required, Toast.LENGTH_SHORT).show()
                return
            }
            editedOld = holder.record.copy(content = content)
        }
        val checkedIds = checked.map { it.record.memoryId }
        // Every checked memory and the proposal must still be exactly what the
        // user saw; the store rechecks this inside its transaction.
        val expected = shownFingerprints.filterKeys { it == draftId || it in checkedIds }

        applying = true
        setButtonsEnabled(false)
        runOffThread {
            val store = MemoryStore.getInstance(this)
            // Pre-apply revalidation: the proposal must still be a pending draft
            // and every selected memory must still exist. Otherwise apply
            // nothing and keep the user on this screen (owner rule).
            val proposal = store.getMemory(draftId)
            if (proposal == null || proposal.status != "draft") {
                runOnUiThread { applying = false; toastAndReload(R.string.mem_review_gone, reload = false); finishGone() }
                return@runOffThread
            }
            if (checkedIds.any { store.getMemory(it) == null }) {
                runOnUiThread { applying = false; toastAndReload(R.string.mem_review_stale, reload = true) }
                return@runOffThread
            }

            val reader = EmbeddingTokenCounter.reader(this)
            val inputsToCheck = listOfNotNull(MemoryTokenBudget.Input.from(proposal), editedOld?.let {
                MemoryTokenBudget.Input.forEdit(store.getMemory(it.memoryId), it)
            })
            if (reader != null && inputsToCheck.any { !reader.measure(it).fits }) {
                runOnUiThread {
                    applying = false
                    setButtonsEnabled(true)
                    MemoryLengthDialog.show(this)
                }
                return@runOffThread
            }

            val result = when (kind) {
                ResolutionKind.EDIT_OLD -> store.resolveSaveAndEditOld(draftId, editedOld, expected)
                ResolutionKind.SUPERSEDE -> store.resolveSupersede(draftId, checkedIds, expected)
                ResolutionKind.REPLACE -> store.resolveReplace(draftId, checkedIds, expected)
            }
            when (result) {
                is MemoryStore.ResolutionResult.Applied -> {
                    // Re-index the now-active memories (the proposal, and an
                    // edited old memory that stayed active).
                    val lib = Librarian.getInstance(this)
                    result.reindexMemoryIds.forEach { lib.reindexMemory(it) }
                    runOnUiThread {
                        Toast.makeText(this, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                is MemoryStore.ResolutionResult.StaleProposal -> {
                    runOnUiThread { applying = false; toastAndReload(R.string.mem_review_gone, reload = false); finishGone() }
                }
                is MemoryStore.ResolutionResult.StaleSelection -> {
                    runOnUiThread { applying = false; toastAndReload(R.string.mem_review_stale, reload = true) }
                }
            }
        }
    }

    private fun finishGone() {
        if (!isFinishing) finish()
    }

    private fun toastAndReload(msgRes: Int, reload: Boolean) {
        Toast.makeText(this, msgRes, Toast.LENGTH_LONG).show()
        if (reload) {
            setButtonsEnabled(true)
            load()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        pairNavigation.forEach { it.isEnabled = enabled && editingCardId == null }
        importanceControls.forEach { it.isEnabled = enabled && editingCardId == null }
        pendingPair?.setExternalEnabled(enabled && editingCardId == null)
        btnEdit?.isEnabled = enabled
        btnSupersede?.isEnabled = enabled
        btnReplace?.isEnabled = enabled
        if (enabled) refreshResolutionButtons()
    }

    /* ------------------------------ helpers ------------------------------ */

    private fun iconForScope(scope: String?): Int = when (scope) {
        "real_life" -> R.drawable.ic_mem_person
        "global" -> R.drawable.ic_mem_global
        "companion" -> R.drawable.ic_mem_companion
        "project" -> R.drawable.ic_mem_draft
        "rp_character" -> R.drawable.ic_mem_theater
        else -> R.drawable.ic_mem_public
    }

    private fun formatTags(tags: List<String>): String =
        tags.joinToString("  ·  ") { t ->
            t.trim().split(" ").joinToString(" ") { w ->
                if (w.isEmpty()) w else w[0].uppercaseChar() + w.substring(1)
            }
        }

    private fun parseTags(tagsJson: String?): List<String> = try {
        if (tagsJson.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) { emptyList() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: org.teslasoft.assistant.preferences.backup.DatabaseDegradedException) {
                runOnUiThread {
                    if (!isFinishing) {
                        org.teslasoft.assistant.ui.DatabaseRecoveryFlows.showBlockedScreenDialog(this, e.type)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    applying = false
                    pendingPair?.onFailure()
                    if (!applying) setButtonsEnabled(true)
                    Toast.makeText(
                        this,
                        getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /* ------------------------------ theme + insets ------------------------------ */

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val amoled = isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true
        ThemeManager.getThemeManager().applyTheme(this, amoled)
        if (amoled) {
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

    private fun isDarkThemeEnabled(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ScreenChrome.fitSystemBarsAndKeyboard(
            findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0),
            actionBar,
            findViewById<android.view.View>(R.id.scroll)
        )
    }

}
