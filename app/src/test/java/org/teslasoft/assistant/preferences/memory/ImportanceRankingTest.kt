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

package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.librarian.Librarian

/** JVM coverage for the user-owned signed importance contract. */
class ImportanceRankingTest {

    private fun mem(id: String, importance: Int, content: String = id) = RetrievableMemory(
        memoryId = id,
        scope = "global",
        content = content,
        embeddingText = null,
        importance = importance,
        createdAt = "2026-07-01T00:00:00Z",
        worldId = null,
        updatedAt = null
    )

    @Test
    fun signedScaleIsCenteredOnNeutral_andPlusThreeDoesNotGetExtraScore() {
        assertEquals(-1.0, ImportanceRanking.normalizedRankingValue(-2), 1e-9)
        assertEquals(-0.5, ImportanceRanking.normalizedRankingValue(-1), 1e-9)
        assertEquals(0.0, ImportanceRanking.normalizedRankingValue(0), 1e-9)
        assertEquals(0.5, ImportanceRanking.normalizedRankingValue(1), 1e-9)
        assertEquals(1.0, ImportanceRanking.normalizedRankingValue(2), 1e-9)
        assertEquals(1.0, ImportanceRanking.normalizedRankingValue(3), 1e-9)
    }

    @Test
    fun absentImportanceIsNeutral() {
        assertEquals(0, ImportanceRanking.sanitizeImportance(null))
    }

    @Test
    fun offZeroesRanking_andDisablesPlusThreeMandatoryInclusion() {
        val configuredWeight = 0.3
        assertEquals(0.0, ImportanceRanking.effectiveImportanceWeight(configuredWeight, false), 0.0)
        assertFalse(ImportanceRanking.isAlwaysIncluded(3, false))

        val vec = floatArrayOf(1f, 0f, 0f)
        val candidates = listOf(
            Librarian.Candidate(mem("normal", 0), vec, recency = 0.5),
            Librarian.Candidate(mem("plus-three", 3), vec, recency = 0.5)
        )
        val offWeights = Librarian.Weights(
            similarity = 1.0,
            importance = ImportanceRanking.effectiveImportanceWeight(configuredWeight, false),
            recency = 0.0,
            useImportanceRatings = false
        )
        val ranked = Librarian.rank(vec, candidates, offWeights, topK = 1)
        assertEquals(listOf("normal"), ranked.map { it.memory.memoryId })
    }

    @Test
    fun signedImportanceReordersOtherwiseEqualEligibleMemories() {
        val vec = floatArrayOf(1f, 0f, 0f)
        val candidates = listOf(
            Librarian.Candidate(mem("demoted", -2), vec, recency = 0.5),
            Librarian.Candidate(mem("promoted", 2), vec, recency = 0.5)
        )
        val weights = Librarian.Weights(0.6, 0.3, 0.1, useImportanceRatings = true)
        val ranked = Librarian.rank(vec, candidates, weights, topK = 2)
        assertEquals("promoted", ranked.first().memory.memoryId)
    }

    @Test
    fun plusThreeBypassesSemanticCountCapAfterEligibility() {
        val vec = floatArrayOf(1f, 0f, 0f)
        val candidates = listOf(
            Librarian.Candidate(mem("normal", 0), vec, recency = 0.5),
            Librarian.Candidate(mem("must-include", 3), vec, recency = 0.5)
        )
        val weights = Librarian.Weights(1.0, 0.0, 0.0, useImportanceRatings = true)
        val ranked = Librarian.rank(vec, candidates, weights, topK = 1)
        assertEquals(listOf("normal", "must-include"), ranked.map { it.memory.memoryId })
        assertTrue(ImportanceRanking.isAlwaysIncluded(3, true))
    }

    @Test
    fun plusThreeCannotBypassSemanticRelevanceFloor() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf(
            Librarian.Candidate(mem("relevant", 0), floatArrayOf(1f, 0f), recency = 0.5),
            Librarian.Candidate(mem("irrelevant-plus-three", 3), floatArrayOf(0f, 1f), recency = 0.5)
        )
        val weights = Librarian.Weights(1.0, 0.0, 0.0, useImportanceRatings = true)
        val ranked = Librarian.rank(query, candidates, weights, topK = 1, minSimilarity = 0.3f)
        assertEquals(listOf("relevant"), ranked.map { it.memory.memoryId })
    }

    @Test
    fun plusThreeBypassesLexicalCountCapButNotTokenEligibility() {
        val candidates = listOf(
            Librarian.LexicalCandidate(mem("normal", 0, "garden plans"), emptyList(), recency = 0.5),
            Librarian.LexicalCandidate(mem("must-include", 3, "garden notes"), emptyList(), recency = 0.5),
            Librarian.LexicalCandidate(mem("unrelated", 3, "tax paperwork"), emptyList(), recency = 0.5)
        )
        val weights = Librarian.Weights(1.0, 0.0, 0.0, useImportanceRatings = true)
        val ranked = Librarian.rankLexical("garden", candidates, weights, topK = 1)
        assertEquals(listOf("normal", "must-include"), ranked.map { it.memory.memoryId })
    }
}
