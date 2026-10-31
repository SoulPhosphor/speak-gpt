/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.teslasoft.assistant.preferences.Logger
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs

/**
 * Keyboard Chat Interaction Debugging (Alerts, Errors & Logs switch, off by
 * default): records each chat viewport resize sequence and writes it to the
 * Error Log as one entry once it settles.
 *
 * A sequence starts at the first resize-related event and closes
 * [SETTLE_MS] after the last resize or movement, or after
 * [MAX_SEQUENCE_MS]. The entry title classifies it by anchor drift: how far
 * the held row moved relative to the top of the message box between the
 * first capture and the settled end, plus the largest frame-to-frame jump.
 * The body is the step-by-step trace, holding geometry and state only, never
 * message text.
 *
 * While the switch is off nothing here records, measures or allocates.
 */
internal object ResizeTrace {
    private const val SETTLE_MS = 300L
    private const val MAX_SEQUENCE_MS = 4_000L
    private const val MAX_LINES = 500
    private const val DRIFT_LIMIT_PX = 10
    private const val LOG_TAG = "ResizeTrace"

    private var handler: Handler? = null
    private val lines = ArrayList<String>()
    private var startNanos = 0L
    private var droppedLines = 0
    private var transcript = WeakReference<ChatTranscriptRecyclerView>(null)
    private var appContext: Context? = null

