/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.teslasoft.assistant.R
import kotlin.math.abs

/**
 * Finds a message row's bubble area in the transcript's coordinates. The
 * bubble area exists whether or not the user shows bubbles, so Selection
 * Mode uses it either way.
 */
internal object MessageBubbleBounds {
    fun of(transcript: RecyclerView, row: View, out: Rect): Boolean {
        val bubble = row.findViewById<View>(R.id.bubble_bg) ?: return false
        if (!bubble.isShown || bubble.width == 0 || bubble.height == 0) return false
        bubble.getDrawingRect(out)
        transcript.offsetDescendantRectToMyCoords(bubble, out)
        return true
    }
}

/**
 * In Selection Mode, a tap anywhere inside a message's bubble area toggles
 * that message, even on a link, picture or button inside it. Drags and
 * scrolls are left alone, and taps outside every bubble pass through.
 */
internal class MessageSelectionTapListener(
    context: Context,
    private val isActive: () -> Boolean,
    private val onBubbleTapped: (position: Int) -> Unit
) : RecyclerView.SimpleOnItemTouchListener() {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val bubble = Rect()
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!isActive()) {
            tracking = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                tracking = positionUnder(rv, e.x, e.y) != RecyclerView.NO_POSITION
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop)) {
                    tracking = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    tracking = false
                    val position = positionUnder(rv, e.x, e.y)
                    if (position != RecyclerView.NO_POSITION) {
                        // Taking the release cancels it for the view inside the
                        // bubble, so a link or button there does not also fire.
                        onBubbleTapped(position)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tracking = false
        }
        return false
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) tracking = false
    }

    private fun positionUnder(rv: RecyclerView, x: Float, y: Float): Int {
        val row = rv.findChildViewUnder(x, y) ?: return RecyclerView.NO_POSITION
        if (!MessageBubbleBounds.of(rv, row, bubble)) return RecyclerView.NO_POSITION
        if (!bubble.contains(x.toInt(), y.toInt())) return RecyclerView.NO_POSITION
        return rv.getChildAdapterPosition(row)
    }
}

/**
 * Draws the dashed selection outline around each selected message's bubble
 * area while Selection Mode is on. Its look comes from the shared
 * bg_chat_selection_outline drawable and its distance from the bubble from
 * chat_selection_outline_offset.
 */
internal class MessageSelectionOutline(
    context: Context,
    private val isActive: () -> Boolean,
    private val isSelected: (position: Int) -> Boolean
) : RecyclerView.ItemDecoration() {
    private val outline: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.bg_chat_selection_outline)
    private val offset =
        context.resources.getDimensionPixelSize(R.dimen.chat_selection_outline_offset)
    private val bubble = Rect()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val drawable = outline ?: return
        if (!isActive()) return
        for (i in 0 until parent.childCount) {
            val row = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(row)
            if (position == RecyclerView.NO_POSITION || !isSelected(position)) continue
            if (!MessageBubbleBounds.of(parent, row, bubble)) continue
            bubble.inset(-offset, -offset)
            bubble.offset(row.translationX.toInt(), row.translationY.toInt())
            drawable.bounds = bubble
            drawable.draw(c)
        }
    }
}
