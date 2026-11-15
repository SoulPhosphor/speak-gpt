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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import kotlin.math.sqrt

class PreviousMessageSearchTest {

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

    /** A library searched by keywords: no memory has a vector. */
    private val pepper = cand("pepper", "Pepper is a tabby cat who eats salmon")
    private val juniper = cand("juniper", "Juniper the terrier walks at dawn")
    private val violin = cand("violin", "The violin lesson is on Thursday")
    private val library = listOf(pepper, juniper, violin)

    private fun message(text: String, vector: FloatArray? = null) = PreviousMessageSearch.Message(text, vector)

    private fun search(
        current: String,
        previous: List<String>,
        count: Int,
        corpus: List<LiveRetrieval.Candidate> = library,
        limit: Int = 5,
        useImportanceRatings: Boolean = true
    ) = PreviousMessageSearch.search(
        message(current), previous.map { message(it) }, count,
        LiveRetrieval.index(corpus), params, limit, useImportanceRatings
    )

    private fun ids(result: PreviousMessageSearch.Result) = result.ranked.map { it.memory.memoryId }

    @Test
    fun aFollowUpThatNamesNothingKeepsThePreviousMessagesMemory() {
        val alone = search("What should I feed her?", listOf("Tell me about Pepper"), count = 0)
        assertTrue(ids(alone).isEmpty())
        val result = search("What should I feed her?", listOf("Tell me about Pepper"), count = 1)
        assertEquals(listOf("pepper"), ids(result))
        assertEquals(listOf(1), result.found.map { it.messagesBack })
    }

    @Test
    fun withNoPreviousMessagesCountedTheResultIsTheNewMessagesOwn() {
        val index = LiveRetrieval.index(library)
        val own = SavedMemorySearch.search("Tell me about Juniper", null, index, params, 5, true) {
            LongMessageSearch.Reading.NONE
        }
        val result = search("Tell me about Juniper", listOf("Tell me about Pepper"), count = 0)
        assertEquals(own.ranked, result.ranked)
        assertEquals(own.ranked, result.current.ranked)
        assertTrue(result.previous.isEmpty())
        assertTrue(result.found.all { it.messagesBack == 0 })
        // A chat's first message has nothing before it, whatever the count.
        assertEquals(own.ranked, search("Tell me about Juniper", emptyList(), count = 3).ranked)
    }

    @Test
    fun aNewerMatchComesFirstEvenWhenAnOlderMatchIsStronger() {
        // "zither" is in no memory, so the new message is a weaker match for
        // Pepper than the previous message is for Juniper.
        val result = search("Pepper zither", listOf("Tell me about Juniper"), count = 1)
        assertEquals(listOf("pepper", "juniper"), ids(result))
        assertTrue(result.ranked[0].score < result.ranked[1].score)
        assertEquals(listOf(0, 1), result.found.map { it.messagesBack })
    }

    @Test
    fun olderMessagesFollowInOrderOfAge() {
        val result = search(
            "Tell me about the violin",
            listOf("Tell me about Pepper", "Tell me about Juniper"), count = 2
        )
        assertEquals(listOf("violin", "pepper", "juniper"), ids(result))
        assertEquals(listOf(0, 1, 2), result.found.map { it.messagesBack })
    }

    @Test
    fun aMemoryMatchedByTwoMessagesIsListedOnceAtItsNewestMatch() {
        val result = search(
            "Is Pepper asleep?", listOf("Tell me about Juniper", "Tell me about Pepper"), count = 2
        )
        assertEquals(listOf("pepper", "juniper"), ids(result))
        assertEquals(listOf(0, 1), result.found.map { it.messagesBack })
    }

    @Test
    fun onlyTheCountedNearestMessagesAreSearched() {
        val history = listOf("nothing here", "Tell me about Juniper", "Tell me about Pepper", "the violin")
        assertTrue(ids(search("And then?", history, count = 1)).isEmpty())
        assertEquals(listOf("juniper"), ids(search("And then?", history, count = 2)))
        assertEquals(listOf("juniper", "pepper"), ids(search("And then?", history, count = 3)))
        // Never more than three, and a negative count is none.
        val most = search("And then?", history, count = 9)
        assertEquals(listOf("juniper", "pepper"), ids(most))
        assertEquals(PreviousMessageSearch.MAX_PREVIOUS_MESSAGES, most.previous.size)
        assertTrue(ids(search("And then?", history, count = -1)).isEmpty())
    }

    @Test
    fun aPreviousMessageThatMatchesNothingAddsNothing() {
        val result = search("Tell me about Juniper", listOf("What a long day"), count = 1)
        assertEquals(listOf("juniper"), ids(result))
        assertEquals(1, result.previous.size)
        assertTrue(result.previous.single().ranked.isEmpty())
    }

    @Test
    fun messagesAreNotJoinedIntoOneSearch() {
        // Joined into one text, the follow-up's own words would dilute the
        // earlier message's subject below the relevance gate.
        val previous = "Tell me about Pepper"
        val current = "Which zither, oboe, tuba or cello suits a beginner?"
        assertTrue(ids(search("$previous $current", emptyList(), count = 0)).isEmpty())
        assertEquals(listOf("pepper"), ids(search(current, listOf(previous), count = 1)))
    }

