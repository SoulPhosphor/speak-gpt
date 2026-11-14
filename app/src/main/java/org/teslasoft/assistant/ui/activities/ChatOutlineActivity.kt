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
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.chat.ChatMarkdownRenderer
import org.teslasoft.assistant.ui.util.DiscardChangesDialog
import org.teslasoft.assistant.ui.util.PromptTabBackground
import org.teslasoft.assistant.ui.util.SaveIconFlash
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.util.summarizer.ChatOutlineRunner
import org.teslasoft.assistant.util.summarizer.ChatOutlines
import org.teslasoft.assistant.util.summarizer.SummarizerController
import org.teslasoft.assistant.util.summarizer.SummarizerController.OperationState
import org.teslasoft.assistant.util.summarizer.SummarizerController.OutlineMode
import org.teslasoft.assistant.util.summarizer.SummarizerControllerRegistry
import org.teslasoft.assistant.util.summarizer.SummarizerErrorCategory

/**
 * Chat Outline (owner design, Nov 13 2026): the chat's outlines, one tab
 * each, shown as formatted markdown. Edit on a tab's menu shows its raw
 * markdown for editing; Save keeps the edit and the Tags line. Generate New
 * Outline adds a tab, Replace All Outlines With New replaces every tab with
 * one, Redo This Outline rewrites one tab, Delete removes one tab, and the
 * header trash can deletes every outline and the tags. Runs are started by
 * the chat screen underneath (ChatOutlineRunner), keep going in the
 * background, and report their percentage in the shared progress pop-up.
 */
class ChatOutlineActivity : FragmentActivity() {

    private var chatId = ""
    private var preferences: Preferences? = null

    private var btnSave: ImageButton? = null
    private var btnDeleteAll: ImageButton? = null
    private var btnGenerateNew: MaterialButton? = null
    private var btnReplaceAll: MaterialButton? = null
    private var rowStatus: View? = null
    private var fieldTags: EditText? = null
    private var tabs: ChipGroup? = null
    private var frame: View? = null
    private var btnMenu: ImageButton? = null
    private var textOutline: TextView? = null
    private var fieldOutline: TextInputEditText? = null

    private var outlines: List<String> = emptyList()
    private var shownIndex = 0
    /** The tab whose raw markdown is open for editing, or -1. */
    private var editingIndex = -1
    private var savedTags = ""
    private var running = false

    private var progressDialog: AlertDialog? = null
    private val dotsHandler = Handler(Looper.getMainLooper())
    private var dotsStep = 0
    private val dotsTick = object : Runnable {
        override fun run() {
            val dots = progressDialog?.findViewById<TextView>(R.id.compaction_dots) ?: return
            dotsStep = (dotsStep + 1) % 4
            dots.text = ".".repeat(dotsStep)
            dotsHandler.postDelayed(this, resources.getInteger(R.integer.progress_dialog_dots_step_ms).toLong())
        }
    }

