/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView

/**
 * Keeps a typing page's content in the same place relative to the bottom of
 * the visible area while the keyboard opens or closes, the way the chat
 * screen holds the conversation against the message box. Whatever sat just
 * above the bottom edge rises with the keyboard and stays just above it, and
 * comes back down when the keyboard closes. The page is not moved to the
 * cursor, except that it never rises so far that the place being typed in
 * leaves the top of the view. A page that still fits above the keyboard
 * does not move.
 *
 * [ScreenChrome.fitSystemBarsAndKeyboard] calls [capture] just before it
 * changes the page's bottom space for the keyboard; the next layout of the
 * scrolling area then restores the held position.
 */
internal class KeyboardPositionHold private constructor(
    private val scroll: View
) : View.OnLayoutChangeListener {

    /** Bottom part of the scrolling area currently behind the navigation bar. */
    var hiddenBottom = 0

    private var heldBottom: Int? = null
    private var smoothScrollingWas = true

    init {
        scroll.addOnLayoutChangeListener(this)
    }

    fun capture() {
        if (heldBottom != null) return
        heldBottom = visibleBottom(scroll.scrollY, scroll.height, hiddenBottom)
        // ScrollView answers its own resize by animating the focused field
        // into view; an animation would run after, and undo, the hold.
        smoothScrollingWas = setSmoothScrolling(false)
    }

    override fun onLayoutChange(
        v: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
    ) {
        val held = heldBottom ?: return
        heldBottom = null
        val visibleHeight = scroll.height - hiddenBottom
        var target = heldScrollY(held, scroll.height, hiddenBottom)
        // The focused field's cursor (its whole bounds for other views) stays
        // in view; Android's own correction cannot do this here because the
        // hold replaces the position it chose.
        val focused = scroll.findFocus()
        if (focused != null && focused !== scroll && scroll is ViewGroup) {
            val cursor = Rect()
            focused.getFocusedRect(cursor)
            scroll.offsetDescendantRectToMyCoords(focused, cursor)
            target = keepInView(target, cursor.top, cursor.bottom, visibleHeight)
        }
        // scrollTo clamps to the page's scroll range, so a page that fits
        // moves only as far as it has to.
        scroll.scrollTo(scroll.scrollX, target)
        setSmoothScrolling(smoothScrollingWas)
    }

    private fun setSmoothScrolling(enabled: Boolean): Boolean = when (scroll) {
        is ScrollView -> scroll.isSmoothScrollingEnabled.also { scroll.isSmoothScrollingEnabled = enabled }
        is NestedScrollView -> scroll.isSmoothScrollingEnabled.also { scroll.isSmoothScrollingEnabled = enabled }
        else -> true
    }

    companion object {
        /** Only scroll views hold an absolute position; other areas are left as they were. */
        fun attachTo(content: View?): KeyboardPositionHold? =
            if (content is ScrollView || content is NestedScrollView) KeyboardPositionHold(content) else null

        /** Content position at the bottom edge of the uncovered part of the area. */
        fun visibleBottom(scrollY: Int, height: Int, hiddenBottom: Int): Int =
            scrollY + height - hiddenBottom

        /** Scroll position that puts [visibleBottom] back at the uncovered bottom edge. */
        fun heldScrollY(visibleBottom: Int, height: Int, hiddenBottom: Int): Int =
            visibleBottom - (height - hiddenBottom)

        /** Smallest change to [scrollY] that keeps [top]..[bottom] within a
         *  [visibleHeight] view; the top wins when both cannot fit. */
        fun keepInView(scrollY: Int, top: Int, bottom: Int, visibleHeight: Int): Int = when {
            top < scrollY -> top
            bottom > scrollY + visibleHeight -> minOf(top, bottom - visibleHeight)
            else -> scrollY
        }
    }
}