    @Test
    fun earlierMessagesOnlyFillTheRoomTheNewMessageLeaves() {
        val result = search(
            "Pepper and Juniper", listOf("Tell me about the violin"), count = 1, limit = 2
        )
        assertEquals(setOf("pepper", "juniper"), ids(result).toSet())
        assertEquals(listOf("violin"), result.leftOutByLimit.map { it.scored.memory.memoryId })

        val roomForOne = search(
            "Tell me about Pepper", listOf("Juniper and the violin"), count = 1, limit = 2
        )
        assertEquals(2, roomForOne.ranked.size)
        assertEquals("pepper", ids(roomForOne).first())
        assertEquals(1, roomForOne.leftOutByLimit.size)
    }

    @Test
    fun aPlusThreeMemoryFromAnEarlierMessageIsAddedBeyondTheLimitOnlyWhenRatingsAreOn() {
        val corpus = listOf(pepper, juniper, cand("violin", "The violin lesson is on Thursday", importance = 3))
        val on = search("Pepper and Juniper", listOf("Tell me about the violin"), count = 1, corpus = corpus, limit = 2)
        assertEquals(listOf("violin"), ids(on).drop(2))
        assertTrue(on.leftOutByLimit.isEmpty())
        val off = search(
            "Pepper and Juniper", listOf("Tell me about the violin"), count = 1,
            corpus = corpus, limit = 2, useImportanceRatings = false
        )
        assertEquals(2, off.ranked.size)
        assertFalse("violin" in ids(off))
    }

    @Test
    fun aMemoryNoLongerEligibleIsNotCarried() {
        val history = listOf("Tell me about Pepper")
        assertEquals(listOf("pepper"), ids(search("What should I feed her?", history, count = 1)))
        val without = library - pepper
        assertTrue(ids(search("What should I feed her?", history, count = 1, corpus = without)).isEmpty())
        // An edited memory is matched by what it says now.
        val edited = without + cand("pepper", "The spare key is under the blue pot")
        assertTrue(ids(search("What should I feed her?", history, count = 1, corpus = edited)).isEmpty())
    }

    /* --------------------------- meaning search --------------------------- */

    private val onSubject = floatArrayOf(1f, 0f, 0f)
    private val elsewhere = floatArrayOf(0f, 1f, 0f)

    private fun meaning(id: String, similarity: Double, content: String = "note $id") =
        cand(id, content, vector = floatArrayOf(similarity.toFloat(), 0f, sqrt(1 - similarity * similarity).toFloat()))

    private fun noise() = listOf(0.18, 0.20, 0.21, 0.22, 0.23, 0.24, 0.25, 0.26, 0.27, 0.28)
        .mapIndexed { i, s -> meaning("noise$i", s) }

    @Test
    fun aSuppliedEarlierVectorIsUsedAndAMissingOneLeavesKeywordSearch() {
        val index = LiveRetrieval.index(noise() + meaning("subject", 0.70, content = "The harbour festival is in June"))
        fun run(previous: PreviousMessageSearch.Message) = PreviousMessageSearch.search(
            message("And after that?", elsewhere), listOf(previous), 1, index, params, 5, true
        )
        // Found by meaning alone: the earlier message shares no word with it.
        val byMeaning = run(message("that thing we planned", onSubject))
        assertEquals(listOf("subject"), ids(byMeaning))
        assertTrue(byMeaning.previous.single().whole.diagnostics.meaningRan)
        // Without a vector the same message cannot be found by meaning...
        val noVector = run(message("that thing we planned"))
        assertTrue(ids(noVector).isEmpty())
        assertFalse(noVector.previous.single().whole.diagnostics.meaningRan)
        // ...but keyword search still runs for it.
        assertEquals(listOf("subject"), ids(run(message("the harbour festival"))))
    }

    @Test
    fun anEarlierLongMessagesSectionMatchIsCarriedAndMarked() {
        val index = LiveRetrieval.index(noise() + meaning("subject", 0.70, content = "Pepper is a tabby cat"))
        fun filler(count: Int, from: Int) = (from until from + count).joinToString(" ") { "zq$it" }
        val long = filler(200, 0) + " Pepper seems unwell lately " + filler(96, 200)
        var sectionReads = 0
        val earlier = PreviousMessageSearch.Message(long, elsewhere) {
            sectionReads++
            LongMessageSearch.read(long, index, now = { 0L }) { text -> if ("Pepper" in text) onSubject else elsewhere }
        }
        val result = PreviousMessageSearch.search(
            message("What should I do?", elsewhere), listOf(earlier), 1, index, params, 5, true
        )
        assertEquals(listOf("subject"), ids(result))
        assertTrue(result.found.single().bySection)
        assertEquals(1, result.found.single().messagesBack)
        assertEquals(1, sectionReads)
        // Not counted, not read.
        PreviousMessageSearch.search(message("What should I do?", elsewhere), listOf(earlier), 0, index, params, 5, true)
        assertEquals(1, sectionReads)
    }

    @Test
    fun combiningLeavesTheNewMessagesResultUntouched() {
        val index = LiveRetrieval.index(library)
        fun one(text: String, limit: Int) = SavedMemorySearch.search(text, null, index, params, limit, true) {
            LongMessageSearch.Reading.NONE
        }
        val current = one("Pepper and Juniper", 5)
        val combined = PreviousMessageSearch.combine(current, listOf(one("the violin", 3)), 5, true)
        assertSame(current, combined.current)
        assertEquals(current.ranked, combined.ranked.take(current.ranked.size))
        assertEquals(current.ranked.size + 1, combined.ranked.size)
    }
}