    private val operationListener = SummarizerControllerRegistry.AppListener { changedChatId, state ->
        if (changedChatId != chatId) return@AppListener
        runOnUiThread { renderOperation(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_chat_outline)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        if (chatId.isBlank()) {
            finish()
            return
        }
        preferences = Preferences.getPreferences(this, chatId)

        val actionBar = findViewById<View>(R.id.action_bar)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        btnDeleteAll = findViewById(R.id.btn_delete_outlines)
        btnSave = findViewById(R.id.btn_save)
        btnGenerateNew = findViewById(R.id.btn_generate_new_outline)
        btnReplaceAll = findViewById(R.id.btn_replace_all_outlines)
        rowStatus = findViewById(R.id.row_outline_status)
        fieldTags = findViewById(R.id.field_outline_tags)
        fieldTags?.let { org.teslasoft.assistant.ui.util.BoundedFieldScroll.attach(it) }
        tabs = findViewById(R.id.outline_tabs)
        frame = findViewById(R.id.outline_frame)
        btnMenu = findViewById(R.id.btn_outline_menu)
        textOutline = findViewById(R.id.text_outline)
        fieldOutline = findViewById(R.id.field_outline)
        // The screen restores its own drafts below.
        fieldTags?.isSaveEnabled = false
        fieldOutline?.isSaveEnabled = false

        ScreenChrome.apply(this, actionBar, btnBack, btnDeleteAll, btnSave)
        ScreenChrome.fitSystemBarsAndKeyboard(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))

        findViewById<TextView>(R.id.text_chat_title)?.text =
            intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty()
                .ifBlank { getString(R.string.label_untitled_chat) }

        savedTags = preferences?.getChatOutlineTags().orEmpty()
        outlines = ChatOutlines.fromJson(preferences?.getChatOutlines())
        shownIndex = savedInstanceState?.getInt(STATE_SHOWN, 0) ?: 0
        editingIndex = savedInstanceState?.getInt(STATE_EDITING, -1) ?: -1
        fieldTags?.setText(savedInstanceState?.getString(STATE_TAGS) ?: savedTags)
        clampIndexes()
        if (editingIndex >= 0) {
            fieldOutline?.setText(savedInstanceState?.getString(STATE_EDIT_TEXT) ?: outlines[editingIndex])
        }

        btnBack?.setOnClickListener { attemptLeave() }
        btnSave?.setOnClickListener { save() }
        btnDeleteAll?.setOnClickListener { confirmDeleteAll() }
        btnGenerateNew?.setOnClickListener { startRun(OutlineMode.GENERATE_NEW) }
        btnReplaceAll?.setOnClickListener { confirmReplaceAll() }
        btnMenu?.setOnClickListener { showTabMenu(it) }
        fieldTags?.addTextChangedListener { refreshSave() }
        fieldOutline?.addTextChangedListener { refreshSave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptLeave()
            }
        })

        render()
        renderOperation(SummarizerControllerRegistry.operationState(chatId))
        SummarizerControllerRegistry.addAppListener(operationListener)
    }

    override fun onDestroy() {
        SummarizerControllerRegistry.removeAppListener(operationListener)
        dotsHandler.removeCallbacks(dotsTick)
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SHOWN, shownIndex)
        outState.putInt(STATE_EDITING, editingIndex)
        outState.putString(STATE_TAGS, fieldTags?.text?.toString().orEmpty())
        if (editingIndex >= 0) outState.putString(STATE_EDIT_TEXT, fieldOutline?.text?.toString().orEmpty())
    }

    /* ------------------------------ rendering ------------------------------ */

    private fun clampIndexes() {
        if (shownIndex !in outlines.indices) shownIndex = (outlines.size - 1).coerceAtLeast(0)
        if (editingIndex !in outlines.indices) editingIndex = -1
    }

    private fun render() {
        val hasOutlines = outlines.isNotEmpty()
        btnReplaceAll?.visibility = if (hasOutlines) View.VISIBLE else View.GONE
        tabs?.visibility = if (hasOutlines) View.VISIBLE else View.GONE
        frame?.visibility = if (hasOutlines) View.VISIBLE else View.GONE
        btnGenerateNew?.isEnabled = !running
        btnReplaceAll?.isEnabled = !running
        btnDeleteAll?.isEnabled = !running && (hasOutlines || savedTags.isNotEmpty())
        btnMenu?.visibility = if (running) View.GONE else View.VISIBLE
        renderTabs()
        renderShownOutline()
        refreshSave()
    }

    private fun renderTabs() {
        val row = tabs ?: return
        row.removeAllViews()
        val outlineColor = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOutline)
        val surface = MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val strokeWidth = resources.getDimension(R.dimen.prompt_tab_stroke_width)
        val slant = resources.getDimension(R.dimen.prompt_tab_slant_width)
        outlines.indices.forEach { index ->
            val shown = index == shownIndex
            val tab = TextView(
                this, null, 0,
                if (shown) R.style.Widget_App_PromptTab_Active else R.style.Widget_App_PromptTab
            )
            tab.text = tabLabel(index)
            tab.background = PromptTabBackground(
                if (shown) surface else Color.TRANSPARENT,
                outlineColor,
                strokeWidth,
                slant
            )
            tab.setOnClickListener { showTab(index) }
            row.addView(tab)
        }
    }

    private fun tabLabel(index: Int): String =
        ChatOutlines.numberedLabel(index)?.let { getString(R.string.chat_outline_tab_numbered, it) }
            ?: getString(R.string.chat_outline_tab_first)

    private fun renderShownOutline() {
        val text = textOutline ?: return
        val field = fieldOutline ?: return
        if (outlines.isEmpty()) return
        if (editingIndex == shownIndex) {
            text.visibility = View.GONE
            field.visibility = View.VISIBLE
        } else {
            field.visibility = View.GONE
            text.visibility = View.VISIBLE
            ChatMarkdownRenderer.builder(this, text.textSize).build()
                .setMarkdown(text, outlines[shownIndex])
        }
    }

    private fun showTab(index: Int) {
        if (index == shownIndex || index !in outlines.indices) return
        shownIndex = index
        render()
    }

    /* ------------------------------ tab menu ------------------------------- */

    private fun showTabMenu(anchor: View) {
        if (running || shownIndex !in outlines.indices) return
        val index = shownIndex
        val popup = PopupMenu(this, anchor)
        if (editingIndex != index) popup.menu.add(0, MENU_EDIT, 0, R.string.chat_outline_menu_edit)
        popup.menu.add(0, MENU_REDO, 1, R.string.chat_outline_menu_redo)
        if (outlines.size > 1) popup.menu.add(0, MENU_DELETE, 2, R.string.chat_outline_menu_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> startEditing(index)
                MENU_REDO -> withSavedEdits { startRun(OutlineMode.REDO, index) }
                MENU_DELETE -> confirmDeleteOne(index)
            }
            true
        }
        popup.show()
    }

    private fun startEditing(index: Int) {
        if (editingIndex >= 0 && editingIndex != index && editDirty()) {
            DiscardChangesDialog.show(this) { openEditor(index) }
        } else {
            openEditor(index)
        }
    }

    private fun openEditor(index: Int) {
        editingIndex = index
        fieldOutline?.setText(outlines[index])
        render()
        fieldOutline?.requestFocus()
    }

    /* ------------------------------ saving --------------------------------- */

    private fun tagsDirty(): Boolean = fieldTags?.text?.toString().orEmpty() != savedTags

    private fun editDirty(): Boolean =
        editingIndex in outlines.indices &&
            fieldOutline?.text?.toString().orEmpty() != outlines[editingIndex]

    private fun isDirty(): Boolean = tagsDirty() || editingIndex >= 0

    private fun refreshSave() {
        btnSave?.isEnabled = !running && (tagsDirty() || editingIndex >= 0)
    }

    /** Save: keeps the Tags line and any open edit, then shows the edited tab formatted again. */
    private fun save() {
        val prefs = preferences ?: return
        if (running) return
        val tags = fieldTags?.text?.toString().orEmpty().trim()
        if (editingIndex in outlines.indices) {
            val current = ChatOutlines.fromJson(prefs.getChatOutlines())
            val updated = ChatOutlines.afterReplace(current, editingIndex, fieldOutline?.text?.toString().orEmpty())
            prefs.setChatOutlines(ChatOutlines.toJson(updated))
            outlines = updated
        }
        prefs.setChatOutlineTags(tags)
        savedTags = tags
        fieldTags?.setText(tags)
        editingIndex = -1
        clampIndexes()
        render()
        btnSave?.let { SaveIconFlash.flash(it) }
    }

    /** Runs [action] once unsaved changes are saved or the user chose to discard them. */
    private fun withSavedEdits(action: () -> Unit) {
        if (!isDirty()) {
            action()
            return
        }
        DiscardChangesDialog.show(this) {
            discardEdits()
            action()
        }
    }

    private fun discardEdits() {
        editingIndex = -1
        fieldTags?.setText(savedTags)
        render()
    }

    private fun attemptLeave() {
        if (isDirty() && (tagsDirty() || editDirty())) {
            DiscardChangesDialog.show(this) { finish() }
        } else {
            finish()
        }
    }

    /* --------------------------- confirmations ----------------------------- */

    private fun confirm(titleRes: Int, bodyRes: Int, onOkay: () -> Unit) {
        val actions = LayoutInflater.from(this).inflate(R.layout.dialog_two_actions_cancel_first, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                dialog.dismiss()
                onOkay()
            }
        }
        dialog.show()
    }

    private fun confirmReplaceAll() {
        if (running) return
        confirm(R.string.chat_outline_replace_all_title, R.string.chat_outline_replace_all_body) {
            withSavedEdits { startRun(OutlineMode.REPLACE_ALL) }
        }
    }

    private fun confirmDeleteAll() {
        if (running) return
        confirm(R.string.chat_outline_delete_all_title, R.string.chat_outline_delete_all_body) {
            val prefs = preferences ?: return@confirm
            prefs.setChatOutlines("")
            prefs.setChatOutlineTags("")
            outlines = emptyList()
            savedTags = ""
            fieldTags?.setText("")
            editingIndex = -1
            shownIndex = 0
            render()
        }
    }

    private fun confirmDeleteOne(index: Int) {
        confirm(R.string.chat_outline_delete_one_title, R.string.chat_outline_delete_one_body) {
            val prefs = preferences ?: return@confirm
            val current = ChatOutlines.fromJson(prefs.getChatOutlines())
            val updated = ChatOutlines.afterDelete(current, index)
            prefs.setChatOutlines(ChatOutlines.toJson(updated))
            outlines = updated
            when {
                editingIndex == index -> editingIndex = -1
                editingIndex > index -> editingIndex -= 1
            }
            if (shownIndex >= index) shownIndex = (shownIndex - 1).coerceAtLeast(0)
            clampIndexes()
            render()
        }
    }

    /* ------------------------------- runs ---------------------------------- */

    private fun startRun(mode: OutlineMode, redoIndex: Int = -1) {
        if (running) return
        if (!ChatOutlineRunner.hasStarter(chatId) && SummarizerController.isConfigured(this)) {
            // Android rebuilt the app and the chat screen underneath is not
            // ready yet: hand it the run; it starts it and reopens this screen.
            // Closing this screen first asks about unsaved changes.
            withSavedEdits {
                ChatOutlineRunner.request(chatId, mode, redoIndex)
                finish()
            }
            return
        }
        if (ChatOutlineRunner.start(chatId, mode, redoIndex)) return
        // Without a Summary Model nothing can run; say so the way a failed run would.
        if (!SummarizerController.isConfigured(this)) {
            showFailure(
                OperationState.Failed(
                    SummarizerController.OperationKind.OUTLINING,
                    "",
                    SummarizerErrorCategory.MODEL_MISSING
                )
            )
        }
    }

    private fun renderOperation(state: OperationState) {
        if (SummarizerController.kindOf(state) != SummarizerController.OperationKind.OUTLINING) {
            // Another summarizer job for this chat also blocks a new run.
            val busy = state is OperationState.Running
            if (busy != running) {
                running = busy
                render()
            }
            return
        }
        when (state) {
            is OperationState.Running -> {
                if (!running) {
                    running = true
                    render()
                }
                rowStatus?.visibility = View.VISIBLE
                showProgress(state.percent)
            }
            is OperationState.Succeeded -> {
                finishRun()
                reloadOutlines(showNewest = true)
                showComplete()
            }
            is OperationState.Failed -> {
                val watching = progressDialog?.isShowing == true
                finishRun()
                closeProgress()
                if (watching) showFailure(state)
            }
            else -> {
                finishRun()
                closeProgress()
            }
        }
    }

    private fun finishRun() {
        rowStatus?.visibility = View.GONE
        if (running) {
            running = false
            render()
        }
    }

    /** Picks up a finished run's result, keeping unsaved tags and edits. */
    private fun reloadOutlines(showNewest: Boolean) {
        val prefs = preferences ?: return
        val previousCount = outlines.size
        outlines = ChatOutlines.fromJson(prefs.getChatOutlines())
        val storedTags = prefs.getChatOutlineTags()
        if (!tagsDirty()) fieldTags?.setText(storedTags)
        savedTags = storedTags
        if (showNewest && outlines.size > previousCount) shownIndex = outlines.lastIndex
        clampIndexes()
        render()
    }

    private fun showProgress(percent: Int) {
        val existing = progressDialog
        if (existing?.isShowing == true) {
            setPercent(existing, percent)
            return
        }
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_compaction_progress, null)
        view.findViewById<TextView>(R.id.compaction_title)?.setText(R.string.outline_dialog_title)
        org.teslasoft.assistant.ui.util.SummarizerModelLine.bind(view, chatId)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener { dotsHandler.removeCallbacks(dotsTick) }
        (view.findViewById<View>(R.id.btn_dialog_primary_action)?.parent as? View)?.visibility = View.GONE
        view.findViewById<MaterialButton>(R.id.btn_dialog_action)?.apply {
            setText(R.string.btn_cancel)
            setOnClickListener {
                closeProgress()
                SummarizerControllerRegistry.cancel(chatId)
            }
        }
        progressDialog = dialog
        dialog.show()
        setPercent(dialog, percent)
        dotsStep = 0
        dotsHandler.removeCallbacks(dotsTick)
        dotsHandler.postDelayed(dotsTick, resources.getInteger(R.integer.progress_dialog_dots_step_ms).toLong())
    }

    private fun setPercent(dialog: AlertDialog, percent: Int) {
        dialog.findViewById<TextView>(R.id.compaction_percent)?.text =
            getString(R.string.compaction_dialog_percent, percent)
        dialog.findViewById<LinearProgressIndicator>(R.id.compaction_progress)?.setProgressCompat(percent, true)
    }

    /** "Outline complete!" with Okay, on the pop-up the user is watching. */
    private fun showComplete() {
        val dialog = progressDialog ?: return
        dotsHandler.removeCallbacks(dotsTick)
        setPercent(dialog, 100)
        dialog.findViewById<TextView>(R.id.compaction_dots)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.compaction_status)?.setText(R.string.outline_dialog_complete)
        dialog.findViewById<MaterialButton>(R.id.btn_dialog_action)?.apply {
            setText(R.string.okay)
            setOnClickListener { closeProgress() }
        }
    }

    private fun closeProgress() {
        dotsHandler.removeCallbacks(dotsTick)
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun showFailure(state: OperationState.Failed) {
        val (title, reason) = when (state.category) {
            SummarizerErrorCategory.MODEL_MISSING ->
                R.string.outline_err_model_missing_title to R.string.outline_err_model_missing
            SummarizerErrorCategory.SERVICE_UNREACHABLE ->
                R.string.outline_err_unreachable_title to R.string.outline_err_unreachable
            SummarizerErrorCategory.CONNECT_TIMEOUT ->
                R.string.outline_err_connect_timeout_title to R.string.outline_err_connect_timeout
            SummarizerErrorCategory.RESPONSE_TIMEOUT ->
                R.string.outline_err_response_timeout_title to R.string.outline_err_response_timeout
            SummarizerErrorCategory.ACCESS_REJECTED ->
                R.string.outline_err_access_rejected_title to R.string.outline_err_access_rejected
            SummarizerErrorCategory.MODEL_UNAVAILABLE ->
                R.string.outline_err_model_unavailable_title to R.string.outline_err_model_unavailable
            SummarizerErrorCategory.RATE_LIMIT ->
                R.string.outline_err_rate_limit_title to R.string.outline_err_rate_limit
            SummarizerErrorCategory.QUOTA ->
                R.string.outline_err_quota_title to R.string.outline_err_quota
            SummarizerErrorCategory.REQUEST_TOO_LARGE ->
                R.string.outline_err_too_large_title to R.string.outline_err_too_large
            SummarizerErrorCategory.CONTENT_REJECTED ->
                R.string.outline_err_rejected_title to R.string.outline_err_rejected
            SummarizerErrorCategory.SERVICE_ERROR ->
                R.string.outline_err_service_title to R.string.outline_err_service
            SummarizerErrorCategory.RESPONSE_UNREADABLE ->
                R.string.outline_err_unreadable_title to R.string.outline_err_unreadable
            SummarizerErrorCategory.SAVE_FAILED ->
                R.string.outline_err_save_title to R.string.outline_err_save
            SummarizerErrorCategory.UNEXPECTED ->
                R.string.outline_err_unexpected_title to R.string.outline_err_unexpected
        }
        val message = buildString {
            append(getString(reason))
            state.providerError?.let {
                append("\n")
                append(getString(R.string.compaction_err_provider, it))
            }
        }
        val actions = LayoutInflater.from(this).inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.okay)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        linkErrorLog(dialog, message)
    }

    /** Makes "Error Log" in the message a link that opens the Error Log
     *  (owner request, Nov 13 2026). The link takes the theme's link color. */
    private fun linkErrorLog(dialog: AlertDialog, message: String) {
        val phrase = getString(R.string.outline_err_log_link)
        val start = message.indexOf(phrase)
        if (start < 0) return
        val messageView = dialog.findViewById<TextView>(android.R.id.message) ?: return
        val text = android.text.SpannableString(message)
        text.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                dialog.dismiss()
                startActivity(
                    Intent(this@ChatOutlineActivity, LogsActivity::class.java)
                        .putExtra("type", "crash")
                        .putExtra("chatId", chatId)
                )
            }
        }, start, start + phrase.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        messageView.text = text
        messageView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_CHAT_NAME = "chatName"
        private const val STATE_SHOWN = "state_shown_outline"
        private const val STATE_EDITING = "state_editing_outline"
        private const val STATE_TAGS = "state_outline_tags"
        private const val STATE_EDIT_TEXT = "state_outline_edit_text"
        private const val MENU_EDIT = 1
        private const val MENU_REDO = 2
        private const val MENU_DELETE = 3

        fun createIntent(context: Context, chatId: String, chatName: String): Intent =
            Intent(context, ChatOutlineActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_CHAT_NAME, chatName)
    }
}
