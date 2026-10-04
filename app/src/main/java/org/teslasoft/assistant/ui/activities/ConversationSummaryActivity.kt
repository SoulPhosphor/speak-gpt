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

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.ConversationPreviewSheet
import org.teslasoft.assistant.ui.util.SummarySectionsPanel
import org.teslasoft.assistant.util.summarizer.SummarySectionStore
import org.teslasoft.assistant.util.summarizer.SummarySections
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.ui.util.HintConfirmDialog
import org.teslasoft.assistant.ui.util.SaveIconFlash
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.util.summarizer.SummarizerController
import org.teslasoft.assistant.util.summarizer.SummarizerControllerRegistry
import org.teslasoft.assistant.util.summarizer.SummarizerOperationMessages
import org.teslasoft.assistant.util.summarizer.SummarizerReviewGate

/**
 * Summary / Compaction review (owner ruling, Oct 3 2026), opened from the
 * chat's top bar once the chat has a saved summary or a completed
 * compaction. One screen serves both, in summarizer or compaction wording.
 * It shows the condensed text the AI receives in place of the condensed
 * messages, lets the user edit and save it, and switches the chat between
 * its condensed and full form without deleting the condensed text. While
 * the summarizer or compactor runs for this chat, the text is read only.
 */
class ConversationSummaryActivity : FragmentActivity(), ConversationPreviewSheet.Host {

    /** The wording set: summarizer and summaries, or compactor and compaction. */
    enum class Mode(
        val titleRes: Int,
        val introRes: Int,
        val turnOffDescRes: Int,
        val turnOnDescRes: Int,
        val turnOffTitleRes: Int,
        val turnOffBodyRes: Int,
        val turnOffHideRes: Int,
        val turnOnTitleRes: Int,
        val turnOnBodyRes: Int,
        val turnOnHideRes: Int
    ) {
        SUMMARY(
            R.string.title_conversation_summary,
            R.string.summary_review_intro,
            R.string.summary_unsummarize_desc,
            R.string.summary_resummarize_desc,
            R.string.summary_unsummarize_title,
            R.string.summary_unsummarize_body,
            R.string.compaction_uncompact_hide,
            R.string.summary_resummarize_title,
            R.string.summary_resummarize_body,
            R.string.compaction_recompact_hide
        ),
        COMPACTION(
            R.string.title_compaction_summary,
            R.string.compaction_summary_intro,
            R.string.compaction_uncompact_desc,
            R.string.compaction_recompact_desc,
            R.string.compaction_uncompact_title,
            R.string.compaction_uncompact_body,
            R.string.compaction_uncompact_hide,
            R.string.compaction_recompact_title,
            R.string.compaction_recompact_body,
            R.string.compaction_recompact_hide
        )
    }

    private var mode = Mode.COMPACTION

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var btnToggle: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var rowStatus: View? = null
    private var spinner: View? = null
    private var textStatus: TextView? = null
    private var textReadOnly: TextView? = null
    private var field: TextInputEditText? = null
    private var btnRevert: MaterialButton? = null
    private var sectionsPanel: SummarySectionsPanel? = null

    /** The text as last saved — the Revert target and unsaved-changes baseline. */
    private var savedText = ""
    /** True while the summarizer or compactor runs for this chat. */
    private var locked = false
    private var lastRunKind = SummarizerController.OperationKind.COMPACTING

