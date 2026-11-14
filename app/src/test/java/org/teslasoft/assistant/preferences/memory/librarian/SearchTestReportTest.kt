package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /* ---------------------- long messages in sections ---------------------- */

    private val elsewhere = floatArrayOf(0f, 1f)

    /** A 300-word message that mentions Pepper only in its third hundred. */
    private val longMessage = (0 until 200).joinToString(" ") { "zq$it" } + " Pepper seems unwell " +
        (200 until 297).joinToString(" ") { "zq$it" }

    /** Reads every section: the ones naming Pepper as [q], the rest as
     *  unrelated to every memory. */
    private fun sections(corpus: List<LiveRetrieval.Candidate>) =
        LongMessageSearch.read(longMessage, LiveRetrieval.index(corpus), now = { 0L }) { text ->
            if ("Pepper" in text) q else elsewhere
        }

    private fun pepper(importance: Int = 0) =
        cand("pepper", 0.55, content = "Pepper is the user's cat.", importance = importance)

    private fun buildLong(corpus: List<LiveRetrieval.Candidate>, tuning: SearchTuning = SearchTuning()) =
        SearchTestReport.build(
            longMessage, elsewhere, corpus, tuning, useImportanceRatings = true, sections = sections(corpus)
        )

    @Test
    fun aMemoryFoundOnlyBySectionIsSentAndNamesItsSection() {
        val corpus = listOf(pepper()) + noise()
        val withoutSections = SearchTestReport.build(longMessage, elsewhere, corpus, SearchTuning(), true)
        assertTrue(withoutSections.sent.isEmpty())

        val r = buildLong(corpus)
        val row = r.sent.single()
        assertEquals("pepper", row.explanation.memory.memoryId)
        assertEquals(SearchTestReport.Outcome.SENT, row.outcome)
        val match = row.section!!
        assertTrue("Pepper" in match.section.text)
        assertEquals(r.sections.sections.size, match.sectionCount)
        // The row's numbers are the memory against that section.
        assertEquals(0.55, row.explanation.similarity!!, 0.001)
        assertTrue(r.preview.contains("Pepper is the user's cat."))
        assertTrue(r.notSent.none { it.explanation.memory.memoryId == "pepper" })
    }

    @Test
    fun wholeMessageMatchesKeepTheirPlaceAheadOfSectionMatches() {
        // "whole" is matched by the whole message through its keywords; the
        // stronger Pepper match is found only by section and still comes after.
        val whole = LiveRetrieval.Candidate(
            RetrievableMemory(
                memoryId = "whole", scope = "global",
                content = (0 until 297).joinToString(" ") { "zq$it" },
                embeddingText = null, importance = 0,
                createdAt = "2026-07-01T00:00:00Z", worldId = null
            ),
            vec(0.1), emptyList(), emptyList()
        )
        val corpus = listOf(pepper(), whole) + noise()
        val r = buildLong(corpus)
        assertEquals(listOf("whole", "pepper"), r.sent.map { it.explanation.memory.memoryId })
        assertNull(r.sent[0].section)
        assertNotNull(r.sent[1].section)

        // With room for one memory, the whole-message match is the one sent.
        val one = buildLong(corpus, SearchTuning(maxMemories = 1))
        assertEquals(listOf("whole"), one.sent.map { it.explanation.memory.memoryId })
        val left = one.notSent.single { it.explanation.memory.memoryId == "pepper" }
        assertEquals(SearchTestReport.Outcome.LIMIT_FULL, left.outcome)
        assertNotNull(left.section)
    }

    @Test
    fun aPlusThreeMemoryFoundOnlyBySectionDoesNotGoPastTheLimit() {
        val whole = LiveRetrieval.Candidate(
            RetrievableMemory(
                memoryId = "whole", scope = "global",
                content = (0 until 297).joinToString(" ") { "zq$it" },
                embeddingText = null, importance = 0,
                createdAt = "2026-07-01T00:00:00Z", worldId = null
            ),
            vec(0.1), emptyList(), emptyList()
        )
        val r = buildLong(listOf(pepper(importance = 3), whole) + noise(), SearchTuning(maxMemories = 1))
        assertEquals(listOf("whole"), r.sent.map { it.explanation.memory.memoryId })
    }

    @Test
    fun aSectionMatchThatWasNotSentIsShownEvenBelowTheRowsShown() {
        val whole = LiveRetrieval.Candidate(
            RetrievableMemory(
                memoryId = "whole", scope = "global",
                content = (0 until 297).joinToString(" ") { "zq$it" },
                embeddingText = null, importance = 0,
                createdAt = "2026-07-01T00:00:00Z", worldId = null
            ),
            vec(0.1), emptyList(), emptyList()
        )
        val corpus = listOf(pepper(), whole) + noise()
        val r = SearchTestReport.build(
            longMessage, elsewhere, corpus, SearchTuning(maxMemories = 1), true,
            notSentShown = 2, sections = sections(corpus)
        )
        assertTrue(r.notSent.any { it.explanation.memory.memoryId == "pepper" && it.section != null })
    }
    @Test
    fun belowSectionThresholdIsNotMislabelledAsMaximumMemories() {
        val corpus = listOf(cand("pepper", .47, "Pepper is a cat")) + noise()
        val report = buildLong(corpus)
        val row = report.notSent.first { it.explanation.memory.memoryId == "pepper" }
        assertEquals(SearchTestReport.Outcome.NOT_A_MATCH, row.outcome)
        assertNull(row.section)
        assertEquals(.48, row.sectionEvaluation!!.requiredSimilarity, 0.0)
        assertTrue(row.sectionEvaluation!!.rejectedBy.contains(LongMessageSearch.Rejection.SIMILARITY))
        assertEquals(.47, row.explanation.similarity!!, .001)
    }

    @Test
    fun highSimilarityWithoutWordsExplainsMissingOverlap() {
        val corpus = listOf(cand("gate", .55, "The garden latch is stiff")) + noise()
        val report = buildLong(corpus)
        val row = report.notSent.first { it.explanation.memory.memoryId == "gate" }
        assertEquals(SearchTestReport.Outcome.NOT_A_MATCH, row.outcome)
        assertTrue(row.sectionEvaluation!!.rejectedBy.contains(LongMessageSearch.Rejection.NO_SHARED_WORD))
        assertFalse(row.sectionEvaluation!!.rejectedBy.contains(LongMessageSearch.Rejection.SIMILARITY))
    }

    @Test
    fun unsearchedSectionsAreNeverPresentedAsFailedRelevance() {
        val corpus = listOf(pepper()) + noise()
        val reading = LongMessageSearch.read(longMessage, LiveRetrieval.index(corpus),
            LongMessageSearch.Settings(timeAllowanceMillis = 0)) { error("must not read") }
        val report = SearchTestReport.build(longMessage, elsewhere, corpus, SearchTuning(), true, sections = reading)
        assertTrue(report.notSent.all { it.sectionEvaluation == null })
    }

    @Test
    fun effectiveSettingsTravelWithReadingIntoDiagnosticsAndMatcher() {
        val corpus = listOf(pepper()) + noise()
        val reading = sections(corpus).copy(settings = LongMessageSearch.Settings(sectionSimilarityMin = .7))
        val report = SearchTestReport.build(longMessage, elsewhere, corpus, SearchTuning(), true, sections = reading)
        val row = report.notSent.first { it.explanation.memory.memoryId == "pepper" }
        assertEquals(.7, row.sectionEvaluation!!.requiredSimilarity, 0.0)
        assertEquals(SearchTestReport.Outcome.NOT_A_MATCH, row.outcome)
    }

    @Test
    fun qualifyingMatchesBeyondScanCapAreNotCalledRelevanceFailures() {
        val oversized = (0..70).map { i -> cand("large$i", .58, "zq0 ".repeat(3000)) }
        val corpus = oversized + listOf(pepper()) + noise()
        // All large memories match the whole message on the semantic gate,
        // consume candidate scanning, and are rejected by character budget.
        val report = SearchTestReport.build(longMessage, q, corpus,
            SearchTuning(maxMemories = 1, memorySpace = 500,
                params = LiveRetrieval.Params(standoutMin = 0.0)), true, notSentShown = 100, sections = sections(corpus))
        assertTrue(report.notSent.any { it.outcome == SearchTestReport.Outcome.SCAN_LIMIT })
    }

    @Test
    fun nearMissesAreNotHiddenByUnrelatedWholeMessageScores() {
        val corpus = noise() + noise().map { it.copy(memory = it.memory.copy(memoryId = it.memory.memoryId + "b")) } +
            cand("pepper", .47, "Pepper is a cat")
        val report = SearchTestReport.build(longMessage, elsewhere, corpus, SearchTuning(), true,
            notSentShown = 2, sections = sections(corpus))
        assertTrue(report.notSent.any { it.explanation.memory.memoryId == "pepper" && it.sectionEvaluation != null })
    }

    @Test
    fun largeSectionMatchSetsDoNotCreateUnboundedDiagnosticCards() {
        val corpus = (0 until 200).map { cand("pepper$it", .55, "Pepper is a cat") }
        val report = SearchTestReport.build(longMessage, floatArrayOf(-1f, 0f), corpus,
            SearchTuning(maxMemories = 1, params = LiveRetrieval.Params(standoutMin = 0.0)), true,
            sections = sections(corpus))
        assertEquals(1, report.sent.size)
        assertTrue(report.notSent.size <= 75)
        assertTrue(report.notSent.all { it.section != null && it.outcome == SearchTestReport.Outcome.LIMIT_FULL })
    }

}
