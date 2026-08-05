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
import org.teslasoft.assistant.preferences.memory.enforcer.RetrievalBackfill

/**
 * The production search pipeline ([Librarian.searchCore]) driven with real
 * ranking code and in-memory corpus data: the complete-set decision between
 * semantic and lexical ranking, partial/stale-vector fallback, embed-failure
 * fallback, alias discovery, and composition with post-filter backfill.
 * (The SQL scope gates and SQLCipher store cannot run on the JVM; they stay
 * covered by the deferred instrumented/golden-corpus work.)
 */
class LibrarianSearchCoreTest {

    private val weights = Librarian.Weights(0.6, 0.3, 0.1)

    private fun mem(
        id: String,
        content: String = id,
        importance: Int = 3
    ) = RetrievableMemory(
        memoryId = id, scope = "global", content = content,
        embeddingText = null, importance = importance,
        createdAt = "2026-07-01T00:00:00Z", worldId = null
    )

    private fun row(
        memory: RetrievableMemory,
        vector: FloatArray? = null,
        tags: List<String> = emptyList(),
        aliases: List<String> = emptyList(),
        recency: Double = 0.5,
        boost: Double = 0.0
    ) = Librarian.CorpusMemory(memory, vector, tags, aliases, recency, boost)

    @Test
    fun completeIndexUsesSemanticRankingWithTheFloor() {
        var embedCalls = 0
        val result = Librarian.searchCore(
            "anything",
            { embedCalls++; floatArrayOf(1f, 0f, 0f) },
            listOf(
                row(mem("near"), vector = floatArrayOf(0.9f, 0.436f, 0f)),
                row(mem("orthogonal"), vector = floatArrayOf(0f, 1f, 0f))
            ),
            weights, topK = 10
        )
        assertEquals(1, embedCalls)
        // Semantic path: the orthogonal memory is below the relevance floor.
        assertEquals(listOf("near"), result.map { it.memory.memoryId })
    }

    @Test
    fun oneMissingVectorRoutesTheWholeTurnToLexical() {
        var embedCalls = 0
        val result = Librarian.searchCore(
            "the harvest festival plans",
            { embedCalls++; floatArrayOf(1f, 0f, 0f) },
            listOf(
                // Has a current vector, but its text is unrelated.
                row(mem("vectored", content = "tax paperwork deadline"), vector = floatArrayOf(1f, 0f, 0f)),
                // Newly edited/imported: no current vector — must not disappear.
                row(mem("vectorless", content = "the harvest festival"))
            ),
            weights, topK = 10
        )
        assertEquals(0, embedCalls)
        assertEquals(listOf("vectorless"), result.map { it.memory.memoryId })
    }

    @Test
    fun queryEmbedFailureFallsBackToLexicalOverTheCompleteSet() {
        val result = Librarian.searchCore(
            "the harvest festival plans",
            { null },
            listOf(
                row(mem("match", content = "the harvest festival"), vector = floatArrayOf(1f, 0f, 0f)),
                row(mem("other", content = "tax paperwork deadline"), vector = floatArrayOf(0f, 1f, 0f))
            ),
            weights, topK = 10
        )
        assertEquals(listOf("match"), result.map { it.memory.memoryId })
    }

    @Test
    fun noModelMeansLexicalOverEverything() {
        val result = Librarian.searchCore(
            "remember the lighthouse",
            { null },
            listOf(
                row(mem("hit", content = "the old lighthouse trip")),
                row(mem("miss", content = "gym schedule"))
            ),
            weights, topK = 10
        )
        assertEquals(listOf("hit"), result.map { it.memory.memoryId })
    }

    @Test
    fun emptyCorpusReturnsEmptyWithoutEmbedding() {
        var embedCalls = 0
        val result = Librarian.searchCore("query", { embedCalls++; null }, emptyList(), weights, topK = 5)
        assertTrue(result.isEmpty())
        assertEquals(0, embedCalls)
    }

    @Test
    fun targetAliasMakesAMemoryFindable() {
        val result = Librarian.searchCore(
            "what happened at Ravenhold",
            { null },
            listOf(
                row(
                    mem("siege", content = "it lasted a month"),
                    aliases = listOf("Ravenhold")
                )
            ),
            weights, topK = 5
        )
        assertEquals(listOf("siege"), result.map { it.memory.memoryId })
    }

    @Test
    fun modeCallbackReportsSemanticWhenTheIndexIsComplete() {
        val modes = ArrayList<Boolean>()
        Librarian.searchCore(
            "anything",
            { floatArrayOf(1f, 0f, 0f) },
            listOf(row(mem("a"), vector = floatArrayOf(1f, 0f, 0f))),
            weights, topK = 5
        ) { modes.add(it) }
        assertEquals(listOf(true), modes)
    }

    @Test
    fun modeCallbackReportsLexicalOnAPartialIndex() {
        val modes = ArrayList<Boolean>()
        Librarian.searchCore(
            "the harvest festival",
            { floatArrayOf(1f, 0f, 0f) },
            listOf(
                row(mem("vectored", content = "the harvest festival"), vector = floatArrayOf(1f, 0f, 0f)),
                row(mem("vectorless", content = "the harvest festival"))
            ),
            weights, topK = 5
        ) { modes.add(it) }
        assertEquals(listOf(false), modes)
    }

    @Test
    fun modeCallbackReportsLexicalWhenTheQueryEmbedFails() {
        val modes = ArrayList<Boolean>()
        Librarian.searchCore(
            "the harvest festival",
            { null },
            listOf(row(mem("match", content = "the harvest festival"), vector = floatArrayOf(1f, 0f, 0f))),
            weights, topK = 5
        ) { modes.add(it) }
        assertEquals(listOf(false), modes)
    }

    @Test
    fun filteredCandidatesBackfillFromTheRankedPool() {
        // Four relevant memories, importance forcing the order m1 > m2 > m3 > m4.
        val corpus = (1..4).map { i ->
            row(mem("m$i", content = "the harvest festival", importance = 6 - i))
        }
        val pool = Librarian.searchCore("the harvest festival", { null }, corpus, weights, topK = Int.MAX_VALUE)
        assertEquals(listOf("m1", "m2", "m3", "m4"), pool.map { it.memory.memoryId })
        // A cooldown-style filter removes m2; the next ranked candidate fills in.
        val selection = RetrievalBackfill.select(pool, topK = 2) { it.memory.memoryId != "m2" }
        assertEquals(listOf("m1", "m3"), selection.kept.map { it.memory.memoryId })
        assertFalse(selection.scanCapReached)
    }
}
