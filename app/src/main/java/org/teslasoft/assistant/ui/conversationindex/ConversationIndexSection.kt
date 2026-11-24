/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.conversationindex

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.ConversationIndexConditions
import org.teslasoft.assistant.conversation.index.ConversationIndexCounts
import org.teslasoft.assistant.conversation.index.ConversationIndexRuntime
import org.teslasoft.assistant.conversation.index.ConversationIndexSetting
import org.teslasoft.assistant.conversation.index.IndexModelProblem
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage

/**
 * The Conversation Index section of RAG Active Memory Settings (G.16a,
 * Step 11), bound to `view_conversation_index_section.xml`:
 *
 *  - Index Conversations starts off and cannot be turned on without an
 *    installed on-device search model; trying explains why (G31).
 *  - The status line shows the real numbers read from the index (G28) and,
 *    beneath it, an ongoing condition: rebuilding, paused for the model, or
 *    stopped after a failed rebuild. Closing a notice never hides it here.
 *  - Delete Conversation Index asks first, turns the setting off and removes
 *    the index; the numbers are read again afterwards, and a failed deletion
 *    reports the failed step. An unreadable count is explained, never invented.
 */
class ConversationIndexSection(private val activity: Activity, root: View) {

    private val toggle: MaterialSwitch = root.findViewById(R.id.switch_index_conversations)
    private val status: TextView = root.findViewById(R.id.text_conversation_index_status)
    private val condition: TextView = root.findViewById(R.id.text_conversation_index_condition)
    private val delete: MaterialButton = root.findViewById(R.id.btn_delete_conversation_index)

    private val background = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conversation-index-settings").apply { isDaemon = true }
    }
    private val onConditionChanged: () -> Unit = { activity.runOnUiThread { refresh() } }
    /** Counts the last read asked for; an older read that finishes later is not shown. */
    @Volatile private var generation = 0
    private var deleteFailure: Snackbar? = null

    init {
        toggle.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        delete.setOnClickListener {
            lastDialogForTest = ConversationIndexDialogs.showDeleteConfirmation(activity) { deleteIndex() }
        }
    }

    /** Reads the counts again while the screen is open and indexing can change them. */
    private val periodicRefresh = object : Runnable {
        override fun run() {
            if (ConversationIndexSetting.isOn(activity) || ConversationIndexConditions.rebuilding) refresh()
            status.postDelayed(this, REFRESH_MILLIS)
        }
    }

    fun onResume() {
        ConversationIndexConditions.addListener(onConditionChanged)
        refresh()
        status.postDelayed(periodicRefresh, REFRESH_MILLIS)
    }

    fun onPause() {
        ConversationIndexConditions.removeListener(onConditionChanged)
        status.removeCallbacks(periodicRefresh)
    }

    fun onDestroy() {
        deleteFailure?.dismiss()
        background.shutdown()
    }

    private fun onToggle(checked: Boolean) {
        if (checked == ConversationIndexSetting.isOn(activity)) return
        if (!checked) {
            ConversationIndexRuntime.turnOff(activity)
            refresh()
            return
        }
        // An installed model is required (G.16a); the switch stays off.
        if (!(modelInstalledForTest ?: (EmbeddingModelStorage.activeModel(activity) != null))) {
            setToggle(false)
            lastDialogForTest = ConversationIndexDialogs.showModelNeeded(activity)
            return
        }
        ConversationIndexRuntime.turnOn(activity)
        refresh()
    }

    private fun deleteIndex() {
        deleteFailure?.dismiss()
        delete.isEnabled = false
        toggle.isEnabled = false
        // Turns the setting off at once; the removal runs on the index's thread.
        ConversationIndexRuntime.deleteIndex(activity.applicationContext) { result ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                delete.isEnabled = true
                toggle.isEnabled = true
                refresh()
                if (!result.deleted) {
                    val themed = ContextThemeWrapper(activity, R.style.ThemeOverlay_App_Snackbar_Error)
                    deleteFailure = Snackbar.make(
                        themed, activity.findViewById(android.R.id.content),
                        IndexFailureMessages.deletion(activity, result), Snackbar.LENGTH_INDEFINITE
                    ).setAction(R.string.okay) { }.also { it.show() }
                }
            }
        }
        setToggle(false)
    }

    /** Shows the setting and condition now, and the counts once read. */
    fun refresh() {
        if (activity.isFinishing || activity.isDestroyed) return
        setToggle(ConversationIndexSetting.isOn(activity))
        val app = activity.applicationContext
        val asked = ++generation
        try {
            background.execute {
                val counts = ConversationIndexRuntime.readCounts(app)
                val conditionText = conditionText()
                activity.runOnUiThread {
                    if (asked != generation || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    showCounts(counts)
                    condition.text = conditionText
                    condition.visibility = if (conditionText == null) View.GONE else View.VISIBLE
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The screen is closing.
        }
    }

    private fun conditionText(): String? = when {
        ConversationIndexConditions.rebuilding -> activity.getString(R.string.conversation_index_rebuilding)
        ConversationIndexConditions.recoveryFailed(activity) -> activity.getString(R.string.conversation_index_stopped_status)
        ConversationIndexSetting.isOn(activity) -> when (ConversationIndexConditions.modelProblem(activity)) {
            IndexModelProblem.REMOVED -> activity.getString(R.string.conversation_index_paused_model_removed)
            IndexModelProblem.CANNOT_LOAD -> activity.getString(R.string.conversation_index_paused_model_cannot_load)
            null -> null
        }
        else -> null
    }

    /** An unavailable count is explained; it is never replaced with zero or an old count. */
    internal fun showCounts(counts: ConversationIndexCounts?) {
        if (counts == null) {
            status.text = activity.getString(R.string.conversation_index_count_unavailable)
            status.visibility = View.VISIBLE
            return
        }
        val lines = ArrayList<String>()
        lines.add(activity.resources.getQuantityString(R.plurals.conversation_index_status_indexed, counts.indexed, counts.indexed))
        if (counts.needOpening > 0) {
            lines.add(
                activity.resources.getQuantityString(
                    R.plurals.conversation_index_status_need_opening, counts.needOpening, counts.needOpening
                )
            )
        }
        status.text = lines.joinToString("\n")
        status.visibility = View.VISIBLE
    }

    /** Sets the switch without treating it as the user's change. */
    private fun setToggle(on: Boolean) {
        if (toggle.isChecked == on) return
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = on
        toggle.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
    }

    companion object {
        /** How often the counts are read again while indexing runs and the screen is open. */
        private const val REFRESH_MILLIS = 3_000L

        /** Tests only: stands in for the installed-model check. */
        @Volatile var modelInstalledForTest: Boolean? = null

        /** Tests only: the last pop-up this section opened. */
        @Volatile var lastDialogForTest: androidx.appcompat.app.AlertDialog? = null
    }
}
