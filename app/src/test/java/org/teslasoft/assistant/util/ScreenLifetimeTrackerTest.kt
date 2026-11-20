package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLifetimeTrackerTest {

    private val tracker = ScreenLifetimeTracker()

    @Test fun openScreenCountsAsOpen() {
        val screen = Any()
        tracker.created(screen)
        assertEquals(1 to 0, tracker.counts())
    }

    @Test fun destroyedScreenStillInMemoryCountsAsClosed() {
        val screen = Any()
        tracker.created(screen)
        tracker.destroyed(screen)
        assertEquals(0 to 1, tracker.counts())
    }

    @Test fun recreatedScreenCountsBothInstances() {
        // A system recreation: the old instance is destroyed, a new one opens.
        val old = Any()
        val new = Any()
        tracker.created(old)
        tracker.destroyed(old)
        tracker.created(new)
        assertEquals(1 to 1, tracker.counts())
    }

    @Test fun destroyingAnUntrackedScreenIsIgnored() {
        tracker.destroyed(Any())
        assertEquals(0 to 0, tracker.counts())
    }
}
