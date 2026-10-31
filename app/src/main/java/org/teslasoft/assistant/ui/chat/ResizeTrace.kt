/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * TEMPORARY flight recorder for the transcript keyboard-resize bug.
 *
 * Keeps the last few resize sequences in memory only (no file, no Logcat, no
 * message text) so the chat menu's temporary "Copy Resize Trace" item can put
 * them on the clipboard. A sequence starts at the first resize-related event
 * and closes [SETTLE_MS] after the last one, or after [MAX_SEQUENCE_MS].
 *
 * Remove this file and every `ResizeTrace` call once the bug is diagnosed.
 */
internal object ResizeTrace {
    private const val SETTLE_MS = 300L
    private const val MAX_SEQUENCE_MS = 4_000L
    private const val MAX_LINES = 500
    private const val KEEP_SEQUENCES = 6

    private var handler: Handler? = null
    private val lines = ArrayList<String>()
    private val finished = ArrayDeque<String>()
    private var startNanos = 0L
    private var sequenceNumber = 0
    private var droppedLines = 0
    private var transcript = WeakReference<ChatTranscriptRecyclerView>(null)

    var active = false
        private set

    /** Frame counter shown on per-draw lines; reset with each sequence. */
    var frame = 0
        private set

    private val settle = Runnable { finish("settled ${SETTLE_MS}ms after last resize/movement") }

    fun attach(view: ChatTranscriptRecyclerView) {
        transcript = WeakReference(view)
    }

    /** A resize-related event: starts a sequence if none is open and
     *  pushes the settle deadline back. */
    fun resize(line: String) {
        if (!active) begin()
        add(line)
        extend()
    }

    /** Movement during an open sequence also keeps it open, so a runaway
     *  scroll after the resize is captured (up to [MAX_SEQUENCE_MS]). */
    fun movement(line: String) {
        if (!active) return
        add(line)
        extend()
    }

    /** Context only; never starts or extends a sequence. */
    fun note(line: String) {
        if (!active) return
        add(line)
    }

    fun nextFrame(): Int = ++frame

    /** The first stack frame outside the transcript/trace/RecyclerView
     *  internals, for "who asked for this scroll". */
    fun caller(): String {
        if (!active) return ""
        val frameOut = Throwable().stackTrace.firstOrNull { element ->
            val c = element.className
            !c.startsWith(ResizeTrace::class.java.name) &&
                !c.startsWith(ChatTranscriptRecyclerView::class.java.name) &&
                !c.startsWith("androidx.recyclerview.") &&
                !c.startsWith("android.view.") &&
                !c.startsWith("java.") && !c.startsWith("kotlin.")
        } ?: return "?"
        return "${frameOut.fileName}:${frameOut.lineNumber} ${frameOut.methodName}"
    }

    fun copyToClipboard(context: Context) {
        val text = buildString {
            append("RESIZE TRACE (newest last)\n")
            if (finished.isEmpty() && !active) append("(no resize recorded yet)\n")
            finished.forEach { append(it).append('\n') }
            if (active) append(render("still open when copied")).append('\n')
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Resize trace", text))
    }

    private fun begin() {
        active = true
        sequenceNumber++
        lines.clear()
        droppedLines = 0
        frame = 0
        startNanos = System.nanoTime()
        add("state at start: " + (transcript.get()?.traceState() ?: "transcript not attached"))
    }

    private fun extend() {
        val h = handler ?: Handler(Looper.getMainLooper()).also { handler = it }
        h.removeCallbacks(settle)
        if (elapsedMs() >= MAX_SEQUENCE_MS) {
            finish("cut off at ${MAX_SEQUENCE_MS}ms (still moving)")
        } else {
            h.postDelayed(settle, SETTLE_MS)
        }
    }

    private fun finish(reason: String) {
        if (!active) return
        handler?.removeCallbacks(settle)
        add("state at end: " + (transcript.get()?.traceState() ?: "transcript not attached"))
        finished.addLast(render(reason))
        while (finished.size > KEEP_SEQUENCES) finished.removeFirst()
        active = false
        lines.clear()
    }

    private fun render(reason: String): String = buildString {
        append("=== sequence #").append(sequenceNumber)
            .append(" (").append(reason).append(") ===\n")
        lines.forEach { append(it).append('\n') }
        if (droppedLines > 0) append("... ").append(droppedLines).append(" more lines dropped\n")
    }

    private fun add(line: String) {
        if (lines.size >= MAX_LINES) {
            droppedLines++
            return
        }
        lines.add(String.format(Locale.US, "%7.1f  %s", elapsedMs().toDouble(), line))
    }

    private fun elapsedMs(): Float = (System.nanoTime() - startNanos) / 1_000_000f
}
