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
import android.view.ViewTreeObserver
import org.teslasoft.assistant.R
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
    private var resizeAnchorFromTop = false
    private var resizeAnchorTop = 0
    private var fingerLayoutAnchor: Pair<Int, Int>? = null
    private var retainPositionAfterTouch = false
    private var resizeHoldReleasedForLayout = false

    /** Send releases the hold; all other viewport changes preserve it. */
    var shouldHoldPositionOnResize: () -> Boolean = { true }
    var onUserInteraction: (() -> Unit)? = null

    /** A finger is on the transcript. While it is, no resize moves the
     *  conversation (chat-keyboard-behavior.md, rule 1). */
    private var touching = false

    // ---- Keyboard Chat Interaction Debugging (see ResizeTrace) ----
    /** Extra activity state shown at the start/end of each trace sequence. */
    var traceExtraState: (() -> String)? = null
    private var lastDrawSnapshot = ""
    private val tracePreDraw = ViewTreeObserver.OnPreDrawListener {
        if (ResizeTrace.active) {
            val position = ResizeTrace.baselinePosition
            val row = position.takeIf { it >= 0 }?.let { layoutManager?.findViewByPosition(it) }
            val frame = ResizeTrace.frameDrawn(
                row?.let { it.top - height },
                position.takeIf { it >= 0 }?.let(::screenDistanceFromComposer),
                row?.height
            )
            val snapshot = "h=$height ${rowsDesc()} ${screenGeometry()}"
            if (snapshot != lastDrawSnapshot) {
                lastDrawSnapshot = snapshot
                ResizeTrace.movement("draw#$frame $snapshot")
            }
        } else {
            lastDrawSnapshot = ""
        }
        true
    }
    private val traceAdapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = ResizeTrace.note("adapter: dataSetChanged")
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
            ResizeTrace.note("adapter: changed $positionStart+$itemCount")
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
            ResizeTrace.note("adapter: changed $positionStart+$itemCount payload")
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
            ResizeTrace.note("adapter: inserted $positionStart+$itemCount")
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
            ResizeTrace.note("adapter: removed $positionStart+$itemCount")
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            ResizeTrace.note("adapter: moved $fromPosition->$toPosition x$itemCount")
    }

    init {
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && ResizeTrace.active) ResizeTrace.movement("onScrolled dy=$dy ${rowsDesc()}")
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                ResizeTrace.note("scrollState=$newState")
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ResizeTrace.attach(this)
        viewTreeObserver.addOnPreDrawListener(tracePreDraw)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(tracePreDraw)
        super.onDetachedFromWindow()
    }

    override fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
        this.adapter?.unregisterAdapterDataObserver(traceAdapterObserver)
        super.setAdapter(adapter)
        adapter?.registerAdapterDataObserver(traceAdapterObserver)
    }

    override fun scrollToPosition(position: Int) {
        ResizeTrace.movement("scrollToPosition($position) from ${ResizeTrace.caller()}")
        super.scrollToPosition(position)
    }

    override fun smoothScrollToPosition(position: Int) {
        ResizeTrace.movement("smoothScrollToPosition($position) from ${ResizeTrace.caller()}")
        super.smoothScrollToPosition(position)
    }

    override fun scrollBy(x: Int, y: Int) {
        if (y != 0) ResizeTrace.movement("scrollBy(dy=$y) from ${ResizeTrace.caller()}")
        super.scrollBy(x, y)
    }

    override fun smoothScrollBy(dx: Int, dy: Int) {
        if (dy != 0) ResizeTrace.movement("smoothScrollBy(dy=$dy) from ${ResizeTrace.caller()}")
        super.smoothScrollBy(dx, dy)
    }

    /** A row's top minus the viewport bottom (the message box top), the same
     *  distance the resize hold keeps; null when the row is not laid out. */
    fun distanceFromBox(position: Int): Int? =
        layoutManager?.findViewByPosition(position)?.let { it.top - height }

    /** Physical screen measurement, not just RecyclerView-local coordinates.
     * Debug-only so a window translation or inset mismatch cannot masquerade
     * as zero anchor drift. No message text or content is accessed. */
    private fun screenDistanceFromComposer(position: Int): Int? {
        val row = layoutManager?.findViewByPosition(position) ?: return null
        val composer = rootView.findViewById<View>(R.id.composer_surface) ?: return null
        val chatXY = IntArray(2)
        val composerXY = IntArray(2)
        getLocationOnScreen(chatXY)
        composer.getLocationOnScreen(composerXY)
        return chatXY[1] + row.top - composerXY[1]
    }

    private fun screenGeometry(): String {
        val composer = rootView.findViewById<View>(R.id.composer_surface)
            ?: return "screenComposer=unavailable"
        val chatXY = IntArray(2)
        val composerXY = IntArray(2)
        getLocationOnScreen(chatXY)
        composer.getLocationOnScreen(composerXY)
        val row = resizeAnchorPosition.takeIf { it >= 0 }
            ?.let { layoutManager?.findViewByPosition(it) }
        return "screen{chatTop=${chatXY[1]} chatBottom=${chatXY[1] + height} " +
            "composerTop=${composerXY[1]} gap=${composerXY[1] - chatXY[1] - height} " +
            "anchorTop=${row?.let { chatXY[1] + it.top } ?: "offscreen"} " +
            "anchorBottom=${row?.let { chatXY[1] + it.bottom } ?: "offscreen"}}"
    }

    private fun rowDesc(position: Int): String {
        if (position == NO_POSITION) return "none"
        val row = layoutManager?.findViewByPosition(position) ?: return "#$position(offscreen)"
        return "#$position[top=${row.top} bottom=${row.bottom} h=${row.height}]"
    }

    /** Recorded anchor row plus the rows at both visible edges. */
    private fun rowsDesc(): String {
        val manager = layoutManager as? LinearLayoutManager ?: return "no LLM"
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        return "anchor=${rowDesc(resizeAnchorPosition)} first=${rowDesc(first)} " +
            "last=${rowDesc(last)} of ${adapter?.itemCount ?: 0}"
    }

    fun traceState(): String =
        "h=$height pad=$paddingTop/$paddingBottom lm.h=${layoutManager?.height} " +
            "touching=$touching pending=$resizeAnchorPending fromTop=$resizeAnchorFromTop " +
            "retainAfterTouch=$retainPositionAfterTouch heldReleased=$resizeHoldReleasedForLayout " +
            "fingerAnchor=$fingerLayoutAnchor hold=${shouldHoldPositionOnResize()} " +
            "canScrollDown=${canScrollVertically(1)} scrollState=$scrollState " +
            "${rowsDesc()} ${screenGeometry()} ${traceExtraState?.invoke() ?: ""}"
    // ---- end Keyboard Chat Interaction Debugging ----

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ResizeTrace.note("touch DOWN")
                ResizeTrace.fingerDown()
                touching = true
                releaseResizeAnchor()
                onUserInteraction?.invoke()
            }
            MotionEvent.ACTION_SCROLL -> onUserInteraction?.invoke()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ResizeTrace.note("touch UP/CANCEL")
                touching = false
                resizeAnchorPending = false
                fingerLayoutAnchor = null
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Capture the bottommost visible row while this view still has its old height. */
    fun captureResizeAnchor(reason: String = "external") {
        captureResizeAnchor(height, reason)
    }

    fun releaseResizeAnchor() {
        ResizeTrace.note("releaseResizeAnchor from ${ResizeTrace.caller()}")
        resizeAnchorPending = false
        fingerLayoutAnchor = null
        retainPositionAfterTouch = false
        resizeHoldReleasedForLayout = false
    }

    /** The final IME inset can report closed before its padding is laid out. */
    fun releaseResizeAnchorForNextLayout() {
        ResizeTrace.note("releaseResizeAnchorForNextLayout (send close)")
        releaseResizeAnchor()
        resizeHoldReleasedForLayout = true
    }

    private fun captureResizeAnchor(height: Int, reason: String) {
        val tag = "capture[$reason] at h=$height"
        if (resizeHoldReleasedForLayout || !shouldHoldPositionOnResize()) {
            ResizeTrace.resize("$tag SKIPPED heldReleased=$resizeHoldReleasedForLayout hold=${shouldHoldPositionOnResize()}")
            return
        }
        if (height <= 0) { ResizeTrace.note("$tag SKIPPED height<=0"); return }
        val manager = layoutManager as? LinearLayoutManager
            ?: run { ResizeTrace.resize("$tag SKIPPED no LLM"); return }
        val position = if (touching) manager.findFirstVisibleItemPosition()
            else manager.findLastVisibleItemPosition()
        if (position == NO_POSITION) { ResizeTrace.resize("$tag SKIPPED no visible row"); return }
        val anchor = manager.findViewByPosition(position)
            ?: run { ResizeTrace.resize("$tag SKIPPED row #$position has no view"); return }
        val isAtEnd = position == (adapter?.itemCount ?: 0) - 1 && !canScrollVertically(1)
        ResizeTrace.resize(
            "$tag row=#$position top=${anchor.top} bottom=${anchor.bottom} rowH=${anchor.height} " +
                "topFromBottom=${anchor.top - height} fromTop=$touching isAtEnd=$isAtEnd " +
                "bottomPastViewport=${anchor.bottom - (height - paddingBottom)} " +
                "replacesPending=$resizeAnchorPending(prev #$resizeAnchorPosition tfb=$resizeAnchorTopFromBottom)"
        )
        if (ResizeTrace.active) {
            ResizeTrace.anchorCaptured(position, anchor.top - height,
                screenDistanceFromComposer(position), anchor.height)
        }
        if (touching) ResizeTrace.fingerDown()

        resizeAnchorPosition = position
        resizeAnchorTopFromBottom = anchor.top - height
        resizeAnchorFromTop = touching
        resizeAnchorTop = anchor.top - paddingTop
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
        if (touching) return
        releaseResizeAnchor()
        val manager = layoutManager as? LinearLayoutManager ?: return
        val last = (adapter?.itemCount ?: 0) - 1
        if (last < 0) return

        ResizeTrace.note("scrollToTranscriptEnd from ${ResizeTrace.caller()}")
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
                if (touching) return
                val lastView = manager.findViewByPosition(last) ?: return
                val overflow = lastView.bottom - (height - paddingBottom)
                // A negative correction is clamped to zero for a genuinely
                // short transcript, preserving its top alignment. For a long
                // transcript with a short final row it moves that row down to
                // the bottom; a positive correction exposes the bottom of a
                // final row taller than the viewport.
                ResizeTrace.note("scrollToTranscriptEnd correction overflow=$overflow")
                if (overflow != 0) scrollBy(0, overflow)
            }
        }
        addOnLayoutChangeListener(correction)
        manager.scrollToPositionWithOffset(last, 0)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val specH = MeasureSpec.getSize(heightSpec)
        val resizing = measuredHeight > 0 && specH != measuredHeight
        if (!resizing && !ResizeTrace.active) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        val before = "measured=$measuredHeight lm.h=${layoutManager?.height} ${rowDesc(resizeAnchorPosition)}"
        super.onMeasure(widthSpec, heightSpec)
        val line = "onMeasure spec=${MeasureSpec.toString(heightSpec)} before{$before} " +
            "after{measured=$measuredHeight lm.h=${layoutManager?.height} ${rowDesc(resizeAnchorPosition)}}"
        if (resizing) ResizeTrace.resize(line) else ResizeTrace.note(line)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // The first layout (from zero height) is not a resize; it is only
        // recorded when a sequence is already open.
        val trace: (String) -> Unit = if (oldh > 0) ResizeTrace::resize else ResizeTrace::note
        // While the layout manager was last laid out at the old height, the
        // children still have their old coordinates here, so a fresh capture
        // replaces any earlier one that may have gone stale since. Wrapping a
        // typed line also reaches this point without an IME inset or composer
        // mode callback. If RecyclerView already laid the children out at a
        // different height while measuring, only an earlier capture still
        // describes the old position.
        if (h != oldh) {
            ResizeTrace.transcriptResized(oldh, h)
            val childrenAtOldHeight = layoutManager?.height == oldh
            trace(
                "onSizeChanged $oldh -> $h lm.h=${layoutManager?.height} " +
                    "childrenAtOldHeight=$childrenAtOldHeight (false = rows already laid out at another height) " +
                    "pending=$resizeAnchorPending touching=$touching ${rowsDesc()}"
            )
            if (childrenAtOldHeight || !resizeAnchorPending || touching) {
                if (childrenAtOldHeight) resizeAnchorPending = false
                captureResizeAnchor(oldh, "onSizeChanged fresh at oldh")
            } else {
                trace("onSizeChanged keeps earlier capture #$resizeAnchorPosition tfb=$resizeAnchorTopFromBottom")
            }
        }
        super.onSizeChanged(w, h, oldw, oldh)
        if (h == oldh || !resizeAnchorPending) {
            if (h != oldh) trace("onSizeChanged: no pending anchor -> NO placement")
            return
        }

        resizeAnchorPending = false
        if (!shouldHoldPositionOnResize()) {
            trace("onSizeChanged: hold off -> NO placement")
            return
        }
        val position = resizeAnchorPosition
        if (position == NO_POSITION || position >= (adapter?.itemCount ?: 0)) {
            trace("onSizeChanged: anchor #$position invalid -> NO placement")
            return
        }
        val manager = layoutManager as? LinearLayoutManager ?: return

        // Skipping the hold alone still lets LinearLayoutManager fill a new
        // bottom gap by moving the rows under a finger. Keep their absolute
        // screen coordinates instead of their distance from the composer.
        if (resizeAnchorFromTop) {
            fingerLayoutAnchor = position to resizeAnchorTop
            trace("onSizeChanged: finger anchor #$position top=$resizeAnchorTop (applied in onLayout)")
            return
        }

        // onSizeChanged runs before RecyclerView.onLayout. Setting the pending
        // position here makes the new layout consume the exact bottom-relative
        // anchor immediately; there is no later runnable for another IME frame
        // to overtake or overwrite. At the real end of a chat the same offset
        // keeps the last row's bottom on the composer, and LinearLayoutManager
        // keeps a short chat from leaving a gap at either edge.
        if (retainPositionAfterTouch) {
            fingerLayoutAnchor = position to (h + resizeAnchorTopFromBottom)
        }
        trace(
            "onSizeChanged: PLACE scrollToPositionWithOffset(#$position, offset=$h+($resizeAnchorTopFromBottom)=" +
                "${h + resizeAnchorTopFromBottom}) retainAfterTouch=$retainPositionAfterTouch"
        )
        manager.scrollToPositionWithOffset(
            position,
            h + resizeAnchorTopFromBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val manager = layoutManager as? LinearLayoutManager
        val anchor = if ((touching || retainPositionAfterTouch) && manager != null) {
            fingerLayoutAnchor ?: manager.findFirstVisibleItemPosition().let { position ->
                manager.findViewByPosition(position)?.let { position to (it.top - paddingTop) }
            }
        } else null
        fingerLayoutAnchor = null
        val traceBefore = if (ResizeTrace.active) rowsDesc() else null
        super.onLayout(changed, l, t, r, b)
        if (traceBefore != null) {
            ResizeTrace.note("onLayout h=$height before{$traceBefore} after{${rowsDesc()}} fingerAnchor=$anchor")
        }
        resizeHoldReleasedForLayout = false
        if (anchor == null || manager == null) return
        val row = manager.findViewByPosition(anchor.first) ?: return
        // LinearLayoutManager fills a bottom gap when the keyboard closes,
        // even with an explicit top anchor. Undo that correction by the
        // measured absolute position before drawing. scrollBy would clamp at
        // the end and leave the unwanted movement in place.
        val correction = paddingTop + anchor.second - row.top
        if (correction != 0) {
            ResizeTrace.movement("onLayout finger correction offsetChildrenVertical($correction) on #${anchor.first}")
            manager.offsetChildrenVertical(correction)
            // Lifting a finger does not give a later adapter update permission
            // to fill this gap and move the reading position after all.
            retainPositionAfterTouch = true
        }
    }
}
