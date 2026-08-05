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

    private fun mem(
        id: String,
        importance: Int = 3,
        confidence: String? = "certain",
        scope: String = "global",
        title: String = id,
        content: String = id,
        embeddingText: String? = null,
        createdAt: String = "2026-07-01T00:00:00Z",
        updatedAt: String? = null
    ) =
        RetrievableMemory(
            memoryId = id, scope = scope, title = title, content = content,
            embeddingText = embeddingText, importance = importance,
            createdAt = createdAt, worldId = null, provenanceConfidence = confidence,
            updatedAt = updatedAt
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
    fun provenanceConfidenceDoesNotAffectRanking() {
        // Phase 2 review: provenance (confidence/source) is no longer read into
        // the score. Two candidates identical but for confidence score equally.
        val query = floatArrayOf(1f, 0f, 0f)
        val ranked = Librarian.rank(
            query,
            listOf(
                cand(mem("tentative", confidence = "tentative"), floatArrayOf(1f, 0f, 0f)),
                cand(mem("certain", confidence = "certain"), floatArrayOf(1f, 0f, 0f))
            ),
            weights, topK = 10
        )
        assertEquals(ranked[0].score, ranked[1].score, 1e-6f)
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

    /* -------- Phase A (counterplan §5.5): floor before top-K -------- */

    @Test
    fun relevanceFloorRunsBeforeTopK() {
        // An irrelevant-but-important memory must not consume a top-K slot
        // while relevant candidates wait below it. Old take-then-filter
        // behavior returned only one result here; floor-first returns both
        // relevant ones.
        val query = floatArrayOf(1f, 0f, 0f)
        val ranked = Librarian.rank(
            query,
            listOf(
                // cosine ≈ 0.25 — below the 0.3 floor, but importance 5 and
                // full recency give it the highest blended score.
                cand(mem("irrelevant-important", importance = 5), floatArrayOf(0.25f, 0.968f, 0f), recency = 1.0),
                cand(mem("relevant-strong", importance = 1), floatArrayOf(0.9f, 0.436f, 0f), recency = 0.0),
                cand(mem("relevant-weak", importance = 1), floatArrayOf(0.35f, 0.937f, 0f), recency = 0.0)
            ),
            weights, topK = 2, minSimilarity = 0.3f
        )
        assertEquals(listOf("relevant-strong", "relevant-weak"), ranked.map { it.memory.memoryId })
    }

    /* -------- Phase A: memory-doc-v2 lexical ranking -------- */

    private fun lex(
        memory: RetrievableMemory,
        tags: List<String> = emptyList(),
        recency: Double = 0.5,
        boost: Double = 0.0
    ) = Librarian.LexicalCandidate(memory, tags, recency, boost)

    @Test
    fun lexicalFindsTagOnlyMemory() {
        val hit = mem("tagged", content = "favorite flowers need daily water")
        val ranked = Librarian.rankLexical(
            "tell me about the garden",
            listOf(lex(hit, tags = listOf("garden"))),
            weights, topK = 5
        )
        assertEquals(listOf("tagged"), ranked.map { it.memory.memoryId })
    }

    @Test
    fun lexicalMatchesWholeTokensOnly() {
        val ranked = Librarian.rankLexical(
            "the cat ran off",
            listOf(lex(mem("catalog", content = "catalog of items stored inside"))),
            weights, topK = 5
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun lexicalSearchesCondensedTextToo() {
        val ranked = Librarian.rankLexical(
            "what about the lighthouse",
            listOf(lex(mem("condensed", content = "a long story", embeddingText = "the old lighthouse trip"))),
            weights, topK = 5
        )
        assertEquals(1, ranked.size)
    }

    @Test
    fun lexicalIgnoresTitle() {
        // Titles are retired (§3.1): a query term that appears ONLY in a
        // memory's title contributes nothing — the memory is not matched, and
        // there is no title bonus.
        val titleOnly = mem("title-only", title = "garden", content = "many plants grow here")
        val body = mem("body", title = "plot notes", content = "the garden layout")
        val ranked = Librarian.rankLexical(
            "garden", listOf(lex(titleOnly), lex(body)), weights, topK = 5
        )
        assertEquals(listOf("body"), ranked.map { it.memory.memoryId })
    }

    @Test
    fun lexicalAppliesTheSameRankingContract() {
        // Equal relevance: provenance no longer affects the score (Phase 2
        // review), so confidence does not reorder; a scope boost orders the more
        // specific entry first — the fallback must not bypass the approved
        // ranking contract (§5.5).
        val q = "remember the harvest festival"
        val certain = mem("certain", content = "the harvest festival")
        val tentative = mem("tentative", content = "the harvest festival", confidence = "tentative")
        val same = Librarian.rankLexical(q, listOf(lex(tentative), lex(certain)), weights, topK = 5)
        assertEquals("confidence must not change the lexical score", same[0].score, same[1].score, 1e-6f)

        val global = mem("global", content = "the harvest festival")
        val campaign = mem("campaign", scope = "campaign", content = "the harvest festival")
        val boosted = Librarian.rankLexical(
            q,
            listOf(
                lex(global, boost = Librarian.retrievalBoost("global", false, emptyList(), q)),
                lex(campaign, boost = Librarian.retrievalBoost("campaign", false, emptyList(), q))
            ),
            weights, topK = 5
        )
        assertEquals("campaign", boosted.first().memory.memoryId)
    }

    @Test
    fun lexicalBoostNeverInjectsAnUnrelatedMemory() {
        // Relevance is the gate: a candidate with zero token hits stays out
        // no matter how large its context boost is.
        val ranked = Librarian.rankLexical(
            "about the harvest festival",
            listOf(lex(mem("unrelated", content = "tax paperwork deadline"), boost = 1.0)),
            weights, topK = 5
        )
        assertTrue(ranked.isEmpty())
    }

    /* -------- Phase A: updated freshness -------- */

    @Test
    fun freshnessRanksByUpdatedAtOverCreatedAt() {
        val editedOld = mem("edited-old", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-07-10T00:00:00Z")
        val newerNeverEdited = mem("newer", createdAt = "2026-06-01T00:00:00Z")
        val fresh = Librarian.freshness(listOf(editedOld, newerNeverEdited))
        assertEquals(1.0, fresh["edited-old"]!!, 1e-9)
        assertEquals(0.0, fresh["newer"]!!, 1e-9)
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
