package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import kotlin.math.sqrt

/** New cases written after parameter selection, without retuning. Synthetic
 * vectors exercise the approved contract; they do not measure model accuracy. */
class Step2IndependentRegressionTest {
    private val topic = floatArrayOf(1f, 0f, 0f)
    private val other = floatArrayOf(0f, 1f, 0f)
    private fun candidate(id: String, text: String, similarity: Double) = LiveRetrieval.Candidate(
        RetrievableMemory(id, "global", text, null, 0, "2026-01-01T00:00:00Z", null),
        floatArrayOf(similarity.toFloat(), 0f, sqrt(1 - similarity * similarity).toFloat()), emptyList(), emptyList())
    private val filler = "then I sorted the papers near the window before going downstairs to collect the parcel and later I planned what to cook while the weather changed outside"
    private fun message(at: Int, detail: String): String {
        val words = List(8) { filler }.joinToString(" ").split(' ').toMutableList()
        words.add(at.coerceAtMost(words.size), detail)
        return words.joinToString(" ")
    }
    private fun search(message: String, memory: LiveRetrieval.Candidate, params: LiveRetrieval.Params = LiveRetrieval.Params()): SavedMemorySearch.Result {
        val index = LiveRetrieval.index(listOf(memory))
        return SavedMemorySearch.search(message, other, index, params, 5, true) {
            LongMessageSearch.read(message, index, now = { 0L }) { text ->
                if (text.contains("saffron") || text.contains("satellite")) topic else other
            }
        }
    }

    @Test fun poorPunctuationAndUnrelatedTopicsKeepBeginningMiddleAndEndDetails() {
        val memory = candidate("loom", "Saffron owns an antique loom and weaves linen", .61)
        for (at in listOf(0, 115, 220)) {
            val input = message(at, "saffron wants help repairing the old weaving frame")
            val result = search(input, memory)
            assertEquals("position $at", listOf("loom"), result.ranked.map { it.memory.memoryId })
            assertEquals(1, result.sectionMatches.size)
            assertTrue(result.sectionMatches.single().section.text.contains("saffron"))
        }
    }

    @Test fun semanticParaphraseWithNoSharedWordRemainsExcludedByApprovedPolicy() {
        val memory = candidate("astronomy", "A telescope was purchased for stargazing", .70)
        val result = search(message(115, "satellite observations through an optical instrument"), memory)
        assertTrue(result.ranked.isEmpty())
        val evaluated = LongMessageSearch.evaluate(result.sections, LiveRetrieval.index(listOf(memory)), LiveRetrieval.Params())
        assertTrue(evaluated.any { it.explanation.similarity!! > .48 && LongMessageSearch.Rejection.NO_SHARED_WORD in it.rejectedBy })
    }

    @Test fun accidentalKeywordWithInappropriateMeaningDoesNotQualify() {
        val memory = candidate("spice", "Saffron is kept in the kitchen spice jar", .20)
        val result = search(message(115, "saffron is the name painted on a fishing boat"), memory)
        assertTrue(result.ranked.isEmpty())
    }

    @Test fun passingSimilarityAndOverlapDoesNotBypassTheRelevanceGate() {
        val memory = candidate("loom", "Saffron owns a loom", .61)
        val strict = LiveRetrieval.Params(similarityFloor = .8, smallLibraryFloor = .8, keywordMin = 1.01)
        val result = search(message(115, "saffron repairs the frame"), memory, strict)
        assertTrue(result.ranked.isEmpty())
        assertTrue(LongMessageSearch.evaluate(result.sections, LiveRetrieval.index(listOf(memory)), strict)
            .any { LongMessageSearch.Rejection.RELEVANCE_GATE in it.rejectedBy && it.explanation.similarity!! > .48 })
    }

    @Test fun keywordFallbackNeverAttemptsAdditionalModelWork() {
        val memory = candidate("loom", "Saffron owns a loom", .61)
        val index = LiveRetrieval.index(listOf(memory))
        val result = SavedMemorySearch.search("saffron", null, index, LiveRetrieval.Params(), 5, true) {
            error("keyword-only retrieval must not request sections")
        }
        assertEquals(listOf("loom"), result.ranked.map { it.memory.memoryId })
    }

    @Test fun renderedLogFragmentHasBalancedPunctuationAndContext() {
        val mode = RecallDiagnostics.mode(false, false, 0, 2)
        assertEquals("saved memories: eligible 2, candidates 1 (Keyword search only (embedding model unavailable)), injected 1, omitted budget 0 / lore-overlap 0",
            RecallDiagnostics.summary(2, 1, mode, 1, 0, 0))
        assertFalse(RecallDiagnostics.mode(true, false, 2, 2).contains("model unavailable"))
        assertTrue(RecallDiagnostics.queryFailure(true, "bad input.").startsWith("Memory Assistant"))
        assertTrue(RecallDiagnostics.queryFailure(false, "bad input.").contains("this message"))
        assertFalse(RecallDiagnostics.queryFailure(true, "bad input.").contains(".."))
    }
}
