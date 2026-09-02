/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.util.AttributeSet
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

    /** Capture the bottommost visible row while this view still has its old height. */
    fun captureResizeAnchor() {
        if (height <= 0) return
        val manager = layoutManager as? LinearLayoutManager ?: return
        val position = manager.findLastVisibleItemPosition()
        if (position == NO_POSITION) return
        val anchor = manager.findViewByPosition(position) ?: return

        resizeAnchorPosition = position
        resizeAnchorTopFromBottom = anchor.top - height
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
        val position = resizeAnchorPosition
        if (position == NO_POSITION || position >= (adapter?.itemCount ?: 0)) return
        val manager = layoutManager as? LinearLayoutManager ?: return

        // onSizeChanged runs before RecyclerView.onLayout. Setting the pending
        // position here makes the new layout consume the exact bottom-relative
        // anchor immediately; there is no later runnable for another IME frame
        // to overtake or overwrite.
        manager.scrollToPositionWithOffset(
            position,
            h + resizeAnchorTopFromBottom
        )
    }
}
