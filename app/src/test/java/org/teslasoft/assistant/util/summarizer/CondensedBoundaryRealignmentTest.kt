/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Test

class CondensedBoundaryRealignmentTest {

    private fun after(boundary: Int, start: Int, end: Int) =
        CondensedBoundaryRealignment.afterRangeRemoval(boundary, start, end)

    @Test
    fun removingOneCondensedMessageShrinksTheBoundaryByOne() {
        assertEquals(29, after(boundary = 30, start = 5, end = 6))
    }

    @Test
    fun removingMessagesAfterTheBoundaryLeavesItAlone() {
        assertEquals(30, after(boundary = 30, start = 30, end = 31))
        assertEquals(30, after(boundary = 30, start = 40, end = 55))
    }

    @Test
    fun removingATailThatStartsInsideTheBoundaryEndsItAtTheCut() {
        // "Delete this and all following" from message 20 of 50, bookmark 30.
        assertEquals(20, after(boundary = 30, start = 20, end = 50))
    }

    @Test
    fun removingARangeStraddlingTheBoundaryCountsOnlyCoveredMessages() {
        assertEquals(25, after(boundary = 30, start = 25, end = 35))
    }

    @Test
    fun anEmptyBoundaryOrEmptyRangeIsUnchanged() {
        assertEquals(0, after(boundary = 0, start = 0, end = 10))
        assertEquals(30, after(boundary = 30, start = 10, end = 10))
    }
}
