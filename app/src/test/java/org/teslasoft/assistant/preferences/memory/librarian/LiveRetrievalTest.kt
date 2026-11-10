package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import kotlin.math.sqrt

/**
 * Live chat memory search ([LiveRetrieval], [KeywordText]): the relevance
 * gate, keyword handling, meaning and keyword search together, relevance-first
 * ordering with a capped importance nudge, and the +3 rule
 * (`Memory System/dynamic_memory_plan.md`, Part B and steps 1.5–1.10).
 */
class LiveRetrievalTest {

    private val params = LiveRetrieval.Params()

    /** The query vector every test uses. */
    private val q = floatArrayOf(1f, 0f)

    /** A unit vector whose cosine similarity to [q] is exactly [c]. */
    private fun vec(c: Double) = floatArrayOf(c.toFloat(), sqrt(1 - c * c).toFloat())

    private fun mem(id: String, content: String = "memory $id", importance: Int = 0) =
        RetrievableMemory(
            memoryId = id, scope = "global", content = content,
            embeddingText = null, importance = importance,
            createdAt = "2026-07-01T00:00:00Z", worldId = null
        )

    private fun cand(
        id: String,
        similarity: Double? = null,
        content: String = "memory $id",
        importance: Int = 0,
        tags: List<String> = emptyList(),
        aliases: List<String> = emptyList()
    ) = LiveRetrieval.Candidate(mem(id, content, importance), similarity?.let { vec(it) }, tags, aliases)

    /** Ten unrelated memories spread around a typical noise level. */
    private fun noise(): List<LiveRetrieval.Candidate> =
        listOf(0.18, 0.20, 0.21, 0.22, 0.23, 0.24, 0.25, 0.26, 0.27, 0.28).mapIndexed { i, s ->
            cand("noise$i", s, content = "unrelated note number $i about weather")
        }

    private fun ids(r: LiveRetrieval.Result) = r.ranked.map { it.memory.memoryId }

    // ---- keyword text ---------------------------------------------------

    @Test
    fun commonWordsAreNeverSearchTerms() {
        assertEquals(emptyList<String>(), KeywordText.terms("The user and the thing, how are you doing?"))
    }

    @Test
    fun wordFormsShareOneTerm() {
        val forms = listOf("crash", "crashes", "crashed", "crashing").map { KeywordText.stem(it) }.toSet()
        assertEquals(setOf("crash"), forms)
        assertEquals(KeywordText.stem("cat"), KeywordText.stem("cats"))
        assertEquals(KeywordText.stem("puppy"), KeywordText.stem("puppies"))
    }

    @Test
    fun termsAreWholeWordsOnly() {
        val r = LiveRetrieval.search(
            "cat", null,
            listOf(cand("catalog", content = "The user keeps a seed catalog.")) + noise(),
            params, limit = 8
        )
        assertTrue(ids(r).isEmpty())
    }

    // ---- keyword search --------------------------------------------------

    @Test
    fun aVibeIsNeverSearched() {
        // The vibe says how to treat a memory, not what it is about, so its
        // words must never make the memory match (owner decision, Nov 10 2026).
        val withVibe = LiveRetrieval.Candidate(
            mem("pets", content = "The user has two cats named Pepper and Salt.")
                .copy(vibe = "playful teasing about gardening"),
            null, emptyList(), emptyList()
        )
        val r = LiveRetrieval.search("playful teasing about gardening", null, listOf(withVibe) + noise(), params, limit = 8)
        assertTrue(ids(r).isEmpty())
    }


    @Test
    fun aCommonWordInEveryMemoryBringsNothingIn() {
        // Memories written "The user ..." used to match any message with "the".
        val corpus = listOf(
            cand("a", content = "The user drives a red car."),
            cand("b", content = "The user likes tea.")
        ) + noise()
        val r = LiveRetrieval.search("what is the user doing today", null, corpus, params, limit = 8)
        assertTrue(ids(r).isEmpty())
    }

    @Test
    fun aRareNameFindsItsMemoryWithoutTheModel() {
        val corpus = listOf(cand("cat", content = "The user has a black cat named Pepper.")) + noise()
        val r = LiveRetrieval.search("tell me about Pepper", null, corpus, params, limit = 8)
        assertEquals(listOf("cat"), ids(r))
        assertFalse(r.diagnostics.meaningRan)
    }

