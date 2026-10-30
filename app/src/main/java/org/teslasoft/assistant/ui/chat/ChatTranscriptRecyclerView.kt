/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps the transcript content touching the composer in the same place while
 * the transcript viewport changes height.
 *
 * The anchor is captured before the composer or IME changes the available
 * height. [onSizeChanged] then gives LinearLayoutManager the absolute target
 * offset before RecyclerView lays out its children at the new height. Applying
 * the anchor in that layout pass avoids a posted restore racing the next IME
 * frame or a simultaneous composer promotion.
 */
class ChatTranscriptRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var resizeAnchorPosition = NO_POSITION
    private var resizeAnchorTopFromBottom = 0
    private var resizeAnchorPending = false
    private var resizeAnchorAtEnd = false
    private var resizeAnchorHeight = 0

    /** A finger is on the transcript. While it is, no resize moves the
     *  conversation (chat-keyboard-behavior.md, rule 1). */
    private var touching = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return super.dispatchTouchEvent(event)
    }

    /** Capture the bottommost visible row while this view still has its old height. */
    fun captureResizeAnchor() {
        if (touching) {
            resizeAnchorPending = false
            return
        }
        if (height <= 0) return
        val manager = layoutManager as? LinearLayoutManager ?: return
        val position = manager.findLastVisibleItemPosition()
        if (position == NO_POSITION) return
        val anchor = manager.findViewByPosition(position) ?: return

        resizeAnchorPosition = position
        resizeAnchorTopFromBottom = anchor.top - height
        // A transcript sitting at its real end is held by that end: the last
        // row's bottom stays on the composer's top edge, not just the row's top.
        resizeAnchorAtEnd = position == (adapter?.itemCount ?: 0) - 1 &&
            anchor.bottom <= height - paddingBottom + END_TOLERANCE_PX
        resizeAnchorHeight = anchor.height
        resizeAnchorPending = true
    }

    /**
     * Positions an ordinary chat open at the real end of the transcript.
     * RecyclerView's plain scrollToPosition only promises that the last row is
     * visible; a last message taller than the viewport can therefore land at
     * its top. The one-layout correction aligns that row's bottom instead.
     * Short conversations retain LinearLayoutManager's normal top alignment.
     */
    fun scrollToTranscriptEnd() {
        val manager = layoutManager as? LinearLayoutManager ?: return
        val last = (adapter?.itemCount ?: 0) - 1
        if (last < 0) return

        val correction = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                removeOnLayoutChangeListener(this)
                val lastView = manager.findViewByPosition(last) ?: return
                val overflow = lastView.bottom - (height - paddingBottom)
                // A negative correction is clamped to zero for a genuinely
                // short transcript, preserving its top alignment. For a long
                // transcript with a short final row it moves that row down to
                // the bottom; a positive correction exposes the bottom of a
                // final row taller than the viewport.
                if (overflow != 0) scrollBy(0, overflow)
            }
        }
        addOnLayoutChangeListener(correction)
        manager.scrollToPositionWithOffset(last, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h == oldh || !resizeAnchorPending) return

        resizeAnchorPending = false
        if (touching) return
        val position = resizeAnchorPosition
        if (position == NO_POSITION || position >= (adapter?.itemCount ?: 0)) return
        val manager = layoutManager as? LinearLayoutManager ?: return

        // onSizeChanged runs before RecyclerView.onLayout. Setting the pending
        // position here makes the new layout consume the exact bottom-relative
        // anchor immediately; there is no later runnable for another IME frame
        // to overtake or overwrite.
        if (!resizeAnchorAtEnd) {
            manager.scrollToPositionWithOffset(
                position,
                h + resizeAnchorTopFromBottom
            )
            return
        }
        // At the end, the offset is taken from the last row's own height so
        // the new layout already puts its bottom on the viewport end.
        manager.scrollToPositionWithOffset(position, h - paddingBottom - resizeAnchorHeight)
        pinEndAfterLayout(manager, position)
    }

    /**
     * Checks the end hold once, right after this view lays out at its new
     * height and before that frame is drawn. It scrolls only if the last row
     * came out a different height than it had when the anchor was captured;
     * otherwise the offset above was already exact and nothing moves. It is a
     * layout callback, not a posted runnable, so no IME frame or composer
     * change can land in between, and it removes itself after one check.
     */
    private fun pinEndAfterLayout(manager: LinearLayoutManager, last: Int) {
        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                removeOnLayoutChangeListener(this)
                if (touching) return
                val lastView = manager.findViewByPosition(last) ?: return
                // Positive pulls a covered bottom back into view; negative is
                // clamped at the top for a transcript shorter than the viewport.
                val overflow = lastView.bottom - (height - paddingBottom)
                if (overflow != 0) scrollBy(0, overflow)
            }
        })
    }

    private companion object {
        const val END_TOLERANCE_PX = 1
    }
}
