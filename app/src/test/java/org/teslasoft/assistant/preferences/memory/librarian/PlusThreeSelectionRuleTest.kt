/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.ImportanceRanking
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.enforcer.RetrievalBackfill
import kotlin.math.sqrt

/**
 * The owner's +3 rule, from the search to the enforcer's selection:
 *  - +2 and +3 have the same ranking weight;
 *  - Maximum Memories applies normally to every matching memory;
 *  - every further matching +3 memory is then added, once;
 *  - +2 never passes the limit;
 *  - the rule is the same for a memory the whole message found and one a
 *    section of a long message found, including beyond the number of
 *    candidates the search hands on.
 * Previous-message matches are covered in PreviousMessageSearchTest.
 */
class PlusThreeSelectionRuleTest {

    private val params = LiveRetrieval.Params()

    private fun cand(id: String, content: String, importance: Int = 0, vector: FloatArray? = null) =
        LiveRetrieval.Candidate(
            RetrievableMemory(
                memoryId = id, scope = "global", content = content,
                embeddingText = null, importance = importance,
                createdAt = "2026-07-01T00:00:00Z", worldId = null
            ),
            vector, emptyList(), emptyList()
        )

    private fun many(prefix: String, word: String, count: Int, importance: Int) =
        (0 until count).map { cand("$prefix%02d".format(it), "$word note $prefix$it", importance = importance) }

    private fun search(
        query: String,
        corpus: List<LiveRetrieval.Candidate>,
        candidateLimit: Int,
        useImportanceRatings: Boolean = true,
        vector: FloatArray? = null,
        sections: (LiveRetrieval.Index) -> LongMessageSearch.Reading = { LongMessageSearch.Reading.NONE }
    ): SavedMemorySearch.Result {
        val index = LiveRetrieval.index(corpus)
        return SavedMemorySearch.search(query, vector, index, params, candidateLimit, useImportanceRatings) {
            sections(index)
        }
    }

    /** The enforcer's selection, called the way the enforcer calls it. */
    private fun sent(
        result: SavedMemorySearch.Result,
        maximumMemories: Int,
        useImportanceRatings: Boolean = true,
        survives: (String) -> Boolean = { true }
    ): List<String> = RetrievalBackfill.select(
        result.ranked, maximumMemories, RetrievalBackfill.scanCap(maximumMemories),
        isMandatory = { ImportanceRanking.isMandatory(it.memory.importance.toDouble(), useImportanceRatings) }
    ) { survives(it.memory.memoryId) }.kept.map { it.memory.memoryId }

    private fun liveLimit(maximumMemories: Int) = RetrievalBackfill.scanCap(maximumMemories) + 1

    /* --------------------------- whole message --------------------------- */

    @Test
    fun plusTwoAndPlusThreeHaveTheSameRankingWeight() {
        val corpus = listOf(cand("two", "kiln glaze", importance = 2), cand("three", "kiln glaze", importance = 3))
        val scores = search("kiln glaze", corpus, liveLimit(5)).whole.explanations.associate {
            it.memory.memoryId to it.score
        }
        assertEquals(scores.getValue("two"), scores.getValue("three"), 0.0)
    }

    @Test
    fun tenPlusTwoMemoriesFillFivePlacesAndThreePlusThreeMemoriesAreAdded() {
        // Maximum Memories 5: ten matching +2 memories compete for the five
        // normal places; three more matching +3 memories are added: eight.
        val corpus = many("two", "kiln", 10, importance = 2) + many("three", "kiln", 3, importance = 3)
        val result = search("kiln", corpus, liveLimit(5))
        assertEquals(
            listOf("two00", "two01", "two02", "two03", "two04", "three00", "three01", "three02"),
            sent(result, maximumMemories = 5)
        )
    }

    @Test
    fun aPlusThreeInsideTheLimitUsesItsPlaceAndIsNotAddedTwice() {
        val corpus = many("three", "kiln", 3, importance = 3) + many("two", "kiln", 10, importance = 2)
        val result = search("kiln", corpus, liveLimit(5))
        assertEquals(listOf("three00", "three01", "three02", "two00", "two01"), sent(result, maximumMemories = 5))
    }

    @Test
    fun plusTwoNeverPassesTheLimitAndPlusThreeDoesNotWithRatingsOff() {
        val corpus = many("two", "kiln", 10, importance = 2) + many("three", "kiln", 3, importance = 3)
        assertEquals(5, sent(search("kiln", many("two", "kiln", 10, importance = 2), liveLimit(5)), 5).size)
        val off = search("kiln", corpus, liveLimit(5), useImportanceRatings = false)
        assertEquals(5, sent(off, maximumMemories = 5, useImportanceRatings = false).size)
    }

    @Test
    fun aPlusThreeMustStillMatch() {
        val corpus = many("two", "kiln", 6, importance = 2) + cand("other", "harbour master", importance = 3)
        assertEquals(5, sent(search("kiln", corpus, liveLimit(5)), 5).size)
        assertFalse("other" in sent(search("kiln", corpus, liveLimit(5)), 5))
    }