    @Test
    fun tagsAndLinkedNamesAreSearchable() {
        val corpus = listOf(
            cand("t", content = "Prefers quiet evenings.", tags = listOf("introvert")),
            cand("n", content = "Lives two hours away.", aliases = listOf("Dana"))
        ) + noise()
        assertEquals(listOf("t"), ids(LiveRetrieval.search("am I an introvert", null, corpus, params, 8)))
        assertEquals(listOf("n"), ids(LiveRetrieval.search("what about Dana", null, corpus, params, 8)))
    }

    @Test
    fun anAccidentalWordInALongerMessageDoesNotPassOnKeywordsAlone() {
        // One shared word ("spring") used to give a perfect keyword score.
        val corpus = listOf(cand("move", content = "The user is planning to move to a new apartment in the spring.")) + noise()
        val r = LiveRetrieval.search("I watched a show about spring cleaning", null, corpus, params, limit = 8)
        assertTrue(ids(r).isEmpty())
        assertTrue(r.explanations.first { it.memory.memoryId == "move" }.keywordStrength < params.keywordMin)
    }

    @Test
    fun keywordStrengthIsTheShareOfTheMessageCovered() {
        val corpus = listOf(cand("cat", content = "The user has a black cat named Pepper.")) + noise()
        val whole = LiveRetrieval.search("tell me about Pepper", null, corpus, params, 8)
        val half = LiveRetrieval.search("Pepper and the dentist", null, corpus, params, 8)
        assertEquals(1.0, whole.explanations.first { it.memory.memoryId == "cat" }.keywordStrength, 1e-9)
        assertEquals(0.5, half.explanations.first { it.memory.memoryId == "cat" }.keywordStrength, 1e-9)
    }

    @Test
    fun aMultiSubjectMessageStillSendsEachStrongMeaningMatch() {
        // Each subject covers only part of the message, so keywords alone do
        // not pass; meaning search still sends each clearly matching memory.
        val corpus = listOf(
            cand("sister", 0.50, content = "The user's sister Dana lives in Chicago."),
            cand("cat", 0.48, content = "The user has a black cat named Pepper.")
        ) + noise()
        val r = LiveRetrieval.search("Dana called and Pepper is sick", q, corpus, params, limit = 8)
        assertEquals(setOf("sister", "cat"), ids(r).toSet())
        assertTrue(r.explanations.filter { it.memory.memoryId in setOf("sister", "cat") }.all { it.keywordStrength < 1.0 })
    }

    // ---- meaning search and the gate -------------------------------------

    @Test
    fun aMemoryThatStandsOutAboveTheFloorPasses() {
        val corpus = listOf(cand("hit", 0.48)) + noise()
        val r = LiveRetrieval.search("x", q, corpus, params, limit = 8)
        assertEquals(listOf("hit"), ids(r))
        assertTrue(r.diagnostics.meaningRan)
    }

    @Test
    fun nothingRelevantSendsNothing() {
        val r = LiveRetrieval.search("hey how are you", q, noise(), params, limit = 8)
        assertTrue(ids(r).isEmpty())
    }

    @Test
    fun aFloorPassThatDoesNotStandOutFails() {
        // Every memory is equally similar: none stands out, so none is sent.
        val corpus = (0 until 10).map { cand("m$it", 0.45) }
        assertTrue(ids(LiveRetrieval.search("x", q, corpus, params, limit = 8)).isEmpty())
    }

    @Test
    fun standingOutBelowTheFloorFails() {
        val corpus = listOf(cand("weak", 0.33)) + noise()
        assertTrue(ids(LiveRetrieval.search("x", q, corpus, params, limit = 8)).isEmpty())
    }

    @Test
    fun severalRelevantMemoriesOnOneTopicAllPass() {
        // A topic with many relevant memories must not raise the bar for all
        // of them (the gate uses the median, not the mean).
        val corpus = listOf(cand("a", 0.50), cand("b", 0.47), cand("c", 0.45)) + noise()
        assertEquals(setOf("a", "b", "c"), ids(LiveRetrieval.search("x", q, corpus, params, 8)).toSet())
    }

    @Test
    fun aSmallLibraryUsesItsFixedFloor() {
        val corpus = listOf(cand("in", 0.42), cand("out", 0.37), cand("low", 0.20))
        assertEquals(listOf("in"), ids(LiveRetrieval.search("x", q, corpus, params, limit = 8)))
    }

