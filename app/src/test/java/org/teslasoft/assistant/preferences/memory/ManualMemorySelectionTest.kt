package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.*
import org.junit.Test

class ManualMemorySelectionTest {
    @Test fun selectionCapsAtFiveAndRemovingOneFreesItsSlot() {
        val selection = ManualMemorySelection()
        (1..5).forEach { assertTrue(selection.toggle("m$it")) }
        assertFalse(selection.toggle("sixth"))
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), selection.ids())
        assertTrue(selection.toggle("m2"))
        assertTrue(selection.toggle("sixth"))
        assertEquals(listOf("m1", "m3", "m4", "m5", "sixth"), selection.ids())
    }
    @Test fun restoredSelectionDeduplicatesAndKeepsOrderAndFiveMemoryLimit() {
        val selection = ManualMemorySelection(listOf("", "a", "a", "b", "c", "d", "e", "f"))
        assertEquals(listOf("a", "b", "c", "d", "e"), selection.ids())
    }
    @Test fun capturedSendSnapshotSurvivesLaterSelectionChanges() {
        val selection = ManualMemorySelection(listOf("a", "b"))
        val snapshot = selection.ids()
        selection.toggle("a")
        selection.toggle("c")
        assertEquals(listOf("a", "b"), snapshot)
        assertEquals(listOf("b", "c"), selection.ids())
    }
}
