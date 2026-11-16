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
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import kotlin.math.sqrt

/** The owner's +3 top-match rule (conversation_recall_plan.md G.10, G40). */
class PlusThreeTopMatchTest {

    // A stricter stand-out gate than the default, so a memory can fail the
    // gate while still standing out by more than the rule's 2.0.
    private val params = LiveRetrieval.Params(standoutMin = 3.0)
    private val q = floatArrayOf(1f, 0f)

    private fun vec(c: Double) = floatArrayOf(c.toFloat(), sqrt(1 - c * c).toFloat())

    private fun cand(id: String, similarity: Double, importance: Int = 0) = LiveRetrieval.Candidate(
        RetrievableMemory(id, "global", "note $id", null, importance, "2026-07-01T00:00:00Z", null),
        vec(similarity), emptyList(), emptyList()
    )

    private fun noise() = listOf(0.18, 0.20, 0.21, 0.22, 0.23, 0.24, 0.25, 0.26, 0.27, 0.28)
        .mapIndexed { i, s -> cand("noise$i", s) }

    private fun search(corpus: List<LiveRetrieval.Candidate>, importanceOn: Boolean = true, rule: Boolean = true) =
        LiveRetrieval.search("x", q, corpus, params, 8, useImportanceRatings = importanceOn,
            includeMandatoryImportance = importanceOn, plusThreeTopMatch = rule)

    private fun standoutOf(corpus: List<LiveRetrieval.Candidate>, id: String) =
        search(corpus, rule = false).explanations.single { it.memory.memoryId == id }.standout!!

    @Test
    fun aPlusThreeTopMatchThatFailsTheGateIsSent() {
        val corpus = noise() + cand("top", 0.36, importance = 3)
        val standout = standoutOf(corpus, "top")
        assertTrue("test setup: between the rule's 2.0 and the gate's 3.0, was $standout", standout in 2.0..3.0)
        assertTrue(search(corpus, rule = false).ranked.isEmpty())
        val r = search(corpus)
        assertEquals(listOf("top"), r.ranked.map { it.memory.memoryId })
        val e = r.explanations.single { it.memory.memoryId == "top" }
        assertTrue(e.passedTopMatch)
        assertFalse(e.passedMeaning)
    }

    @Test
    fun onlyTheSingleMostSimilarMemoryQualifies() {
        val corpus = noise() + cand("plus3", 0.36, importance = 3) + cand("closer", 0.37)
        assertTrue(search(corpus).ranked.none { it.memory.memoryId == "plus3" })
    }

    @Test
    fun aMemoryRatedBelowPlusThreeDoesNotQualify() {
        val corpus = noise() + cand("plus2", 0.36, importance = 2)
        assertTrue(search(corpus).ranked.isEmpty())
    }

    @Test
    fun belowTheSimilarityFloorDoesNotQualify() {
        val corpus = noise() + cand("top", 0.34, importance = 3)
        assertTrue(search(corpus).ranked.isEmpty())
    }

    @Test
    fun aStandOutBelowTwoDoesNotQualify() {
        val flat = listOf(0.30, 0.31, 0.32, 0.33, 0.34, 0.34, 0.33, 0.32, 0.31, 0.30).mapIndexed { i, s -> cand("n$i", s) }
        val corpus = flat + cand("top", 0.35, importance = 3)
        assertTrue("test setup", standoutOf(corpus, "top") < 2.0)
        assertTrue(search(corpus).ranked.isEmpty())
    }

    @Test
    fun withImportanceRatingsOffTheRuleDoesNotApply() {
        val corpus = noise() + cand("top", 0.36, importance = 3)
        assertTrue(search(corpus, importanceOn = false).ranked.isEmpty())
    }

    @Test
    fun aSectionDoesNotListTheTopMatchAgain() {
        val corpus = noise() + cand("top", 0.36, importance = 3)
        val index = LiveRetrieval.index(corpus)
        val whole = LiveRetrieval.search("x", q, index, params, 8)
        val reading = LongMessageSearch.Reading.NONE
        val matches = LongMessageSearch.matches(reading, index, params, whole.explanations)
        assertTrue(matches.none { it.explanation.memory.memoryId == "top" })
        val result = SavedMemorySearch.search("x", q, index, params, 8, true) { reading }
        assertEquals(1, result.ranked.count { it.memory.memoryId == "top" })
    }
}