    /** Mirrors the saved switch; ChatActivity refreshes it on every resume. */
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) discard()
        }

    var active = false
        private set

    /** Frame counter shown on per-draw lines; reset with each sequence. */
    var frame = 0
        private set

    // Per-sequence drift measurement.
    /** The row whose distance from the message box is measured, or -1. */
    var baselinePosition = -1
        private set
    private var baselineDistance = 0
    private var lastFrameDistance: Int? = null
    private var baselineScreenDistance: Int? = null
    private var lastFrameScreenDistance: Int? = null
    private var maxScreenDrift = 0
    private var maxScreenFrameJump = 0
    private var baselineRowHeight: Int? = null
    private var lastRowHeight: Int? = null
    private var keyboardOpeningObserved = false
    private var keyboardClosingObserved = false
    private var keyboardFrameCount = 0
    private var initialImeBottom: Int? = null
    private var finalImeBottom: Int? = null
    private var viewportResizeCount = 0
    private var maxFrameJump = 0
    private var maxFrameJumpAt = ""
    private var maxDrift = 0
    private var anchorLeftScreen = false
    private var fingerOnChat = false
    private var sendClosedKeyboard = false
    private var lastStep = ""
    private var firstFailure: String? = null

    private val settle = Runnable { finish("settled ${SETTLE_MS}ms after last resize/movement") }

    fun attach(view: ChatTranscriptRecyclerView) {
        transcript = WeakReference(view)
        appContext = view.context.applicationContext
    }

    /** A resize-related event: starts a sequence if none is open and
     *  pushes the settle deadline back. */
    fun resize(line: String) {
        if (!enabled) return
        if (!active) begin()
        step(line)
        extend()
    }

    /** Observe actual IME motion, including an inset frame that does not
     * change container padding. A keyboard-opening failure must not get
     * classified as a composer-only resize. */
    fun keyboardInsetChanged(from: Int, to: Int, nav: Int, padding: Int, targetPadding: Int) {
        if (!enabled || from == to) return
        resize("IME ${if (to > from) "OPENING" else "CLOSING"} $from->$to nav=$nav padding=$padding->$targetPadding")
        if (initialImeBottom == null) initialImeBottom = from
        finalImeBottom = to
        keyboardFrameCount++
        if (to > from) keyboardOpeningObserved = true else keyboardClosingObserved = true
    }

    /** A keyboard inset without a chat-area resize indicates a missing
     * resize handoff, and must not receive a successful position-hold label. */
    fun transcriptResized(oldHeight: Int, newHeight: Int) {
        if (!active || oldHeight == newHeight) return
        viewportResizeCount++
        note("transcript viewport $oldHeight->$newHeight")
    }

    /** Movement during an open sequence also keeps it open, so a runaway
     *  scroll after the resize is captured (up to [MAX_SEQUENCE_MS]). */
    fun movement(line: String) {
        if (!active) return
        step(line)
        extend()
    }

    /** Context only; never starts or extends a sequence. */
    fun note(line: String) {
        if (!active) return
        step(line)
    }

    /** The first successful capture of a sequence sets the drift baseline:
     *  the row's top minus the viewport bottom, which is the composer top. */
    fun anchorCaptured(position: Int, distanceFromBox: Int, screenDistance: Int?, rowHeight: Int?) {
        if (!active || baselinePosition >= 0) return
        baselinePosition = position
        baselineDistance = distanceFromBox
        lastFrameDistance = distanceFromBox
        baselineScreenDistance = screenDistance
        lastFrameScreenDistance = screenDistance
        baselineRowHeight = rowHeight
        lastRowHeight = rowHeight
        note("anchor baseline: rowTopFromComposerScreen=${screenDistance ?: "unavailable"} rowHeight=${rowHeight ?: "unavailable"}")
    }

    fun fingerDown() {
        if (active) fingerOnChat = true
    }

    fun sendClosingKeyboard() {
        if (active) sendClosedKeyboard = true
    }

    /** One drawn frame. [distanceFromBox] is the baseline row's distance from
     *  the message box, or null when that row is not on screen. */
    fun frameDrawn(distanceFromBox: Int?, screenDistance: Int?, rowHeight: Int?): Int {
        val n = ++frame
        if (baselinePosition < 0) return n
        if (distanceFromBox == null) {
            if (!anchorLeftScreen) {
                anchorLeftScreen = true
                fail("draw#$n anchor row #$baselinePosition left the screen")
            }
            return n
        }
        lastFrameDistance?.let { previous ->
            val jump = abs(distanceFromBox - previous)
            if (jump > maxFrameJump) {
                maxFrameJump = jump
                maxFrameJumpAt = "draw#$n"
            }
            if (jump > DRIFT_LIMIT_PX) fail("draw#$n jumped ${jump}px in one frame")
        }
        val drift = distanceFromBox - baselineDistance
        if (abs(drift) > abs(maxDrift)) maxDrift = drift
        if (abs(drift) > DRIFT_LIMIT_PX) fail("draw#$n drifted ${drift}px")
        lastFrameDistance = distanceFromBox
        lastRowHeight = rowHeight

        // The old metric only compared row coordinates within the RecyclerView.
        // If Android moves the entire chat/composer, the row's local offset can
        // stay fixed even though its real screen position has changed.
        if (screenDistance != null && baselineScreenDistance != null) {
            val screenDrift = screenDistance - baselineScreenDistance!!
            if (abs(screenDrift) > abs(maxScreenDrift)) maxScreenDrift = screenDrift
            lastFrameScreenDistance?.let { previous ->
                maxScreenFrameJump = maxOf(maxScreenFrameJump, abs(screenDistance - previous))
            }
            if (abs(screenDrift) > DRIFT_LIMIT_PX) {
                fail("draw#$n row moved ${screenDrift}px relative to composer on screen")
            }
        }
        lastFrameScreenDistance = screenDistance
        return n
    }

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

    private fun fail(what: String) {
        if (firstFailure == null) firstFailure = "$what, after step: $lastStep"
    }

    private fun begin() {
        active = true
        lines.clear()
        droppedLines = 0
        frame = 0
        baselinePosition = -1
        baselineDistance = 0
        lastFrameDistance = null
        baselineScreenDistance = null
        lastFrameScreenDistance = null
        maxScreenDrift = 0
        maxScreenFrameJump = 0
        baselineRowHeight = null
        lastRowHeight = null
        keyboardOpeningObserved = false
        keyboardClosingObserved = false
        keyboardFrameCount = 0
        initialImeBottom = null
        finalImeBottom = null
        viewportResizeCount = 0
        maxFrameJump = 0
        maxFrameJumpAt = ""
        maxDrift = 0
        anchorLeftScreen = false
        fingerOnChat = false
        sendClosedKeyboard = false
        lastStep = ""
        firstFailure = null
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

    private fun discard() {
        handler?.removeCallbacks(settle)
        active = false
        lines.clear()
    }

    private fun finish(reason: String) {
        if (!active) return
        handler?.removeCallbacks(settle)
        val view = transcript.get()
        add("state at end: " + (view?.traceState() ?: "transcript not attached"))

        // The settled end is read from the laid-out rows now, not from the
        // last drawn frame, so a move after the final draw still counts.
        val endDistance = if (baselinePosition >= 0) view?.distanceFromBox(baselinePosition) else null
        val drift = endDistance?.let { it - baselineDistance }
        if (baselinePosition >= 0 && endDistance == null && !anchorLeftScreen) {
            anchorLeftScreen = true
            fail("at settle anchor row #$baselinePosition is off screen")
        }
        if (drift != null && abs(drift) > DRIFT_LIMIT_PX) fail("settled with drift ${drift}px")

        val event = when {
            keyboardOpeningObserved && keyboardClosingObserved -> "KEYBOARD OPEN/CLOSE"
            keyboardOpeningObserved -> "KEYBOARD OPEN"
            keyboardClosingObserved -> "KEYBOARD CLOSE"
            else -> "COMPOSER/OTHER (no keyboard motion recorded)"
        }
        val title = when {
            sendClosedKeyboard -> "[Resize Trace: NOT HELD (send closed keyboard)]"
            fingerOnChat -> "[Resize Trace: NOT HELD (finger on chat)]"
            baselinePosition < 0 -> "[Resize Trace: NOT HELD (nothing to hold)]"
            anchorLeftScreen && endDistance == null ->
                "[Resize Trace: $event / SPIN-OUT (anchor left screen)]"
            firstFailure != null -> "[Resize Trace: $event / DRIFT (row drift ${drift}px)]"
            keyboardOpeningObserved && viewportResizeCount == 0 ->
                "[Resize Trace: KEYBOARD OPEN / NO CHAT RESIZE]"
            else -> "[Resize Trace: $event / ROW HELD (row drift ${drift}px)]"
        }
        val failed = firstFailure != null || title.contains("SPIN-OUT") || title.contains("NO CHAT RESIZE")
        val message = buildString {
            append(title).append('\n')
            if (baselinePosition >= 0) {
                append("anchor #").append(baselinePosition)
                    .append(" start=").append(baselineDistance).append("px")
                    .append(" end=").append(endDistance?.let { "${it}px" } ?: "off screen")
                    .append(" maxDrift=").append(maxDrift).append("px")
                    .append(" maxFrameJump=").append(maxFrameJump).append("px")
                if (maxFrameJumpAt.isNotEmpty()) append(" at ").append(maxFrameJumpAt)
                append(" (distance = anchor row top - message box top)\n")
            }
            append("event=").append(event)
                .append(" IME frames=").append(keyboardFrameCount)
                .append(" IME bottom=").append(initialImeBottom?.toString() ?: "not observed")
                .append(" -> ").append(finalImeBottom?.toString() ?: "not observed")
                .append(" transcript resizes=").append(viewportResizeCount).append('\n')
            append("screen rowTop-from-composer baseline=")
                .append(baselineScreenDistance?.toString() ?: "unavailable")
                .append("px maxDrift=").append(maxScreenDrift)
                .append("px maxFrameJump=").append(maxScreenFrameJump).append("px\n")
            append("anchor row height baseline=")
                .append(baselineRowHeight?.toString() ?: "unavailable")
                .append(" end=").append(lastRowHeight?.toString() ?: "unavailable").append("px\n")
            firstFailure?.let { append("first problem: ").append(it).append('\n') }
            append("sequence ").append(reason).append(", ").append(frame).append(" frames\n")
            lines.forEach { append(it).append('\n') }
            if (droppedLines > 0) append("... ").append(droppedLines).append(" more lines dropped\n")
        }
        active = false
        lines.clear()
        val context = appContext ?: return
        Logger.logAsync(context, "crash", LOG_TAG, if (failed) "error" else "info", message.trimEnd())
    }

    private fun step(line: String) {
        if (!line.startsWith("draw#")) lastStep = line
        add(line)
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
