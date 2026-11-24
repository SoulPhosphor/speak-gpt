/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.conversationindex

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.index.IndexingDisplay

/** The pop-up's wording for each state (G.16a; Owner, 2026-11-24, Step 12). */
internal object IndexingDialogText {

    /** The status line; null when nothing is shown. */
    fun status(context: Context, display: IndexingDisplay): String? = when (display) {
        IndexingDisplay.Hidden -> null
        is IndexingDisplay.Paused -> context.getString(R.string.conversation_indexing_paused)
        is IndexingDisplay.Running -> display.numbers?.let {
            context.resources.getQuantityString(
                R.plurals.conversation_indexing_status, it.totalChats, it.indexedChats, it.totalChats
            )
        } ?: context.getString(R.string.conversation_indexing_counting)
    }

    /** The percentage beside the title; empty while none is known. */
    fun percent(context: Context, display: IndexingDisplay): String {
        val numbers = when (display) {
            is IndexingDisplay.Running -> display.numbers
            is IndexingDisplay.Paused -> display.numbers
            IndexingDisplay.Hidden -> null
        }
        return numbers?.let { context.getString(R.string.conversation_indexing_percent, it.percent) }.orEmpty()
    }
}

/**
 * The Indexing Conversations progress pop-up (G.16a; Owner, 2026-11-24,
 * Step 12), on the shared Progress pop-up layout:
 *
 *  - running: the real percentage and bar, "128 of 310 conversations
 *    indexed" with the looping dots, Pause and Close;
 *  - counting the messages first: no percentage, a moving bar, "Counting
 *    messages to index…" with the dots, Pause and Close;
 *  - paused: the percentage and bar kept still, "Paused", Resume and
 *    Close; paused before the count finished, no percentage or bar.
 *
 * It follows the progress while open, reading the numbers again every few
 * seconds while indexing runs, and closes when the icon goes.
 */
class ConversationIndexingDialog(
    private val context: Context,
    private val controls: IndexingControls
) {
    private val view = LayoutInflater.from(context).inflate(R.layout.dialog_conversation_indexing, null)
    private val percent: TextView = view.findViewById(R.id.indexing_percent)
    private val bar: LinearProgressIndicator = view.findViewById(R.id.indexing_bar)
    private val status: TextView = view.findViewById(R.id.indexing_status)
    private val dots: TextView = view.findViewById(R.id.indexing_dots)
    private val pauseResume: MaterialButton = view.findViewById(R.id.indexing_pause_resume)
    private val close: MaterialButton = view.findViewById(R.id.indexing_close)
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var running = false
    private var dotsStep = 0

    val isShowing: Boolean get() = dialog?.isShowing == true

    private val dotsTick = object : Runnable {
        override fun run() {
            dotsStep = (dotsStep + 1) % DOTS_STEPS
            dots.text = DOT.repeat(dotsStep)
            handler.postDelayed(this, context.resources.getInteger(R.integer.progress_dialog_dots_step_ms).toLong())
        }
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            controls.refresh()
            handler.postDelayed(this, context.resources.getInteger(R.integer.conversation_indexing_refresh_ms).toLong())
        }
    }

    init {
        close.setOnClickListener { dismiss() }
    }

    fun open(display: IndexingDisplay) {
        if (display == IndexingDisplay.Hidden) return
        if (dialog == null) {
            dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
                .setView(view)
                .create()
                .apply { setOnDismissListener { stopTicks() } }
        }
        if (dialog?.isShowing != true) dialog?.show()
        bind(display)
        controls.refresh()
    }

    fun dismiss() {
        stopTicks()
        dialog?.takeIf { it.isShowing }?.dismiss()
    }

    /** The screen left the front: the dots and the reading stop until the next [bind]. */
    fun stopUpdates() = stopTicks()

    /** Shows [display]; closes the pop-up when indexing is over. */
    fun bind(display: IndexingDisplay) {
        val numbers = when (display) {
            IndexingDisplay.Hidden -> {
                dismiss()
                return
            }
            is IndexingDisplay.Running -> display.numbers
            is IndexingDisplay.Paused -> display.numbers
        }
        val isRunning = display is IndexingDisplay.Running
        percent.text = IndexingDialogText.percent(context, display)
        status.text = IndexingDialogText.status(context, display)
        when {
            isRunning && numbers == null -> showBar(indeterminate = true, value = 0)
            numbers != null -> showBar(indeterminate = false, value = numbers.percent)
            // Paused before the count finished: no bar until a real percentage exists.
            else -> bar.visibility = View.GONE
        }
        pauseResume.setText(if (isRunning) R.string.conversation_indexing_pause else R.string.conversation_indexing_resume)
        pauseResume.setOnClickListener { if (isRunning) controls.pause() else controls.resume() }
        dots.visibility = if (isRunning) View.VISIBLE else View.GONE
        setRunning(isRunning)
    }

    /** Tests: the shown texts and controls. */
    internal val percentText: CharSequence get() = percent.text
    internal val statusText: CharSequence get() = status.text
    internal val barShown: Boolean get() = bar.visibility == View.VISIBLE
    internal val barIndeterminate: Boolean get() = bar.isIndeterminate
    internal val barProgress: Int get() = bar.progress
    internal val dotsShown: Boolean get() = dots.visibility == View.VISIBLE
    internal val pauseResumeText: CharSequence get() = pauseResume.text
    internal fun clickPauseResume() = pauseResume.performClick()
    internal fun clickClose() = close.performClick()
    internal fun textOf(id: Int): CharSequence? = view.findViewById<TextView>(id)?.text

    private fun showBar(indeterminate: Boolean, value: Int) {
        if (bar.isIndeterminate != indeterminate) {
            // The bar changes mode only while hidden.
            bar.visibility = View.INVISIBLE
            bar.isIndeterminate = indeterminate
        }
        if (!indeterminate) bar.setProgressCompat(value, false)
        bar.visibility = View.VISIBLE
    }

    /** The dots move, and the numbers are read again, only while indexing runs and the pop-up is open. */
    private fun setRunning(isRunning: Boolean) {
        if (isRunning == running) return
        stopTicks()
        running = isRunning
        if (isRunning) {
            handler.post(dotsTick)
            handler.postDelayed(refreshTick, context.resources.getInteger(R.integer.conversation_indexing_refresh_ms).toLong())
        } else {
            dots.text = ""
        }
    }

    /** Stops the dots and the reading; [setRunning] starts them again for a reopened pop-up. */
    private fun stopTicks() {
        handler.removeCallbacks(dotsTick)
        handler.removeCallbacks(refreshTick)
        running = false
    }

    private companion object {
        const val DOT = "."
        /** No dots, then one, two and three. */
        const val DOTS_STEPS = 4
    }
}
