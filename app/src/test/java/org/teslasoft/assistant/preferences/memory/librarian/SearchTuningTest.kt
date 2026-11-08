package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SearchTuning.normalized] applies the same bounds chats apply when reading
 * the stored policy, so the Memory Search Test screen never tests or saves a
 * combination that chats would read differently.
 */
class SearchTuningTest {

    @Test
    fun inRangeTuningIsUnchanged() {
        val t = SearchTuning()
        assertEquals(t, t.normalized())
    }

    @Test
    fun fullStrengthBelowTheMinimumIsRaisedAsChatsWouldRaiseIt() {
        val t = SearchTuning(params = LiveRetrieval.Params(similarityFloor = 0.5, fullStrengthSimilarity = 0.4))
        val n = t.normalized()
        assertEquals(0.51, n.params.fullStrengthSimilarity, 1e-9)
        assertEquals(0.5, n.params.similarityFloor, 1e-9)
    }

    @Test
    fun limitsOutsideTheirRangesAreClamped() {
        val n = SearchTuning(maxMemories = 500, memorySpace = 10).normalized()
        assertEquals(SearchTuning.MAX_MEMORIES_RANGE.last, n.maxMemories)
        assertEquals(SearchTuning.MEMORY_SPACE_RANGE.first, n.memorySpace)
    }
}