    private val operationListener = SummarizerControllerRegistry.AppListener { changedChatId, state ->
        if (changedChatId != chatId) return@AppListener
        runOnUiThread { renderOperation(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_conversation_summary)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        if (chatId.isBlank()) {
            finish()
            return
        }
        preferences = Preferences.getPreferences(this, chatId)
        mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> Mode.entries.firstOrNull { it.name == name } }
            ?: Mode.COMPACTION
        // No summary update starts while this screen is open; the chat runs
        // any held-back update when it resumes.
        SummarizerReviewGate.open(chatId, SummarizerReviewGate.CONVERSATION_SUMMARY)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnToggle = findViewById(R.id.btn_toggle_condensed)
        btnSave = findViewById(R.id.btn_save)
        rowStatus = findViewById(R.id.row_condensed_status)
        spinner = findViewById(R.id.spinner_condensed)
        textStatus = findViewById(R.id.text_condensed_status)
        textReadOnly = findViewById(R.id.text_condensed_read_only)
        field = findViewById(R.id.field_condensed_text)
        btnRevert = findViewById(R.id.btn_revert_condensed)
        findViewById<TextView>(R.id.activity_title)?.setText(mode.titleRes)
        findViewById<TextView>(R.id.text_condensed_intro)?.setText(mode.introRes)
        ScreenChrome.apply(this, actionBar, btnBack, btnToggle, btnSave)

        if (mode == Mode.SUMMARY) {
            // The Summarizer's sections replace the single text box.
            field?.visibility = View.GONE
            btnRevert?.visibility = View.GONE
            val container = findViewById<android.widget.LinearLayout>(R.id.summary_sections)
            container.visibility = View.VISIBLE
            sectionsPanel = SummarySectionsPanel(
                this,
                container,
                onEdited = {},
                onHeaderTapped = { sectionId -> openConversationPreview(sectionId) },
                onRewrite = { sectionId -> confirmRewrite(sectionId) }
            )
            bindSections(savedInstanceState?.getString(STATE_DRAFT)?.let { decodeDrafts(it) }.orEmpty())
            if (savedInstanceState == null) {
                intent.getStringExtra(EXTRA_SECTION_ID)?.let { target ->
                    sectionsPanel?.target(target, findViewById(R.id.scroll))
                }
            }
        } else {
            val compatible = preferences?.ensureSummarizerProjectionCompatibility() == true
            savedText = if (compatible) preferences?.getSummarizerSummary().orEmpty() else ""
            field?.setText(savedInstanceState?.getString(STATE_DRAFT) ?: savedText)
            field?.addTextChangedListener { refreshRevert() }
        }

        btnBack?.setOnClickListener { attemptLeave() }
        btnSave?.setOnClickListener { save() }
        btnRevert?.setOnClickListener { field?.setText(savedText) }
        btnToggle?.setOnClickListener { onToggleCondensed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptLeave()
            }
        })

        refreshToggle()
        renderOperation(SummarizerControllerRegistry.operationState(chatId))
        SummarizerControllerRegistry.addAppListener(operationListener)
    }

    /** Closing here, not only in onDestroy, lets the chat screen's onResume
     *  (which runs before this screen is destroyed) see the gate closed. */
    override fun onPause() {
        super.onPause()
        if (isFinishing) SummarizerReviewGate.close(chatId, SummarizerReviewGate.CONVERSATION_SUMMARY)
    }

    override fun onDestroy() {
        if (isFinishing) SummarizerReviewGate.close(chatId, SummarizerReviewGate.CONVERSATION_SUMMARY)
        SummarizerControllerRegistry.removeAppListener(operationListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val panel = sectionsPanel
        outState.putString(
            STATE_DRAFT,
            if (panel != null) encodeDrafts(panel.drafts()) else field?.text?.toString().orEmpty()
        )
    }

    /* ------------------------------ run status ------------------------------ */

    /** While the summarizer or compactor runs for this chat the text is read
     *  only; when the run ends the newest saved text is loaded for editing. */
    private fun renderOperation(state: SummarizerController.OperationState) {
        val running = state as? SummarizerController.OperationState.Running
        val wasLocked = locked
        locked = running != null

        when {
            running != null -> {
                lastRunKind = running.kind
                rowStatus?.visibility = View.VISIBLE
                spinner?.visibility = View.VISIBLE
                textStatus?.setText(SummarizerOperationMessages.inProgressRes(running.kind))
                textReadOnly?.setText(SummarizerOperationMessages.readOnlyRes(running.kind))
                textReadOnly?.visibility = View.VISIBLE
            }
            state is SummarizerController.OperationState.Succeeded -> {
                rowStatus?.visibility = View.VISIBLE
                spinner?.visibility = View.GONE
                textStatus?.setText(SummarizerOperationMessages.successRes(state.kind))
                textReadOnly?.visibility = View.GONE
            }
            state is SummarizerController.OperationState.Idle && wasLocked -> {
                rowStatus?.visibility = View.VISIBLE
                spinner?.visibility = View.GONE
                textStatus?.setText(SummarizerOperationMessages.successRes(lastRunKind))
                textReadOnly?.visibility = View.GONE
            }
            state is SummarizerController.OperationState.Idle -> {
                // Keep a success line already showing; nothing else to say.
                if (spinner?.visibility == View.VISIBLE) rowStatus?.visibility = View.GONE
                textReadOnly?.visibility = View.GONE
            }
            else -> {
                // Failed or cancelled: the error belongs to Summarizer Errors.
                rowStatus?.visibility = View.GONE
                textReadOnly?.visibility = View.GONE
            }
        }

        if (wasLocked && !locked) {
            if (sectionsPanel != null) {
                bindSections()
            } else {
                savedText = preferences?.getSummarizerSummary().orEmpty()
                field?.setText(savedText)
            }
        }
        sectionsPanel?.setLocked(locked)

        field?.isFocusable = !locked
        field?.isFocusableInTouchMode = !locked
        if (locked) field?.clearFocus()
        btnSave?.isEnabled = !locked
        btnSave?.alpha = if (locked) 0.38f else 1f
        refreshRevert()
    }

    /* ------------------------------ edit / save ------------------------------ */

    private fun isDirty(): Boolean = sectionsPanel?.isDirty()
        ?: ((field?.text?.toString() ?: savedText) != savedText)

    private fun refreshRevert() {
        if (sectionsPanel != null) return
        btnRevert?.visibility = if (isDirty() && !locked) View.VISIBLE else View.GONE
    }

    /* ------------------------------ summary sections ------------------------------ */

    /** The chat's stored messages as the panel needs them: identity, role,
     *  and time (for the date/time headers). */
    private fun storedMessages(): List<SummarySections.SourceMessage> =
        ChatPreferences.getChatPreferences().getChatById(this, chatId).map {
            SummarySections.SourceMessage(
                MessageIdentity.idOf(it),
                it["isBot"] == true,
                "",
                it[ChatAdapter.KEY_MESSAGE_TIME]?.toString()?.toLongOrNull()
            )
        }

    private fun storedSections(): List<SummarySections.Section> =
        SummarySections.fromJson(preferences?.getSummarySections().orEmpty())

    private fun bindSections(drafts: Map<String, String> = emptyMap()) {
        sectionsPanel?.bind(storedSections(), storedMessages(), drafts)
        sectionsPanel?.setLocked(locked)
    }

    /** Saves each changed section by its stable ID: the user's text becomes
     *  that section's summary and is never silently replaced. */
    private fun saveSections(panel: SummarySectionsPanel): Boolean {
        val prefs = preferences ?: return false
        val changes = panel.changedTexts()
        if (changes.isEmpty()) return true
        val updated = storedSections().map { section ->
            changes[section.id]?.let { section.copy(text = it, edited = true, needsUpdate = false, kept = false) } ?: section
        }
        return SummarySectionStore.save(prefs, updated)
    }

    private fun encodeDrafts(drafts: Map<String, String>): String =
        org.json.JSONObject(drafts as Map<*, *>).toString()

    private fun decodeDrafts(json: String): Map<String, String> = try {
        val o = org.json.JSONObject(json)
        o.keys().asSequence().associateWith { o.optString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    /** From the preview: go to that exact message in the chat, where editing
     *  and deleting stay. Unsaved summary edits are confirmed first. */
    override fun onPreviewMessageChosen(messageId: String) {
        val go = {
            setResult(RESULT_OK, Intent().putExtra(RESULT_MESSAGE_ID, messageId))
            finish()
        }
        if (isDirty() && !locked) DiscardChangesDialog.show(this) { go() } else go()
    }

    /** A kept section (the user's edit or an older summary) whose messages
     *  changed: on confirmation it is queued to be rewritten from its current
     *  messages, which happens once this screen closes. */
    private fun confirmRewrite(sectionId: String) {
        val actions = layoutInflater.inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.summary_section_rewrite_title)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener {
                dialog.dismiss()
                val prefs = preferences ?: return@setOnClickListener
                val updated = storedSections().map {
                    if (it.id == sectionId) it.copy(edited = false, legacy = false, kept = false, needsUpdate = true) else it
                }
                if (SummarySectionStore.save(prefs, updated)) {
                    prefs.setSummarizerCatchUpPending(true)
                    bindSections(sectionsPanel?.drafts().orEmpty().filterKeys { it != sectionId })
                }
            }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    private fun openConversationPreview(sectionId: String) {
        ConversationPreviewSheet.newInstance(chatId, sectionId)
            .show(supportFragmentManager, ConversationPreviewSheet.TAG)
    }

    private fun save() {
        if (locked) return
        sectionsPanel?.let { panel ->
            if (!saveSections(panel)) {
                Toast.makeText(this, R.string.label_sorry_action_failed, Toast.LENGTH_LONG).show()
                return
            }
            panel.markSaved()
            btnSave?.let { SaveIconFlash.flash(it) }
            Toast.makeText(this, R.string.companion_editor_saved_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val text = field?.text?.toString().orEmpty()
        if (preferences?.commitSummarizerSummaryEdit(text) != true) {
            Toast.makeText(this, R.string.label_sorry_action_failed, Toast.LENGTH_LONG).show()
            return
        }
        savedText = text
        refreshRevert()
        btnSave?.let { SaveIconFlash.flash(it) }
        Toast.makeText(this, R.string.companion_editor_saved_toast, Toast.LENGTH_SHORT).show()
    }

    private fun attemptLeave() {
        if (isDirty() && !locked) {
            DiscardChangesDialog.show(this) { finish() }
        } else {
            finish()
        }
    }

    /* ------------------------------ uncompact / recompact ------------------------------ */

    private fun usingCompacted(): Boolean =
        preferences?.getUseSummarizedConversationProjection() != false

    /** The icon names the action: Uncompact while the compacted form is in
     *  use, Recompact while the full conversation is being sent. */
    private fun refreshToggle() {
        val compacted = usingCompacted()
        btnToggle?.setImageResource(if (compacted) R.drawable.ic_docs_add_on else R.drawable.ic_topic)
        val desc = getString(if (compacted) mode.turnOffDescRes else mode.turnOnDescRes)
        btnToggle?.contentDescription = desc
        btnToggle?.tooltipText = desc
    }

    private fun onToggleCondensed() {
        val prefs = preferences ?: return
        val summary = mode == Mode.SUMMARY
        if (usingCompacted()) {
            val hidden = if (summary) prefs.getHideUnsummarizeHint() else prefs.getHideUncompactHint()
            if (hidden) {
                uncompact()
            } else {
                HintConfirmDialog.show(this, mode.turnOffTitleRes, mode.turnOffBodyRes, mode.turnOffHideRes) { hide ->
                    if (summary) prefs.setHideUnsummarizeHint(hide) else prefs.setHideUncompactHint(hide)
                    uncompact()
                }
            }
        } else {
            val hidden = if (summary) prefs.getHideResummarizeHint() else prefs.getHideRecompactHint()
            if (hidden) {
                recompact()
            } else {
                HintConfirmDialog.show(this, mode.turnOnTitleRes, mode.turnOnBodyRes, mode.turnOnHideRes) { hide ->
                    if (summary) prefs.setHideResummarizeHint(hide) else prefs.setHideRecompactHint(hide)
                    recompact()
                }
            }
        }
    }

    /** Sends the full conversation again. The compacted text, bookmark, and
     *  marker are kept; automatic summarizing pauses and catches up later,
     *  matching the summary view's Send Entire Chat. */
    private fun uncompact() {
        val prefs = preferences ?: return
        prefs.setUseSummarizedConversationProjection(false)
        prefs.setSummarizerCatchUpPending(true)
        val state = SummarizerControllerRegistry.operationState(chatId)
        if (state is SummarizerController.OperationState.Running &&
            state.kind == SummarizerController.OperationKind.SUMMARIZING
        ) {
            SummarizerControllerRegistry.cancel(chatId)
        }
        refreshToggle()
    }

    private fun recompact() {
        preferences?.setUseSummarizedConversationProjection(true)
        refreshToggle()
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
            val density = resources.displayMetrics.density
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom +
                    (24 * density).toInt()
            )
        } catch (_: Exception) { /* unused */ }
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SECTION_ID = "sectionId"

        /** Result extra: the chat message the user chose in the preview. */
        const val RESULT_MESSAGE_ID = "goToMessageId"
        private const val STATE_DRAFT = "state_condensed_draft"

        /** [sectionId], when given, is scrolled to, flagged, and highlighted. */
        fun createIntent(context: Context, chatId: String, mode: Mode, sectionId: String? = null): Intent =
            Intent(context, ConversationSummaryActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_SECTION_ID, sectionId)
    }
}
