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

/**
 * The one shared importance-access path (canonical recovery plan Phase 2, item
 * 3), proven on the JVM. Covers the two required behaviors:
 *
 *  5. Importance Off contributes EXACTLY ZERO to ranking without changing the
 *     stored ratings.
 *  6. Importance On reuses the preserved stored values.
 *
 * The gate is pure ([ImportanceRanking.effectiveImportanceWeight]); it is
 * exercised directly and then through [Librarian.rank] to show it changes only
 * the score contribution, never a memory's stored importance.
 */
class ImportanceRankingTest {

    private val storedImportanceWeight = 0.3

    @Test
    fun offZeroesTheWeight_onKeepsIt() {
        // Test 5 / 6 at the shared-path level.
        assertEquals(0.0, ImportanceRanking.effectiveImportanceWeight(storedImportanceWeight, false), 0.0)
        assertEquals(storedImportanceWeight, ImportanceRanking.effectiveImportanceWeight(storedImportanceWeight, true), 0.0)
    }

    private fun mem(id: String, importance: Int) = RetrievableMemory(
        memoryId = id, scope = "global", content = id, embeddingText = null,
        importance = importance, createdAt = "2026-07-01T00:00:00Z", worldId = null,
        updatedAt = null
    )

    @Test
    fun signedScaleIsSymmetricAndPlusThreeUsesPlusTwoScore() {
        assertEquals(-1.0, ImportanceRanking.normalizedRankingImportance(-2.0), 0.0)
        assertEquals(-0.5, ImportanceRanking.normalizedRankingImportance(-1.0), 0.0)
        assertEquals(0.0, ImportanceRanking.normalizedRankingImportance(0.0), 0.0)
        assertEquals(0.5, ImportanceRanking.normalizedRankingImportance(1.0), 0.0)
        assertEquals(1.0, ImportanceRanking.normalizedRankingImportance(2.0), 0.0)
        assertEquals(1.0, ImportanceRanking.normalizedRankingImportance(3.0), 0.0)
    }

    @Test
    fun plusThreeIsMandatoryOnlyWhenImportanceIsEnabled() {
        assertTrue(ImportanceRanking.isMandatory(3.0, true))
        assertFalse(ImportanceRanking.isMandatory(3.0, false))
        assertFalse(ImportanceRanking.isMandatory(2.0, true))
    }

    @Test
    fun mandatoryCandidatesExtendPastTopKWithoutDuplicatingTheHead() {
        val ranked = listOf("normal", "must-a", "must-b")
        val selected = ImportanceRanking.includeMandatory(ranked, topK = 1) { it.startsWith("must-") }
        assertEquals(listOf("normal", "must-a", "must-b"), selected)

        val alreadyInHead = ImportanceRanking.includeMandatory(ranked, topK = 2) { it == "must-a" }
        assertEquals(listOf("normal", "must-a"), alreadyInHead)
    }

    @Test
    fun importanceOff_twoMemoriesIdenticalExceptImportanceScoreEqually() {
        // Two candidates identical but for importance, same vector/recency/boost.
        val vec = floatArrayOf(1f, 0f, 0f)
        val low = mem("low", importance = 0)
        val high = mem("high", importance = 2)
        val candidates = listOf(
            Librarian.Candidate(low, vec, recency = 0.5, boost = 0.0),
            Librarian.Candidate(high, vec, recency = 0.5, boost = 0.0)
        )

        // Off: importance weight is exactly zero, so the two score identically —
        // importance made no difference at all.
        val offWeights = Librarian.Weights(0.6, ImportanceRanking.effectiveImportanceWeight(storedImportanceWeight, false), 0.1)
        val off = Librarian.rank(vec, candidates, offWeights, topK = 2)
        val offLow = off.first { it.memory.memoryId == "low" }.score
        val offHigh = off.first { it.memory.memoryId == "high" }.score
        assertEquals("importance Off must contribute exactly zero", offLow, offHigh, 1e-6f)

        // On: the stored ratings take effect again (no rewrite needed), so the
        // higher-importance memory now outscores the lower one.
        val onWeights = Librarian.Weights(0.6, ImportanceRanking.effectiveImportanceWeight(storedImportanceWeight, true), 0.1)
        val on = Librarian.rank(vec, candidates, onWeights, topK = 2)
        val onLow = on.first { it.memory.memoryId == "low" }.score
        val onHigh = on.first { it.memory.memoryId == "high" }.score
        assertTrue("importance On must reuse the stored ratings", onHigh > onLow)

        // The stored ratings themselves were never touched by ranking.
        assertEquals(0, low.importance)
        assertEquals(2, high.importance)
    }
}
