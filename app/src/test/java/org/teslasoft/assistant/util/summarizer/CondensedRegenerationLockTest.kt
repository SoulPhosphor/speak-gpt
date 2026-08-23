package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CondensedRegenerationLockTest {
    @Test fun compactionWinsInsideItsPrefixAndSummaryOwnsLaterFoldedMessages() {
        assertEquals(
            CondensedRegenerationLock.Kind.COMPACTION,
            CondensedRegenerationLock.kindAt(2, summaryBoundary = 8, compactionBoundary = 5)
        )
        assertEquals(
            CondensedRegenerationLock.Kind.SUMMARY,
            CondensedRegenerationLock.kindAt(6, summaryBoundary = 8, compactionBoundary = 5)
        )
        assertNull(
            CondensedRegenerationLock.kindAt(8, summaryBoundary = 8, compactionBoundary = 5)
        )
    }

    @Test fun invalidOrEmptyBoundariesNeverLockAMessage() {
        assertNull(CondensedRegenerationLock.kindAt(-1, 10, 10))
        assertNull(CondensedRegenerationLock.kindAt(0, -1, -1))
    }
}
