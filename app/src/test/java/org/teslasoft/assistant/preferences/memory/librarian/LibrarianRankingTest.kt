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

    private fun mem(id: String, importance: Int = 3, confidence: String? = "certain") =
        RetrievableMemory(
            memoryId = id, scope = "global", title = id, content = id,
            embeddingText = null, importance = importance, alwaysLoad = false,
            createdAt = "2026-07-01T00:00:00Z", worldId = null, provenanceConfidence = confidence
        )

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
        val near = mem("near")
        val far = mem("far")
        val ranked = Librarian.rank(
            query,
            listOf(
                Triple(far, floatArrayOf(0f, 1f, 0f), 0.5),
                Triple(near, floatArrayOf(0.9f, 0.1f, 0f), 0.5)
            ),
            weights, topK = 10
        )
        assertEquals("near", ranked.first().memory.memoryId)
    }

    @Test
    fun tentativeMemoriesAreDampened() {
        val query = floatArrayOf(1f, 0f, 0f)
        val certain = mem("certain", confidence = "certain")
        val tentative = mem("tentative", confidence = "tentative")
        // Identical vectors, importance, recency — only confidence differs.
        val ranked = Librarian.rank(
            query,
            listOf(
                Triple(tentative, floatArrayOf(1f, 0f, 0f), 0.5),
                Triple(certain, floatArrayOf(1f, 0f, 0f), 0.5)
            ),
            weights, topK = 10
        )
        assertEquals("certain", ranked.first().memory.memoryId)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun importanceBreaksNearTies() {
        val query = floatArrayOf(1f, 0f, 0f)
        val high = mem("high", importance = 5)
        val low = mem("low", importance = 1)
        val ranked = Librarian.rank(
            query,
            listOf(
                Triple(low, floatArrayOf(1f, 0f, 0f), 0.5),
                Triple(high, floatArrayOf(1f, 0f, 0f), 0.5)
            ),
            weights, topK = 10
        )
        assertEquals("high", ranked.first().memory.memoryId)
    }

    @Test
    fun topKLimitsResults() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = (1..10).map {
            Triple(mem("m$it"), floatArrayOf(1f, 0f, 0f), 0.5)
        }
        assertEquals(3, Librarian.rank(query, candidates, weights, topK = 3).size)
    }
}
