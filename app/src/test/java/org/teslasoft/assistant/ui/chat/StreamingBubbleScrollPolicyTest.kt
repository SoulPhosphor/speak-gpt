/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingBubbleScrollPolicyTest {

    @Test
    fun followsClippedBottomWhileBubbleTopHasRoom() {
        assertEquals(
            40,
            StreamingBubbleScrollPolicy.distance(
                itemTop = 120,
                itemBottom = 640,
                viewportTop = 0,
                viewportBottom = 600
            )
        )
    }

    @Test
    fun stopsExactlyAtViewportTopForLongResponse() {
        assertEquals(
            30,
            StreamingBubbleScrollPolicy.distance(
                itemTop = 30,
                itemBottom = 900,
                viewportTop = 0,
                viewportBottom = 600
            )
        )
        assertEquals(
            0,
            StreamingBubbleScrollPolicy.distance(
                itemTop = 0,
                itemBottom = 1200,
                viewportTop = 0,
                viewportBottom = 600
            )
        )
    }

    @Test
    fun neverOverridesAUserWhoPulledBubbleBelowOrAboveTheCap() {
        assertEquals(
            0,
            StreamingBubbleScrollPolicy.distance(
                itemTop = -80,
                itemBottom = 850,
                viewportTop = 0,
                viewportBottom = 600
            )
        )
    }
}
