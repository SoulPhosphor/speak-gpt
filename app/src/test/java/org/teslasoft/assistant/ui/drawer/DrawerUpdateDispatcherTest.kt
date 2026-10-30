package org.teslasoft.assistant.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerUpdateDispatcherTest {
    @Test fun updatesRunImmediatelyWhileStationary() {
        val dispatcher = DrawerUpdateDispatcher()
        var applied = false
        dispatcher.dispatch { applied = true }
        assertEquals(true, applied)
    }

    @Test fun openingAndClosingHoldUpdatesUntilTheDrawerStops() {
        for (motion in listOf("opening", "closing")) {
            val dispatcher = DrawerUpdateDispatcher()
            val applied = mutableListOf<String>()
            dispatcher.setMoving(true)
            dispatcher.dispatch { applied.add("projection") }
            dispatcher.dispatch { applied.add("details") }
            // Dragging can turn into settling without an idle frame.
            dispatcher.setMoving(true)
            assertEquals(motion, emptyList<String>(), applied)
            dispatcher.setMoving(false)
            assertEquals(motion, listOf("projection", "details"), applied)
            dispatcher.setMoving(false)
            assertEquals("updates must run only once", 2, applied.size)
        }
    }

    @Test fun aNewMotionDuringAnUpdateKeepsRemainingUpdatesDeferred() {
        val dispatcher = DrawerUpdateDispatcher()
        val applied = mutableListOf<Int>()
        dispatcher.setMoving(true)
        dispatcher.dispatch {
            applied.add(1)
            dispatcher.setMoving(true)
        }
        dispatcher.dispatch { applied.add(2) }
        dispatcher.setMoving(false)
        assertEquals(listOf(1), applied)
        dispatcher.setMoving(false)
        assertEquals(listOf(1, 2), applied)
    }
}
