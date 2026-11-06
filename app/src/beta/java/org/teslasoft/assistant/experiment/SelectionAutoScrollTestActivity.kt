/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.experiment

import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * Beta build only. Temporary highlight auto-scroll experiment, opened from
 * its own "Selection Test" icon. It answers whether Android's own highlight
 * handles keep following the finger while a text box scrolls inside itself,
 * with and without the app scrolling the box at the edge. Remove with the
 * experiment.
 */
class SelectionAutoScrollTestActivity : AppCompatActivity() {

    private lateinit var text: EdgeScrollTestTextView
    private lateinit var status: TextView
    private lateinit var edgeSwitch: MaterialSwitch

    private var edgeZonePx = 0f
    private var speedPxPerSecond = 0f
    private var noMovementStopMs = 0L

    // Highlight tracking.
    private var lastStart = -1
    private var lastEnd = -1
    private var movingEnd = true
    private var changes = 0
    private var lastChangeAt = 0L
    private var longestGapMs = 0L

    // Edge scrolling.
    private var running = false
    private var lastFrameAt = 0L
    private var pendingScroll = 0f

    private val frame = object : Runnable {
        override fun run() = onFrame()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            status.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selection_autoscroll_test)

        val actionBar = findViewById<android.view.View>(R.id.action_bar)
        val back = findViewById<android.view.View>(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, back)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))
        back.setOnClickListener { finish() }

        edgeZonePx = resources.getDimension(R.dimen.selection_test_edge_zone)
        speedPxPerSecond = resources.getDimension(R.dimen.selection_test_scroll_speed_per_second)
        noMovementStopMs = resources.getInteger(R.integer.selection_test_no_movement_stop_ms).toLong()

        status = findViewById(R.id.status)
        edgeSwitch = findViewById(R.id.switch_edge_scrolling)
        edgeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) stopEdgeScrolling()
            updateStatus()
        }

        text = findViewById(R.id.test_text)
        text.setText(sampleText(), TextView.BufferType.SPANNABLE)
        text.setTextIsSelectable(true)
        // The chat restores LinkMovementMethod after enabling selection so
        // links stay tappable; the experiment does the same.
        text.movementMethod = LinkMovementMethod.getInstance()
        text.onHighlightChanged = ::onHighlightChanged
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        status.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        status.removeCallbacks(ticker)
        stopEdgeScrolling()
    }

    private fun sampleText(): CharSequence {
        val builder = SpannableStringBuilder()
        for (n in 1..SAMPLE_LINES) {
            if (n > 1) builder.append('\n')
            if (n % LINK_EVERY == 0) {
                builder.append(getString(R.string.selection_test_link_line, n, getString(R.string.selection_test_link_url, n)))
            } else {
                builder.append(getString(R.string.selection_test_line, n))
            }
        }
        Linkify.addLinks(builder, Linkify.WEB_URLS)
        return builder
    }

    private fun onHighlightChanged(start: Int, end: Int) {
        val now = SystemClock.uptimeMillis()
        if (running && lastChangeAt > 0) {
            longestGapMs = maxOf(longestGapMs, now - lastChangeAt)
        }
        changes++
        lastChangeAt = now

        val startMoved = start != lastStart
        val endMoved = end != lastEnd
        if (startMoved && !endMoved) movingEnd = false
        if (endMoved && !startMoved) movingEnd = true
        lastStart = start
        lastEnd = end

        if (start == end) {
            stopEdgeScrolling()
        } else if (edgeSwitch.isChecked && movingOffsetIsAtEdge()) {
            startEdgeScrolling()
        } else {
            stopEdgeScrolling()
        }
        updateStatus()
    }

    /** Whether the line of the handle being dragged is within the edge zone of the box. */
    private fun movingOffsetIsAtEdge(): Boolean {
        val layout = text.layout ?: return false
        val offset = if (movingEnd) lastEnd else lastStart
        if (offset < 0) return false
        val line = layout.getLineForOffset(offset)
        val top = layout.getLineTop(line) + text.totalPaddingTop - text.scrollY
        val bottom = layout.getLineBottom(line) + text.totalPaddingTop - text.scrollY
        return if (movingEnd) {
            bottom >= text.height - text.totalPaddingBottom - edgeZonePx
        } else {
            top <= text.totalPaddingTop + edgeZonePx
        }
    }

    private fun startEdgeScrolling() {
        if (running) return
        running = true
        longestGapMs = 0
        lastFrameAt = SystemClock.uptimeMillis()
        pendingScroll = 0f
        text.postOnAnimation(frame)
    }

    private fun stopEdgeScrolling() {
        if (!running) return
        running = false
        text.removeCallbacks(frame)
    }

    private fun onFrame() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        // Android only moves a handle when the screen reports finger
        // movement, and the app cannot see the finger. If no movement has
        // been reported for a while, the finger is either resting without
        // reports or lifted; either way, stop.
        if (now - lastChangeAt > noMovementStopMs || lastStart == lastEnd) {
            stopEdgeScrolling()
            updateStatus()
            return
        }

        pendingScroll += speedPxPerSecond * (now - lastFrameAt) / 1000f
        lastFrameAt = now
        val step = pendingScroll.toInt()
        pendingScroll -= step

        val target = if (movingEnd) {
            (text.scrollY + step).coerceAtMost(text.maxScroll)
        } else {
            (text.scrollY - step).coerceAtLeast(0)
        }
        if (step > 0 && target == text.scrollY) {
            // Reached the end or the start of the text.
            stopEdgeScrolling()
            updateStatus()
            return
        }
        if (target != text.scrollY) text.scrollTo(text.scrollX, target)
        updateStatus()
        text.postOnAnimation(frame)
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val state = getString(
            when {
                !edgeSwitch.isChecked -> R.string.selection_test_state_off
                running -> R.string.selection_test_state_running
                else -> R.string.selection_test_state_stopped
            }
        )
        val start = text.selectionStart
        val end = text.selectionEnd
        status.text = if (start < 0 || start == end) {
            getString(R.string.selection_test_status_none, text.scrollY, changes)
        } else {
            val sinceLast = if (lastChangeAt > 0) SystemClock.uptimeMillis() - lastChangeAt else 0L
            getString(
                R.string.selection_test_status,
                sampleLineOf(start), sampleLineOf(end), text.scrollY,
                changes, sinceLast.toInt(), longestGapMs.toInt(), state
            )
        }
    }

    /** The "Line N" number holding [offset]: each sample line is one paragraph. */
    private fun sampleLineOf(offset: Int): Int {
        val content = text.text ?: return 0
        var line = 1
        for (i in 0 until offset.coerceAtMost(content.length)) {
            if (content[i] == '\n') line++
        }
        return line
    }

    private companion object {
        const val SAMPLE_LINES = 400
        const val LINK_EVERY = 20
        const val STATUS_REFRESH_MS = 100L
    }
}
