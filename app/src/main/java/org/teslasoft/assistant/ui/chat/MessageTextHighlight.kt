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
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Magnifier
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.teslasoft.assistant.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The chat's own text highlighting for message text.
 *
 * It looks and behaves like Android's highlighting (long-press or
 * double-tap a word, drag the handles, Android's own handle images,
 * magnifier and Copy / Share / Select All pop-up) with one addition: while a
 * handle is held near the top or bottom edge of the chat, the chat keeps
 * scrolling and the highlight keeps growing under the finger. Android's
 * handles cannot do this because they cannot follow the finger once the
 * chat moves, so the chat draws its own.
 *
 * A highlight stays within one message's text. It is cleared by a tap, by
 * Back, by Copy or Share, or when the message's text changes.
 */
internal class MessageTextHighlight(private val context: Context) {

    companion object {
        /** Lets [text] start the chat's own highlighting instead of Android's. */
        fun prepare(text: TextView) {
            text.setOnLongClickListener { view ->
                controllerFor(view)?.onLongPress(view as TextView) ?: false
            }
        }

        private fun controllerFor(view: View): MessageTextHighlight? {
            var parent = view.parent
            while (parent is View) {
                (parent.getTag(R.id.tag_message_text_highlight) as? MessageTextHighlight)?.let { return it }
                parent = parent.parent
            }
            return null
        }

        /** Message text views that use the chat's own highlighting. */
        private val HIGHLIGHTABLE_IDS = intArrayOf(R.id.message, R.id.reasoning_text)

        /** Slowest auto-scroll, as a share of the fastest, just inside the edge area. */
        private const val SLOWEST_SCROLL_SHARE = 0.2f
    }

    private enum class Drag { NONE, WORD, START, END }

    private var transcript: RecyclerView? = null

    // The highlight.
    private var target: TextView? = null
    private var targetText: CharSequence? = null
    private var targetString = ""
    private var start = 0
    private var end = 0

    val isActive: Boolean get() = target != null

    // Dragging.
    private var drag = Drag.NONE
    private var anchorStart = 0
    private var anchorEnd = 0
    private var grabDx = 0f
    private var grabDy = 0f
    private var fingerX = 0f
    private var fingerY = 0f

    // The current touch gesture.
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var gestureStartedHighlight = false
    private var lastTapUpAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    private val edgeZone = context.resources.getDimension(R.dimen.chat_text_highlight_edge_zone)
    private val fastestScrollPerSecond =
        context.resources.getDimension(R.dimen.chat_text_highlight_scroll_speed_max)
    private val handleMinTouch =
        context.resources.getDimensionPixelSize(R.dimen.chat_text_highlight_handle_min_touch)

    // Drawing.
    private var startHandleImage: Drawable? = null
    private var endHandleImage: Drawable? = null
    private val startHandleTouch = Rect()
    private val endHandleTouch = Rect()
    private var startHandleShown = false
    private var endHandleShown = false
    private val highlightPath = Path()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val origin = IntArray(2)
    private val transcriptOrigin = IntArray(2)
    private var magnifier: Magnifier? = null

    // Auto-scroll.
    private var scrolling = false
    private var lastFrameAt = 0L
    private var pendingScroll = 0f
    private val scrollFrame = Runnable { onScrollFrame() }

    private val toolbar = MessageHighlightToolbar(object : MessageHighlightToolbar.Host {
        override fun onCopy() = copyHighlight()
        override fun onShare() = shareHighlight()
        override fun onSelectAll() = selectAll()
        override fun onProcessText(intent: Intent) = sendToApp(intent)
        override fun isAllSelected(): Boolean = start == 0 && end == targetString.length
        override fun getContentRect(out: Rect) = highlightBounds(out)
        override fun onClosed() = clear()
    })

