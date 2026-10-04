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
import org.teslasoft.assistant.preferences.Preferences
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
class ConversationSummaryActivity : FragmentActivity() {

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

        val compatible = preferences?.ensureSummarizerProjectionCompatibility() == true
        savedText = if (compatible) preferences?.getSummarizerSummary().orEmpty() else ""
        field?.setText(savedInstanceState?.getString(STATE_DRAFT) ?: savedText)
        field?.addTextChangedListener { refreshRevert() }

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
        outState.putString(STATE_DRAFT, field?.text?.toString().orEmpty())
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
            savedText = preferences?.getSummarizerSummary().orEmpty()
            field?.setText(savedText)
        }

        field?.isFocusable = !locked
        field?.isFocusableInTouchMode = !locked
        if (locked) field?.clearFocus()
        btnSave?.isEnabled = !locked
        btnSave?.alpha = if (locked) 0.38f else 1f
        refreshRevert()
    }

    /* ------------------------------ edit / save ------------------------------ */

    private fun isDirty(): Boolean = (field?.text?.toString() ?: savedText) != savedText

    private fun refreshRevert() {
        btnRevert?.visibility = if (isDirty() && !locked) View.VISIBLE else View.GONE
    }

    private fun save() {
        if (locked) return
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
        private const val STATE_DRAFT = "state_condensed_draft"

        fun createIntent(context: Context, chatId: String, mode: Mode): Intent =
            Intent(context, ConversationSummaryActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_MODE, mode.name)
    }
}
