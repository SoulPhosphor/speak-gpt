/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import kotlin.math.min

/** Scrolls a growing response only while both its bottom is clipped and its
 * top still has room to move. Once the bubble reaches the viewport top, auto
 * scrolling stops instead of chasing the response's ever-growing bottom. */
object StreamingBubbleScrollPolicy {
    fun distance(
        itemTop: Int,
        itemBottom: Int,
        viewportTop: Int,
        viewportBottom: Int
    ): Int {
        val clippedBottom = (itemBottom - viewportBottom).coerceAtLeast(0)
        val roomAbove = (itemTop - viewportTop).coerceAtLeast(0)
        return min(clippedBottom, roomAbove)
    }
}