    @Test
    fun aMissingVectorAffectsOnlyItsOwnMemory() {
        val corpus = listOf(
            cand("meaning", 0.48),
            cand("keyword", similarity = null, content = "The user drives a Honda Civic.")
        ) + noise()
        val r = LiveRetrieval.search("how is my Honda Civic", q, corpus, params, limit = 8)
        assertEquals(setOf("meaning", "keyword"), ids(r).toSet())
        assertEquals(corpus.size, r.diagnostics.eligible)
        assertEquals(corpus.size - 1, r.diagnostics.withVector)
    }

    // ---- ordering, importance, and +3 -------------------------------------

    @Test
    fun aClearRelevanceLeadBeatsAnyImportance() {
        val corpus = listOf(
            cand("relevant", 0.54, importance = -2),
            cand("rated", 0.46, importance = 3)
        ) + noise()
        val r = LiveRetrieval.search("x", q, corpus, params, limit = 8)
        val e = r.explanations.associateBy { it.memory.memoryId }
        assertTrue(e.getValue("relevant").relevance - e.getValue("rated").relevance > params.clearLead)
        assertEquals("relevant", ids(r).first())
    }

    @Test
    fun importanceBreaksNearTies() {
        val corpus = listOf(cand("plain", 0.48, importance = 0), cand("rated", 0.47, importance = 2)) + noise()
        assertEquals("rated", ids(LiveRetrieval.search("x", q, corpus, params, limit = 8)).first())
    }

    @Test
    fun plusThreeOrdersExactlyLikePlusTwo() {
        // +3 gets no extra ordering boost over +2; its only extra meaning is
        // a guaranteed place when it matches (owner, 2026-11-08).
        val corpus = listOf(cand("two", 0.48, importance = 2), cand("three", 0.48, importance = 3)) + noise()
        val e = LiveRetrieval.search("x", q, corpus, params, limit = 8).explanations.associateBy { it.memory.memoryId }
        assertEquals(e.getValue("two").importanceBonus, e.getValue("three").importanceBonus, 1e-12)
        assertEquals(e.getValue("two").score, e.getValue("three").score, 1e-12)
    }

    @Test
    fun aRelevantPlusThreeIsAddedPastTheLimit() {
        val corpus = listOf(cand("best", 0.55), cand("second", 0.53), cand("plus3", 0.47, importance = 3)) + noise()
        val r = LiveRetrieval.search("x", q, corpus, params, limit = 1)
        assertEquals(listOf("best", "plus3"), ids(r))
    }

    @Test
    fun anUnrelatedPlusThreeIsNeverSent() {
        val corpus = listOf(cand("plus3", 0.27, importance = 3)) + noise()
        assertTrue(ids(LiveRetrieval.search("hey how are you", q, corpus, params, limit = 8)).isEmpty())
    }

    @Test
    fun importanceOffMeansNoNudgeAndNoPlusThreeOverflow() {
        val corpus = listOf(cand("best", 0.55), cand("plus3", 0.47, importance = 3)) + noise()
        val r = LiveRetrieval.search("x", q, corpus, params, limit = 1, useImportanceRatings = false)
        assertEquals(listOf("best"), ids(r))
        assertTrue(r.explanations.all { it.importanceBonus == 0.0 })
    }

    // ---- stored tuning ----------------------------------------------------

    @Test
    fun storedTuningIsBoundedAndMissingKeysKeepDefaults() {
        val (p, notes) = LiveRetrieval.Params.bounded(
            mapOf(
                LiveRetrieval.Params.KEY_SIMILARITY_FLOOR to 0.4,
                LiveRetrieval.Params.KEY_STANDOUT_MIN to 99.0,
                LiveRetrieval.Params.KEY_KEYWORD_MIN to Double.NaN
            )
        )
        assertEquals(0.4, p.similarityFloor, 1e-9)
        assertEquals(10.0, p.standoutMin, 1e-9)
        assertEquals(LiveRetrieval.Params().keywordMin, p.keywordMin, 1e-9)
        assertEquals(LiveRetrieval.Params().importanceWeight, p.importanceWeight, 1e-9)
        assertEquals(2, notes.size)
    }

    @Test
    fun emptyLibraryReturnsNothing() {
        val r = LiveRetrieval.search("anything", q, emptyList(), params, limit = 8)
        assertTrue(r.ranked.isEmpty())
        assertEquals(0, r.diagnostics.eligible)
    }
}
