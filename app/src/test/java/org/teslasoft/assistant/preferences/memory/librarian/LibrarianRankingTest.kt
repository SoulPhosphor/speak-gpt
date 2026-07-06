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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory

/**
 * The librarian's ranking math is the part that must be provably correct
 * regardless of which model produced the vectors — so it lives in
 * [Librarian.rank] and [VectorMath] with no Android/ORT deps and is tested
 * here on the JVM.
 */
class LibrarianRankingTest {

    private fun mem(id: String, importance: Int = 3, confidence: String? = "certain", scope: String = "global") =
        RetrievableMemory(
            memoryId = id, scope = scope, title = id, content = id,
            embeddingText = null, importance = importance,
            createdAt = "2026-07-01T00:00:00Z", worldId = null, provenanceConfidence = confidence
        )

    private fun cand(
        memory: RetrievableMemory,
        vector: FloatArray,
        recency: Double = 0.5,
        boost: Double = 0.0
    ) = Librarian.Candidate(memory, vector, recency, boost)

    private val weights = Librarian.Weights(0.6, 0.3, 0.1)

    @Test
    fun cosineIsDirectionalNotMagnitude() {
        val a = floatArrayOf(1f, 0f, 0f)
        assertEquals(1f, VectorMath.cosine(a, floatArrayOf(5f, 0f, 0f)), 1e-6f)
        assertEquals(0f, VectorMath.cosine(a, floatArrayOf(0f, 3f, 0f)), 1e-6f)
        assertEquals(-1f, VectorMath.cosine(a, floatArrayOf(-2f, 0f, 0f)), 1e-6f)
        // Zero vector never produces NaN.
        assertEquals(0f, VectorMath.cosine(a, floatArrayOf(0f, 0f, 0f)), 0f)
    }

    @Test
    fun blobRoundTripIsLossless() {
        val v = floatArrayOf(0.1f, -2.5f, 3.14159f, 0f, 1234.5f)
        assertTrue(v.contentEquals(VectorMath.fromBlob(VectorMath.toBlob(v))))
    }

    @Test
    fun mostSimilarMemoryRanksFirst() {
        val query = floatArrayOf(1f, 0f, 0f)
        val ranked = Librarian.rank(
            query,
            listOf(
                cand(mem("far"), floatArrayOf(0f, 1f, 0f)),
                cand(mem("near"), floatArrayOf(0.9f, 0.1f, 0f))
            ),
            weights, topK = 10
        )
        assertEquals("near", ranked.first().memory.memoryId)
    }

    @Test
    fun tentativeMemoriesAreDampened() {
        val query = floatArrayOf(1f, 0f, 0f)
        // Identical vectors, importance, recency — only confidence differs.
        val ranked = Librarian.rank(
            query,
            listOf(
                cand(mem("tentative", confidence = "tentative"), floatArrayOf(1f, 0f, 0f)),
                cand(mem("certain", confidence = "certain"), floatArrayOf(1f, 0f, 0f))
            ),
            weights, topK = 10
        )
        assertEquals("certain", ranked.first().memory.memoryId)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun importanceBreaksNearTies() {
        val query = floatArrayOf(1f, 0f, 0f)
        val ranked = Librarian.rank(
            query,
            listOf(
                cand(mem("low", importance = 1), floatArrayOf(1f, 0f, 0f)),
                cand(mem("high", importance = 5), floatArrayOf(1f, 0f, 0f))
            ),
            weights, topK = 10
        )
        assertEquals("high", ranked.first().memory.memoryId)
    }

    @Test
    fun topKLimitsResults() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = (1..10).map {
            cand(mem("m$it"), floatArrayOf(1f, 0f, 0f))
        }
        assertEquals(3, Librarian.rank(query, candidates, weights, topK = 3).size)
    }

    /* -------- Stage 3.2: the priority ladder as a blended boost (§12) -------- */

    @Test
    fun specificityBreaksTiesBetweenComparablyRelevantEntries() {
        val query = floatArrayOf(1f, 0f, 0f)
        // Same vector, same importance/recency: the more specific scope wins.
        val ranked = Librarian.rank(
            query,
            listOf(
                cand(mem("global", scope = "global"), floatArrayOf(1f, 0f, 0f),
                    boost = Librarian.retrievalBoost("global", false, emptyList(), "")),
                cand(mem("campaign", scope = "campaign"), floatArrayOf(1f, 0f, 0f),
                    boost = Librarian.retrievalBoost("campaign", false, emptyList(), ""))
            ),
            weights, topK = 10
        )
        assertEquals("campaign", ranked.first().memory.memoryId)
    }

    @Test
    fun weaklyRelevantSpecificEntryNeverBeatsStronglyRelevantBroadOne() {
        // §12.4: specificity is a preference among comparably relevant entries,
        // not a trump card. A campaign memory at low similarity must lose to a
        // global memory the conversation is actually about.
        val query = floatArrayOf(1f, 0f, 0f)
        val ranked = Librarian.rank(
            query,
            listOf(
                cand(mem("weak-specific", scope = "campaign"), floatArrayOf(0.35f, 0.94f, 0f),
                    boost = Librarian.retrievalBoost("campaign", true, emptyList(), "")),
                cand(mem("strong-broad", scope = "global"), floatArrayOf(0.95f, 0.31f, 0f),
                    boost = Librarian.retrievalBoost("global", false, emptyList(), ""))
            ),
            weights, topK = 10
        )
        assertEquals("strong-broad", ranked.first().memory.memoryId)
    }

    @Test
    fun ladderOrderIsCampaignFirstGlobalLast() {
        val order = listOf("campaign", "rp_character", "world", "project", "companion", "real_life", "global")
        val boosts = order.map { Librarian.retrievalBoost(it, false, emptyList(), "") }
        assertEquals(boosts, boosts.sortedDescending())
        assertTrue(boosts.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun selectedProjectBoostsItsMemories() {
        val inProject = Librarian.retrievalBoost("project", true, emptyList(), "")
        val notInProject = Librarian.retrievalBoost("project", false, emptyList(), "")
        assertTrue(inProject > notInProject)
    }

    @Test
    fun tagHitsAreSmallAndCapped() {
        val query = "we talked about the garden and the roses today"
        val none = Librarian.retrievalBoost("global", false, listOf("winter"), query)
        val one = Librarian.retrievalBoost("global", false, listOf("garden"), query)
        val many = Librarian.retrievalBoost("global", false,
            listOf("garden", "roses", "talked", "today", "about"), query)
        assertEquals(0.0, none, 1e-9)
        assertTrue(one > none)
        // Capped: a pile of matching tags can't outrank a scope tier.
        assertTrue(many <= one + 0.05)
        // Whole-word only: "rose" must not match inside "roses"... but "roses" does.
        assertEquals(0.0, Librarian.retrievalBoost("global", false, listOf("den"), query), 1e-9)
    }
}
