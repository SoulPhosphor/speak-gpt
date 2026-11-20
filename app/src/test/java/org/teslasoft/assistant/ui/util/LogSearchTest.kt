package org.teslasoft.assistant.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSearchTest {

    private val entries = listOf(
        "[2026-11-19 4:15 PM] [ProcessExit] [WARNING] Exit: low memory",
        "[2026-11-19 4:16 PM] [Voice] [INFO] mic opened",
        "[2026-11-19 4:17 PM] [GenError] [ERROR] Low Memory again"
    )

    @Test fun blankQueryShowsEveryEntry() {
        assertEquals(listOf(0, 1, 2), LogSearch.matchingIndices(entries, ""))
        assertEquals(listOf(0, 1, 2), LogSearch.matchingIndices(entries, "   "))
    }

    @Test fun matchesPartialTextIgnoringCase() {
        assertEquals(listOf(0, 2), LogSearch.matchingIndices(entries, "low mem"))
        assertEquals(listOf(1), LogSearch.matchingIndices(entries, "MIC"))
        assertEquals(emptyList<Int>(), LogSearch.matchingIndices(entries, "nothing here"))
    }
}