    private val detachWatcher = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            if (view === target) clear()
        }
    }

    /** Hooks the highlighting into the chat's message list. */
    fun attach(list: RecyclerView) {
        if (transcript === list) return
        transcript = list
        list.setTag(R.id.tag_message_text_highlight, this)
        list.addOnItemTouchListener(touchListener)
        list.addItemDecoration(handleDecoration)
        list.addOnScrollListener(scrollListener)
    }

    /** Clears the highlight when a touch starts outside the message list. */
    fun onWindowTouchDown(rawX: Float, rawY: Float) {
        val list = transcript ?: return
        if (!isActive) return
        val bounds = Rect()
        if (!list.getGlobalVisibleRect(bounds) || !bounds.contains(rawX.toInt(), rawY.toInt())) {
            clear()
        }
    }

    fun clear() {
        val view = target ?: return
        stopAutoScroll()
        magnifier?.dismiss()
        magnifier = null
        drag = Drag.NONE
        target = null
        targetText = null
        targetString = ""
        toolbar.dismiss()
        view.removeOnAttachStateChangeListener(detachWatcher)
        (view as? PortraitAwareMessageTextView)?.highlightPainter = null
        view.invalidate()
        transcript?.invalidate()
    }

    // ---- Starting a highlight ----

    private fun onLongPress(view: TextView): Boolean {
        val list = transcript ?: return false
        if (!isDescendant(view, list)) return false
        if (!startWordHighlight(view, fingerX, fingerY)) return false
        gestureStartedHighlight = true
        return true
    }

    private fun startWordHighlight(view: TextView, x: Float, y: Float): Boolean {
        val text = view.text ?: return false
        if (text.isEmpty() || view.layout == null) return false
        val offset = offsetAt(view, x, y)
        if (offset < 0) return false
        val (wordStart, wordEnd) = MessageHighlightText.wordAt(text, offset) ?: return false
        show(view, wordStart, wordEnd)
        anchorStart = wordStart
        anchorEnd = wordEnd
        drag = Drag.WORD
        grabDx = 0f
        grabDy = 0f
        fingerX = x
        fingerY = y
        toolbar.hideWhileMoving()
        updateMagnifier()
        return true
    }

    private fun show(view: TextView, from: Int, to: Int) {
        if (target !== view) {
            clear()
            target = view
            view.addOnAttachStateChangeListener(detachWatcher)
            (view as? PortraitAwareMessageTextView)?.highlightPainter = ::paintHighlight
        }
        targetText = view.text
        targetString = view.text.toString()
        start = from
        end = to
        redraw()
    }

    // ---- Touch handling on the message list ----

    private val touchListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(list: RecyclerView, event: MotionEvent): Boolean {
            fingerX = event.x
            fingerY = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    gestureStartedHighlight = false
                    if (isActive) {
                        val handle = handleAt(event.x, event.y)
                        if (handle != Drag.NONE) {
                            beginHandleDrag(handle)
                            return true
                        }
                    }
                    if (isDoubleTap(event)) {
                        val text = highlightableTextAt(list, event.x, event.y)
                        if (text != null && startWordHighlight(text, event.x, event.y)) {
                            gestureStartedHighlight = true
                            lastTapUpAt = 0L
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > touchSlop) moved = true
                    // A long-press that started a highlight keeps extending it
                    // by whole words while the finger moves, as Android does.
                    if (gestureStartedHighlight && drag == Drag.WORD) {
                        list.parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (gestureStartedHighlight) {
                        finishDrag()
                        return false
                    }
                    if (!moved) {
                        if (isActive) {
                            // A tap while text is highlighted only clears it.
                            clear()
                            return true
                        }
                        lastTapUpAt = event.eventTime
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                }
                MotionEvent.ACTION_CANCEL -> finishDrag()
            }
            return false
        }

        override fun onTouchEvent(list: RecyclerView, event: MotionEvent) {
            fingerX = event.x
            fingerY = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (drag != Drag.NONE) {
                        updateDrag()
                        updateAutoScroll()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDrag()
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    private fun isDoubleTap(event: MotionEvent): Boolean =
        lastTapUpAt > 0L &&
            event.eventTime - lastTapUpAt <= ViewConfiguration.getDoubleTapTimeout() &&
            hypot(event.x - lastTapX, event.y - lastTapY) <= doubleTapSlop

    private fun highlightableTextAt(list: RecyclerView, x: Float, y: Float): TextView? {
        val row = list.findChildViewUnder(x, y) ?: return null
        for (id in HIGHLIGHTABLE_IDS) {
            val text = row.findViewById<TextView>(id) ?: continue
            if (!text.isShown || text.text.isNullOrEmpty()) continue
            originOf(text)
            if (x >= origin[0] && x < origin[0] + text.width && y >= origin[1] && y < origin[1] + text.height) {
                return text
            }
        }
        return null
    }

    // ---- Dragging ----

    private fun beginHandleDrag(handle: Drag) {
        val view = target ?: return
        val layout = view.layout ?: return
        drag = handle
        val offset = if (handle == Drag.START) start else end
        val line = handleLine(layout, offset, handle == Drag.END)
        originOf(view)
        val pointX = origin[0] + view.totalPaddingLeft + handleX(layout, offset, line, handle == Drag.END)
        val pointY = origin[1] + view.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        grabDx = fingerX - pointX
        grabDy = fingerY - pointY
        transcript?.parent?.requestDisallowInterceptTouchEvent(true)
        toolbar.hideWhileMoving()
        updateMagnifier()
    }

    private fun updateDrag() {
        val view = target ?: return
        if (!textStillMatches(fromDraw = false)) return
        val text = view.text ?: return
        val length = text.length
        if (length == 0) return
        val offset = offsetAt(view, fingerX - grabDx, fingerY - grabDy)
        if (offset < 0) return

        val oldStart = start
        val oldEnd = end
        when (drag) {
            Drag.WORD -> {
                when {
                    offset < anchorStart -> {
                        start = MessageHighlightText.startOfWordAt(text, offset)
                        end = anchorEnd
                    }
                    offset > anchorEnd -> {
                        start = anchorStart
                        end = MessageHighlightText.endOfWordAt(text, offset)
                    }
                    else -> {
                        start = anchorStart
                        end = anchorEnd
                    }
                }
            }
            Drag.END -> {
                val grown = if (offset > end) MessageHighlightText.endOfWordAt(text, offset) else offset
                end = grown.coerceIn(start + 1, length)
            }
            Drag.START -> {
                val grown = if (offset < start) MessageHighlightText.startOfWordAt(text, offset) else offset
                start = grown.coerceIn(0, end - 1)
            }
            Drag.NONE -> return
        }
        if (start != oldStart || end != oldEnd) {
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            redraw()
        }
        toolbar.hideWhileMoving()
        updateMagnifier()
    }

    private fun finishDrag() {
        if (drag == Drag.NONE) return
        drag = Drag.NONE
        stopAutoScroll()
        magnifier?.dismiss()
        target?.let { toolbar.show(it) }
    }

    // ---- Auto-scroll at the top and bottom edges ----

    private fun updateAutoScroll() {
        val list = transcript ?: return
        if (drag == Drag.NONE || edgeDirection(list) == 0) {
            stopAutoScroll()
            return
        }
        if (scrolling) return
        scrolling = true
        lastFrameAt = SystemClock.uptimeMillis()
        pendingScroll = 0f
        list.postOnAnimation(scrollFrame)
    }

    private fun stopAutoScroll() {
        if (!scrolling) return
        scrolling = false
        transcript?.removeCallbacks(scrollFrame)
    }

    private fun onScrollFrame() {
        if (!scrolling) return
        val list = transcript
        val view = target
        if (list == null || view == null || drag == Drag.NONE) {
            stopAutoScroll()
            return
        }
        val direction = edgeDirection(list)
        if (direction == 0 || !list.canScrollVertically(direction) || !textContinuesPastEdge(list, view, direction)) {
            stopAutoScroll()
            return
        }

        val now = SystemClock.uptimeMillis()
        val depth = edgeDepth(list, direction)
        val speed = fastestScrollPerSecond * (SLOWEST_SCROLL_SHARE + (1f - SLOWEST_SCROLL_SHARE) * depth)
        pendingScroll += speed * (now - lastFrameAt) / 1000f
        lastFrameAt = now
        val step = pendingScroll.toInt()
        pendingScroll -= step
        if (step > 0) {
            list.scrollBy(0, direction * step)
            // The finger has not moved but the text under it has: extend the
            // highlight to the text now under the finger.
            updateDrag()
        }
        list.postOnAnimation(scrollFrame)
    }

    private fun edgeDirection(list: RecyclerView): Int = when {
        fingerY >= list.height - edgeZone -> 1
        fingerY <= edgeZone -> -1
        else -> 0
    }

    private fun edgeDepth(list: RecyclerView, direction: Int): Float {
        val into = if (direction > 0) fingerY - (list.height - edgeZone) else edgeZone - fingerY
        return (into / edgeZone).coerceIn(0f, 1f)
    }

    /** Whether more of the highlighted message lies beyond the edge being scrolled toward. */
    private fun textContinuesPastEdge(list: RecyclerView, view: TextView, direction: Int): Boolean {
        originOf(view)
        return if (direction > 0) {
            origin[1] + view.height - view.totalPaddingBottom > list.height - edgeZone
        } else {
            origin[1] + view.totalPaddingTop < edgeZone
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (isActive && drag == Drag.NONE && dy != 0) toolbar.hideWhileMoving()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE && drag == Drag.NONE) {
                target?.let { if (toolbar.isShown) toolbar.show(it) }
            }
        }
    }

    // ---- Pop-up actions ----

    private fun highlightedText(): CharSequence? {
        val view = target ?: return null
        val text = view.text ?: return null
        return MessageHighlightText.copyText(text, start, end)
    }

    private fun copyHighlight() {
        val text = highlightedText() ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        clear()
    }

    private fun shareHighlight() {
        val text = highlightedText()?.toString() ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        clear()
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun selectAll() {
        val view = target ?: return
        start = 0
        end = view.text?.length ?: 0
        redraw()
        toolbar.refresh()
        toolbar.show(view)
    }

    private fun sendToApp(intent: Intent) {
        val text = highlightedText()?.toString() ?: return
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        clear()
        try {
            context.startActivity(intent)
        } catch (_: RuntimeException) {
            // The other app is no longer available; nothing else to do.
        }
    }

    /** The highlighted lines, within the part of the message that is on screen. */
    private fun highlightBounds(out: Rect) {
        val view = target
        val layout = view?.layout
        val list = transcript
        if (view == null || layout == null || list == null) {
            out.setEmpty()
            return
        }
        val firstLine = layout.getLineForOffset(start)
        val lastLine = handleLine(layout, end, true)
        val handleHeight = (endHandleImage?.intrinsicHeight ?: 0)
        val left: Int
        val right: Int
        if (firstLine == lastLine) {
            val a = layout.getPrimaryHorizontal(start)
            val b = handleX(layout, end, lastLine, true)
            left = minOf(a, b).toInt()
            right = max(a, b).toInt()
        } else {
            left = 0
            right = layout.width
        }
        out.set(
            view.totalPaddingLeft + left,
            view.totalPaddingTop + layout.getLineTop(firstLine),
            view.totalPaddingLeft + right,
            view.totalPaddingTop + layout.getLineBottom(lastLine) + handleHeight
        )
        // Keep the pop-up beside the part of the highlight that can be seen.
        originOf(view)
        val visibleTop = -origin[1]
        val visibleBottom = list.height - origin[1]
        if (out.top < visibleTop) out.top = visibleTop
        if (out.bottom > visibleBottom) out.bottom = visibleBottom
        if (out.bottom < out.top) out.bottom = out.top
    }

    // ---- Drawing ----

    /** Draws the highlight color behind the message text (called from the text view). */
    private fun paintHighlight(canvas: Canvas) {
        val view = target ?: return
        if (!textStillMatches(fromDraw = true)) return
        val layout = view.layout ?: return
        val length = view.text?.length ?: return
        highlightPath.reset()
        layout.getSelectionPath(start.coerceIn(0, length), end.coerceIn(0, length), highlightPath)
        highlightPaint.color = view.highlightColor
        val save = canvas.save()
        canvas.translate(view.totalPaddingLeft.toFloat(), view.totalPaddingTop.toFloat())
        canvas.drawPath(highlightPath, highlightPaint)
        canvas.restoreToCount(save)
    }

    private val handleDecoration = object : RecyclerView.ItemDecoration() {
        override fun onDrawOver(canvas: Canvas, list: RecyclerView, state: RecyclerView.State) {
            val view = target
            if (view == null || !textStillMatches(fromDraw = true)) {
                startHandleShown = false
                endHandleShown = false
                return
            }
            val layout = view.layout ?: return
            loadHandleImages(view)
            startHandleShown = drawHandle(canvas, list, view, layout, start, isEnd = false, startHandleTouch)
            endHandleShown = drawHandle(canvas, list, view, layout, end, isEnd = true, endHandleTouch)
        }
    }

    private fun drawHandle(
        canvas: Canvas,
        list: RecyclerView,
        view: TextView,
        layout: Layout,
        offset: Int,
        isEnd: Boolean,
        touchArea: Rect
    ): Boolean {
        val line = handleLine(layout, offset, isEnd)
        originOf(view)
        val lineTop = origin[1] + view.totalPaddingTop + layout.getLineTop(line)
        val lineBottom = origin[1] + view.totalPaddingTop + lineBottomWithoutSpacing(layout, line)
        if (lineBottom <= 0 || lineTop >= list.height) return false

        val rtl = isRtlAt(layout, offset, isEnd)
        // As in Android: the start handle points left (right in right-to-left
        // text) and the end handle points the other way.
        val pointsLeft = if (isEnd) rtl else !rtl
        val image = (if (pointsLeft) startHandleImage else endHandleImage) ?: return false
        val width = image.intrinsicWidth
        val height = image.intrinsicHeight
        val hotspot = if (pointsLeft) width * 3 / 4 else width / 4
        val x = origin[0] + view.totalPaddingLeft + handleX(layout, offset, line, isEnd).toInt()
        val left = x - hotspot
        image.setBounds(left, lineBottom, left + width, lineBottom + height)
        image.draw(canvas)

        val touchWidth = max(width, handleMinTouch)
        val touchHeight = max(height, handleMinTouch)
        val centerX = left + width / 2
        touchArea.set(centerX - touchWidth / 2, lineBottom, centerX + touchWidth / 2, lineBottom + touchHeight)
        return true
    }

    private fun handleAt(x: Float, y: Float): Drag {
        val onStart = startHandleShown && startHandleTouch.contains(x.toInt(), y.toInt())
        val onEnd = endHandleShown && endHandleTouch.contains(x.toInt(), y.toInt())
        return when {
            onStart && onEnd -> {
                val toStart = abs(x - startHandleTouch.exactCenterX()) + abs(y - startHandleTouch.exactCenterY())
                val toEnd = abs(x - endHandleTouch.exactCenterX()) + abs(y - endHandleTouch.exactCenterY())
                if (toStart < toEnd) Drag.START else Drag.END
            }
            onStart -> Drag.START
            onEnd -> Drag.END
            else -> Drag.NONE
        }
    }

    /** Android's own handle images, from the theme, as its highlighting uses. */
    private fun loadHandleImages(view: TextView) {
        if (startHandleImage != null && endHandleImage != null) return
        if (Build.VERSION.SDK_INT >= 29) {
            startHandleImage = view.textSelectHandleLeft?.mutate()
            endHandleImage = view.textSelectHandleRight?.mutate()
        } else {
            val attrs = view.context.obtainStyledAttributes(
                intArrayOf(android.R.attr.textSelectHandleLeft, android.R.attr.textSelectHandleRight)
            )
            try {
                startHandleImage = attrs.getDrawable(0)?.mutate()
                endHandleImage = attrs.getDrawable(1)?.mutate()
            } finally {
                attrs.recycle()
            }
        }
    }

    private fun updateMagnifier() {
        val view = target ?: return
        val layout = view.layout ?: return
        if (drag == Drag.NONE) return
        val offset = when (drag) {
            Drag.START -> start
            Drag.END -> end
            else -> if (end > anchorEnd) end else start
        }
        val line = handleLine(layout, offset, offset == end)
        val lens = magnifier ?: createMagnifier(view).also { magnifier = it }
        originOf(view)
        val x = (fingerX - grabDx - origin[0]).coerceIn(0f, view.width.toFloat())
        val y = view.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        lens.show(x, y)
    }

    private fun createMagnifier(view: TextView): Magnifier =
        if (Build.VERSION.SDK_INT >= 29) {
            Magnifier.Builder(view).build()
        } else {
            @Suppress("DEPRECATION")
            Magnifier(view)
        }

    // ---- Geometry ----

    /** An end edge at the start of a wrapped line belongs to the line before it. */
    private fun handleLine(layout: Layout, offset: Int, isEnd: Boolean): Int {
        val line = layout.getLineForOffset(offset)
        return if (isEnd && line > 0 && offset == layout.getLineStart(line)) line - 1 else line
    }

    private fun handleX(layout: Layout, offset: Int, line: Int, isEnd: Boolean): Float {
        if (isEnd && line != layout.getLineForOffset(offset)) {
            return if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
                layout.getLineLeft(line)
            } else {
                layout.getLineRight(line)
            }
        }
        return layout.getPrimaryHorizontal(offset)
    }

    private fun lineBottomWithoutSpacing(layout: Layout, line: Int): Int =
        if (Build.VERSION.SDK_INT >= 33) layout.getLineBottom(line, false) else layout.getLineBottom(line)

    private fun isRtlAt(layout: Layout, offset: Int, isEnd: Boolean): Boolean {
        val length = layout.text.length
        if (length == 0) return false
        val char = if (isEnd) (offset - 1).coerceIn(0, length - 1) else offset.coerceIn(0, length - 1)
        return layout.isRtlCharAt(char)
    }

    /** [view]'s top-left corner in the message list's coordinates, into [origin]. */
    private fun originOf(view: View) {
        view.getLocationInWindow(origin)
        transcript?.getLocationInWindow(transcriptOrigin)
        origin[0] -= transcriptOrigin[0]
        origin[1] -= transcriptOrigin[1]
    }

    private fun offsetAt(view: TextView, listX: Float, listY: Float): Int {
        if (view.layout == null) return -1
        originOf(view)
        return view.getOffsetForPosition(listX - origin[0], listY - origin[1])
    }

    /**
     * The highlight belongs to the text it was made on. If the message text
     * is replaced by different text, the highlight is cleared.
     */
    private fun textStillMatches(fromDraw: Boolean): Boolean {
        val view = target ?: return false
        val now = view.text
        if (now === targetText) return true
        if (now != null && now.toString() == targetString) {
            targetText = now
            return true
        }
        if (fromDraw) view.post { if (target === view && view.text !== targetText) clear() } else clear()
        return false
    }

    private fun redraw() {
        target?.invalidate()
        transcript?.invalidate()
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }
}
