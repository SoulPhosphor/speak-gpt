package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import kotlin.math.sqrt

/**
 * The Memory Search Test report ([SearchTestReport]): which memories a chat
 * would send for a message and why every other memory was left out, using the
 * same search and memory-limit / memory-space walk as the chat.
 */
class SearchTestReportTest {

    private val q = floatArrayOf(1f, 0f)

    private fun vec(c: Double) = floatArrayOf(c.toFloat(), sqrt(1 - c * c).toFloat())

    private fun cand(id: String, similarity: Double, content: String = "memory $id", importance: Int = 0) =
        LiveRetrieval.Candidate(
            RetrievableMemory(
                memoryId = id, scope = "global", content = content,
                embeddingText = null, importance = importance,
                createdAt = "2026-07-01T00:00:00Z", worldId = null
            ),
            vec(similarity), emptyList(), emptyList()
        )

    private fun noise() = listOf(0.18, 0.20, 0.21, 0.22, 0.23, 0.24, 0.25, 0.26, 0.27, 0.28)
        .mapIndexed { i, s -> cand("noise$i", s, content = "unrelated note $i") }

    private fun build(corpus: List<LiveRetrieval.Candidate>, tuning: SearchTuning = SearchTuning()) =
        SearchTestReport.build("x", q, corpus, tuning, useImportanceRatings = true)

    @Test
    fun relevantMemoriesAreSentAndTheRestExplained() {
        val r = build(listOf(cand("hit", 0.50, content = "The user has a cat named Pepper.")) + noise())
        assertEquals(listOf("hit"), r.sent.map { it.explanation.memory.memoryId })
        assertEquals(SearchTestReport.Outcome.SENT, r.sent.single().outcome)
        assertTrue(r.notSent.isNotEmpty())
        assertTrue(r.notSent.all { it.outcome == SearchTestReport.Outcome.NOT_A_MATCH })
        assertTrue(r.preview.contains("The user has a cat named Pepper."))
        assertFalse(r.preview.contains("unrelated note"))
    }

    @Test
    fun nothingRelevantSendsNothingAndAnEmptyPreview() {
        val r = build(noise())
        assertTrue(r.sent.isEmpty())
        assertTrue(r.preview.isBlank())
    }

    @Test
    fun matchesBeyondTheLimitAreReportedAsLimitFull() {
        val corpus = listOf(cand("a", 0.55), cand("b", 0.52), cand("c", 0.50)) + noise()
        val r = build(corpus, SearchTuning(maxMemories = 1))
        assertEquals(listOf("a"), r.sent.map { it.explanation.memory.memoryId })
        val outcomes = r.notSent.associate { it.explanation.memory.memoryId to it.outcome }
        assertEquals(SearchTestReport.Outcome.LIMIT_FULL, outcomes["b"])
        assertEquals(SearchTestReport.Outcome.LIMIT_FULL, outcomes["c"])
    }

    @Test
    fun aRelevantPlusThreeIsSentPastTheLimit() {
        val corpus = listOf(cand("a", 0.55), cand("b", 0.53), cand("plus3", 0.47, importance = 3)) + noise()
        val r = build(corpus, SearchTuning(maxMemories = 1))
        assertEquals(listOf("a", "plus3"), r.sent.map { it.explanation.memory.memoryId })
        assertEquals(SearchTestReport.Outcome.SENT_PAST_LIMIT, r.sent.last().outcome)
    }

    @Test
    fun aMatchThatDoesNotFitTheMemorySpaceIsReportedAsNoSpace() {
        val long = "x".repeat(600)
        val corpus = listOf(cand("short", 0.55, content = "short memory"), cand("long", 0.52, content = long)) + noise()
        val r = build(corpus, SearchTuning(memorySpace = 500))
        assertEquals(listOf("short"), r.sent.map { it.explanation.memory.memoryId })
        assertEquals(
            SearchTestReport.Outcome.NO_SPACE,
            r.notSent.first { it.explanation.memory.memoryId == "long" }.outcome
        )
    }

    @Test
    fun adjustedNumbersChangeTheResult() {
        val corpus = listOf(cand("hit", 0.50)) + noise()
        val strict = SearchTuning(params = LiveRetrieval.Params(similarityFloor = 0.6))
        assertTrue(build(corpus, strict).sent.isEmpty())
        assertEquals(1, build(corpus).sent.size)
    }
}
