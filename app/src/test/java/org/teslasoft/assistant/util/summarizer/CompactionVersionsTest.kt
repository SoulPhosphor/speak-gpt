/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionVersionsTest {

    private fun v(count: Int, text: String, baseCount: Int = 0, baseText: String = "") =
        CompactionVersions.Version(count, text, baseCount, baseText)

    @Test
    fun aChatCompactedBeforeTabsGetsItsTextAsOneTab() {
        val state = CompactionVersions.current("", "Old text", 5)
        assertEquals(listOf(v(5, "Old text")), state.versions)
        assertEquals(0, state.activeIndex)
    }

    @Test
    fun nothingCompactedMeansNoTabs() {
        assertTrue(CompactionVersions.current("", "", 0).versions.isEmpty())
    }

    @Test
    fun theVersionInUseCarriesTheSavedEditedText() {
        val stored = CompactionVersions.toJson(CompactionVersions.State(listOf(v(5, "A"), v(15, "A\n\nB")), 1))
        val state = CompactionVersions.current(stored, "A\n\nB edited", 15)
        assertEquals("A\n\nB edited", state.versions[1].text)
        assertEquals("A", state.versions[0].text)
    }

    @Test
    fun eachCompactionAddsOneTabUpToFour() {
        var state = CompactionVersions.EMPTY
        for (n in 1..5) state = CompactionVersions.afterCompaction(state, v(n * 5, "t$n"))
        assertEquals(CompactionVersions.MAX_VERSIONS, state.versions.size)
        assertEquals(listOf(10, 15, 20, 25), state.versions.map { it.count })
        assertEquals(3, state.activeIndex)
    }

    @Test
    fun useThisSummaryMarksNewerTabsUnusedAndTheyCanBePutBack() {
        val three = CompactionVersions.State(listOf(v(5, "a"), v(15, "b"), v(20, "c")), 2)
        val back = CompactionVersions.use(three, 0)
        assertEquals(0, back.activeIndex)
        assertTrue(back.isUnused(1))
        assertTrue(back.isUnused(2))
        assertFalse(back.isUnused(0))
        val forward = CompactionVersions.use(back, 2)
        assertFalse(forward.isUnused(1))
        assertEquals(3, forward.versions.size)
    }

    @Test
    fun compactingAfterGoingBackDeletesTheUnusedTabs() {
        val back = CompactionVersions.State(listOf(v(5, "a"), v(15, "b"), v(20, "c")), 0)
        val next = CompactionVersions.afterCompaction(back, v(12, "a\n\nd", 5, "a"))
        assertEquals(listOf(5, 12), next.versions.map { it.count })
        assertEquals(1, next.activeIndex)
    }

    @Test
    fun recompactFromScratchLeavesExactlyOneTab() {
        val next = CompactionVersions.afterRecompactFromScratch(v(20, "fresh"))
        assertEquals(listOf(v(20, "fresh")), next.versions)
        assertEquals(0, next.activeIndex)
    }

    @Test
    fun redoReplacesOnlyTheNewestTabAndOnlyWhileItIsInUse() {
        val state = CompactionVersions.State(listOf(v(5, "a"), v(15, "a\n\nb", 5, "a")), 1)
        assertTrue(CompactionVersions.canRedo(state, 1))
        assertFalse(CompactionVersions.canRedo(state, 0))
        assertFalse(CompactionVersions.canRedo(CompactionVersions.use(state, 0), 1))
        val redone = CompactionVersions.afterRedoNewest(state, v(15, "a\n\nbetter b", 5, "a"))
        assertEquals(listOf("a", "a\n\nbetter b"), redone.versions.map { it.text })
        assertEquals(1, redone.activeIndex)
    }

    @Test
    fun deletingMessagesShiftsEveryTabLikeTheMarker() {
        val state = CompactionVersions.State(listOf(v(5, "a"), v(15, "b", 5, "a")), 1)
        val after = CompactionVersions.afterRangeRemoval(state, 2, 4)
        assertEquals(listOf(3, 13), after.versions.map { it.count })
        assertEquals(3, after.versions[1].baseCount)
    }

    @Test
    fun storageRoundTrips() {
        val state = CompactionVersions.State(listOf(v(5, "a"), v(15, "b", 5, "a")), 0)
        assertEquals(state, CompactionVersions.fromJson(CompactionVersions.toJson(state)))
        assertEquals(CompactionVersions.EMPTY, CompactionVersions.fromJson("not json"))
    }
}
