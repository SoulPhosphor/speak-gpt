/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitExclusionGeometryTest {

    @Test
    fun portraitSizeChangesTheMeasuredClearance() {
        val small = PortraitExclusionGeometry.textFlow(
            portraitNearEdge = 110,
            contentEdge = 40,
            portraitBottom = 72,
            contentTop = 20,
            gapPx = 9,
            lineHeightPx = 20
        )
        val large = PortraitExclusionGeometry.textFlow(
            portraitNearEdge = 150,
            contentEdge = 40,
            portraitBottom = 112,
            contentTop = 20,
            gapPx = 9,
            lineHeightPx = 20
        )

        assertEquals(79, small.leadingMarginPx)
        assertEquals(3, small.lineCount)
        assertEquals(119, large.leadingMarginPx)
        assertEquals(5, large.lineCount)
    }

    @Test
    fun expandedThinkingCanMoveTheAnswerPastThePortrait() {
        val collapsed = PortraitExclusionGeometry.textFlow(
            portraitNearEdge = 120,
            contentEdge = 40,
            portraitBottom = 96,
            contentTop = 54,
            gapPx = 9,
            lineHeightPx = 21
        )
        val expanded = PortraitExclusionGeometry.textFlow(
            portraitNearEdge = 120,
            contentEdge = 40,
            portraitBottom = 96,
            contentTop = 180,
            gapPx = 9,
            lineHeightPx = 21
        )

        assertEquals(2, collapsed.lineCount)
        assertEquals(0, expanded.lineCount)
        assertEquals(0, expanded.leadingMarginPx)
    }

    @Test
    fun blockIntersectionUsesItsRealVerticalBounds() {
        assertTrue(PortraitExclusionGeometry.overlaps(0, 96, 80, 112))
        assertFalse(PortraitExclusionGeometry.overlaps(0, 96, 96, 128))
    }

    @Test
    fun horizontalClearanceFollowsTheRenderedPortraitSide() {
        val left = PortraitExclusionGeometry.horizontalExclusion(
            portraitLeft = 20,
            portraitRight = 116,
            contentLeft = 42,
            contentRight = 342,
            gapPx = 9
        )
        val right = PortraitExclusionGeometry.horizontalExclusion(
            portraitLeft = 284,
            portraitRight = 380,
            contentLeft = 58,
            contentRight = 358,
            gapPx = 9
        )

        assertTrue(left.portraitOnLeft)
        assertEquals(83, left.leadingMarginPx)
        assertFalse(right.portraitOnLeft)
        assertEquals(83, right.leadingMarginPx)
    }
}