    @Test
    fun aPlusThreeBeyondTheCandidateCutoffIsStillSent() {
        // More ordinary matches than the enforcer examines, then the +3.
        val cutoff = liveLimit(1)
        val corpus = many("two", "kiln", cutoff + 4, importance = 2) + cand("protected", "kiln shelf", importance = 3)
        val result = search("kiln", corpus, cutoff)
        assertEquals(cutoff + 1, result.ranked.size)
        assertEquals(listOf("two00", "protected"), sent(result, maximumMemories = 1))
    }

    @Test
    fun theEnforcersFiltersAndBackfillStillApply() {
        val corpus = many("two", "kiln", 10, importance = 2) + many("three", "kiln", 3, importance = 3)
        val result = search("kiln", corpus, liveLimit(5))
        // Two candidates are dropped (lore overlap or memory space): their
        // places are filled from the next candidates; a +3 that does not
        // survive is not sent.
        assertEquals(
            listOf("two00", "two02", "two04", "two05", "two06", "three00", "three02"),
            sent(result, maximumMemories = 5) { it != "two01" && it != "two03" && it != "three01" }
        )
    }

    /* ------------------------ long-message sections ------------------------ */

    private val onSubject = floatArrayOf(1f, 0f, 0f)
    private val elsewhere = floatArrayOf(0f, 1f, 0f)
    private fun vec(similarity: Double) =
        floatArrayOf(similarity.toFloat(), 0f, sqrt(1 - similarity * similarity).toFloat())

    private fun filler(count: Int, from: Int) = (from until from + count).joinToString(" ") { "zq$it" }
    private val longMessage = filler(200, 0) + " Pepper seems unwell lately " + filler(96, 200)

    /** Matched by the whole long message through its words. */
    private val whole = cand("whole", filler(296, 0), vector = vec(0.1))
    private fun noise() = listOf(0.18, 0.20, 0.21, 0.22, 0.23, 0.24, 0.25, 0.26, 0.27, 0.28)
        .mapIndexed { i, s -> cand("noise$i", "unrelated note $i", vector = vec(s)) }

    private fun searchLong(
        corpus: List<LiveRetrieval.Candidate>, candidateLimit: Int, useImportanceRatings: Boolean = true
    ) = search(longMessage, corpus, candidateLimit, useImportanceRatings, vector = elsewhere) { index ->
        LongMessageSearch.read(longMessage, index, now = { 0L }) { text -> if ("Pepper" in text) onSubject else elsewhere }
    }

    @Test
    fun aPlusThreeFoundOnlyByASectionIsSentPastMaximumMemories() {
        val corpus = noise() + whole + cand("pepper", "Pepper is a tabby cat", importance = 3, vector = vec(0.55))
        val result = searchLong(corpus, liveLimit(1))
        assertEquals(listOf("whole", "pepper"), result.ranked.map { it.memory.memoryId })
        assertEquals(setOf("pepper"), result.sectionIds)
        assertEquals(listOf("whole", "pepper"), sent(result, maximumMemories = 1))
    }

    @Test
    fun aPlusTwoFoundOnlyByASectionDoesNotPassMaximumMemories() {
        val corpus = noise() + whole + cand("pepper", "Pepper is a tabby cat", importance = 2, vector = vec(0.55))
        assertEquals(listOf("whole"), sent(searchLong(corpus, liveLimit(1)), maximumMemories = 1))
        val three = noise() + whole + cand("pepper", "Pepper is a tabby cat", importance = 3, vector = vec(0.55))
        val off = searchLong(three, liveLimit(1), useImportanceRatings = false)
        assertEquals(listOf("whole"), sent(off, maximumMemories = 1, useImportanceRatings = false))
    }

    @Test
    fun aSectionPlusThreeBeyondTheCandidateCutoffIsHandedOnAndSent() {
        // With room for one candidate the whole-message match takes it. The
        // section's +3 match is still handed on; its stronger +2 match is not.
        val corpus = noise() + whole +
            cand("ordinary", "Pepper likes salmon", importance = 2, vector = vec(0.56)) +
            cand("protected", "Pepper is a tabby cat", importance = 3, vector = vec(0.55))
        val cut = searchLong(corpus, candidateLimit = 1)
        assertEquals(listOf("whole", "protected"), cut.ranked.map { it.memory.memoryId })
        assertEquals(setOf("protected"), cut.sectionIds)
        assertEquals(listOf("whole", "protected"), sent(cut, maximumMemories = 1))
        // With room, both are candidates in their own order and the limit
        // is the enforcer's.
        val roomy = searchLong(corpus, liveLimit(2))
        assertEquals(listOf("whole", "ordinary", "protected"), roomy.ranked.map { it.memory.memoryId })
        assertEquals(listOf("whole", "ordinary", "protected"), sent(roomy, maximumMemories = 2))
        assertEquals(listOf("whole", "protected"), sent(roomy, maximumMemories = 1))
        assertTrue(roomy.sectionIds.containsAll(setOf("ordinary", "protected")))
    }
}
