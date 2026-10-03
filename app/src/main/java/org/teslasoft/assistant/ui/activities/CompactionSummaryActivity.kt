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

/**
 * Compaction Summary (owner ruling, Oct 3 2026), opened from the chat's top
 * bar once the chat has been compacted at least once. Shows the compacted
 * text the AI receives in place of the compacted messages, lets the user
 * edit and save it, and switches the chat between its compacted and full
 * (uncompacted) form without deleting the compacted text. While a compaction
 * of this chat runs, the text is read only.
 */
class CompactionSummaryActivity : FragmentActivity() {

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
    private var compacting = false

    private val operationListener = SummarizerControllerRegistry.AppListener { changedChatId, state ->
        if (changedChatId != chatId) return@AppListener
        runOnUiThread { renderOperation(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_compaction_summary)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        if (chatId.isBlank()) {
            finish()
            return
        }
        preferences = Preferences.getPreferences(this, chatId)

        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        btnToggle = findViewById(R.id.btn_toggle_compaction)
        btnSave = findViewById(R.id.btn_save)
        rowStatus = findViewById(R.id.row_compaction_status)
        spinner = findViewById(R.id.spinner_compaction)
        textStatus = findViewById(R.id.text_compaction_status)
        textReadOnly = findViewById(R.id.text_compaction_read_only)
        field = findViewById(R.id.field_compaction_text)
        btnRevert = findViewById(R.id.btn_revert_compaction)
        ScreenChrome.apply(this, actionBar, btnBack, btnToggle, btnSave)

        val compatible = preferences?.ensureSummarizerProjectionCompatibility() == true
        savedText = if (compatible) preferences?.getSummarizerSummary().orEmpty() else ""
        field?.setText(savedInstanceState?.getString(STATE_DRAFT) ?: savedText)
        field?.addTextChangedListener { refreshRevert() }

        btnBack?.setOnClickListener { attemptLeave() }
        btnSave?.setOnClickListener { save() }
        btnRevert?.setOnClickListener { field?.setText(savedText) }
        btnToggle?.setOnClickListener { onToggleCompaction() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptLeave()
            }
        })

        refreshToggle()
        renderOperation(SummarizerControllerRegistry.operationState(chatId))
        SummarizerControllerRegistry.addAppListener(operationListener)
    }

    override fun onDestroy() {
        SummarizerControllerRegistry.removeAppListener(operationListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DRAFT, field?.text?.toString().orEmpty())
    }

    /* ------------------------------ compaction status ------------------------------ */

    private fun renderOperation(state: SummarizerController.OperationState) {
        val compactingNow = state is SummarizerController.OperationState.Running &&
            state.kind == SummarizerController.OperationKind.COMPACTING
        val succeeded = state is SummarizerController.OperationState.Succeeded &&
            state.kind == SummarizerController.OperationKind.COMPACTING
        val wasCompacting = compacting
        compacting = compactingNow

        when {
            compactingNow -> {
                rowStatus?.visibility = View.VISIBLE
                spinner?.visibility = View.VISIBLE
                textStatus?.setText(R.string.compaction_summary_in_progress)
                textReadOnly?.visibility = View.VISIBLE
            }
            succeeded || (wasCompacting && state !is SummarizerController.OperationState.Failed &&
                state !is SummarizerController.OperationState.Cancelled) -> {
                rowStatus?.visibility = View.VISIBLE
                spinner?.visibility = View.GONE
                textStatus?.setText(R.string.compaction_summary_success)
                textReadOnly?.visibility = View.GONE
            }
            else -> {
                // Keep a success line that is already showing; otherwise no
                // status line when nothing is running.
                if (spinner?.visibility == View.VISIBLE) rowStatus?.visibility = View.GONE
                textReadOnly?.visibility = View.GONE
            }
        }

        // The compacted text is solid again: show what the compactor saved.
        if (wasCompacting && !compactingNow) {
            savedText = preferences?.getSummarizerSummary().orEmpty()
            field?.setText(savedText)
        }

        field?.isFocusable = !compactingNow
        field?.isFocusableInTouchMode = !compactingNow
        if (compactingNow) field?.clearFocus()
        btnSave?.isEnabled = !compactingNow
        btnSave?.alpha = if (compactingNow) 0.38f else 1f
        refreshRevert()
    }

    /* ------------------------------ edit / save ------------------------------ */

    private fun isDirty(): Boolean = (field?.text?.toString() ?: savedText) != savedText

    private fun refreshRevert() {
        btnRevert?.visibility = if (isDirty() && !compacting) View.VISIBLE else View.GONE
    }

    private fun save() {
        if (compacting) return
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
        if (isDirty() && !compacting) {
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
        val desc = getString(if (compacted) R.string.compaction_uncompact_desc else R.string.compaction_recompact_desc)
        btnToggle?.contentDescription = desc
        btnToggle?.tooltipText = desc
    }

    private fun onToggleCompaction() {
        val prefs = preferences ?: return
        if (usingCompacted()) {
            if (prefs.getHideUncompactHint()) {
                uncompact()
            } else {
                HintConfirmDialog.show(
                    this,
                    R.string.compaction_uncompact_title,
                    R.string.compaction_uncompact_body,
                    R.string.compaction_uncompact_hide
                ) { hide ->
                    prefs.setHideUncompactHint(hide)
                    uncompact()
                }
            }
        } else {
            if (prefs.getHideRecompactHint()) {
                recompact()
            } else {
                HintConfirmDialog.show(
                    this,
                    R.string.compaction_recompact_title,
                    R.string.compaction_recompact_body,
                    R.string.compaction_recompact_hide
                ) { hide ->
                    prefs.setHideRecompactHint(hide)
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
        private const val STATE_DRAFT = "state_compaction_draft"

        fun createIntent(context: Context, chatId: String): Intent =
            Intent(context, CompactionSummaryActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId)
    }
}
