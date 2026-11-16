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
import org.teslasoft.assistant.preferences.memory.ImportanceRanking
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.RetrievalPolicy
import org.teslasoft.assistant.preferences.memory.enforcer.RetrievalBackfill
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

    private fun message(text: String, vector: FloatArray? = null) =
        if (vector == null) PreviousMessageSearch.Message.keywordsOnly(text)
        else PreviousMessageSearch.Message.withVector(text, vector)

    /** The number of candidates live chat asks the search for at the default
     *  Maximum Memories: the enforcer's scan cap and one more. */
    private val liveCandidateLimit = RetrievalBackfill.scanCap(RetrievalPolicy.DEFAULT_TOP_K) + 1

    private fun search(
        current: String,
        previous: List<String>,
        count: Int,
        corpus: List<LiveRetrieval.Candidate> = library,
        candidateLimit: Int = liveCandidateLimit,
        useImportanceRatings: Boolean = true
    ) = PreviousMessageSearch.search(
        message(current), previous.map { message(it) }, count,
        LiveRetrieval.index(corpus), params, candidateLimit, useImportanceRatings
    )

    /**
     * The enforcer's own selection over the candidates, called the way the
     * enforcer calls it: Maximum Memories, its scan cap, the +3 rule, and a
     * filter standing in for its lore-overlap and memory-space checks.
     */
    private fun sent(
        result: PreviousMessageSearch.Result,
        maximumMemories: Int,
        useImportanceRatings: Boolean = true,
        survives: (String) -> Boolean = { true }
    ): List<String> = RetrievalBackfill.select(
        result.ranked, maximumMemories, RetrievalBackfill.scanCap(maximumMemories),
        isMandatory = { ImportanceRanking.isMandatory(it.memory.importance.toDouble(), useImportanceRatings) }
    ) { survives(it.memory.memoryId) }.kept.map { it.memory.memoryId }

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

    /* ---------------- candidates, not the final selection ---------------- */

    @Test
    fun atTheLiveCandidateLimitEveryMatchIsHandedToTheEnforcer() {
        val result = search("Pepper and Juniper", listOf("Tell me about the violin"), count = 1)
        assertEquals(setOf("pepper", "juniper"), ids(result).take(2).toSet())
        assertEquals("violin", ids(result).last())
        assertTrue(result.beyondCandidateLimit.isEmpty())
    }

    @Test
    fun earlierMessagesAddCandidatesOnlyUpToTheCandidateLimit() {
        val result = search(
            "Pepper and Juniper", listOf("Tell me about the violin"), count = 1, candidateLimit = 2
        )
        assertEquals(setOf("pepper", "juniper"), ids(result).toSet())
        assertEquals(listOf("violin"), result.beyondCandidateLimit.map { it.scored.memory.memoryId })

        val roomForOne = search(
            "Tell me about Pepper", listOf("Juniper and the violin"), count = 1, candidateLimit = 2
        )
        assertEquals(2, roomForOne.ranked.size)
        assertEquals("pepper", ids(roomForOne).first())
        assertEquals(1, roomForOne.beyondCandidateLimit.size)
    }

    @Test
    fun aMatchingPlusThreeFromAnEarlierMessageIsHandedOnPastTheCandidateLimit() {
        val corpus = listOf(pepper, juniper, cand("violin", "The violin lesson is on Thursday", importance = 3))
        val on = search(
            "Pepper and Juniper", listOf("Tell me about the violin"), count = 1, corpus = corpus, candidateLimit = 2
        )
        assertEquals(listOf("violin"), ids(on).drop(2))
        assertTrue(on.beyondCandidateLimit.isEmpty())
        // +2 is ordinary, and so is +3 while importance ratings are off.
        val two = listOf(pepper, juniper, cand("violin", "The violin lesson is on Thursday", importance = 2))
        assertFalse("violin" in ids(search(
            "Pepper and Juniper", listOf("Tell me about the violin"), count = 1, corpus = two, candidateLimit = 2
        )))
        val off = search(
            "Pepper and Juniper", listOf("Tell me about the violin"), count = 1,
            corpus = corpus, candidateLimit = 2, useImportanceRatings = false
        )
        assertEquals(2, off.ranked.size)
        assertFalse("violin" in ids(off))
    }

    /* ------------------ through the enforcer's selection ------------------ */

    private fun many(prefix: String, word: String, count: Int, importance: Int) =
        (0 until count).map { cand("$prefix%02d".format(it), "$word note $prefix$it", importance = importance) }

    @Test
    fun maximumMemoriesIsAppliedByTheEnforcerNotByTheSearch() {
        // The same candidates give different results at different limits.
        val result = search("Tell me about Juniper", listOf("Tell me about Pepper", "the violin"), count = 2)
        assertEquals(listOf("juniper", "pepper", "violin"), ids(result))
        assertEquals(listOf("juniper"), sent(result, maximumMemories = 1))
        assertEquals(listOf("juniper", "pepper"), sent(result, maximumMemories = 2))
        assertEquals(listOf("juniper", "pepper", "violin"), sent(result, maximumMemories = 8))
    }

    @Test
    fun aFilteredCandidateIsBackfilledFromAnEarlierMessagesCandidates() {
        val result = search("Pepper and Juniper", listOf("Tell me about the violin"), count = 1)
        val first = ids(result).first()
        val second = ids(result)[1]
        // The enforcer drops the second candidate (lore overlap or memory
        // space); the earlier message's candidate takes the freed place.
        assertEquals(listOf(first, "violin"), sent(result, maximumMemories = 2) { it != second })
        assertEquals(listOf(first, second), sent(result, maximumMemories = 2))
    }

    @Test
    fun tenPlusTwoMemoriesFillFivePlacesAndThreePlusThreeMemoriesAreAdded() {
        // The owner's example, with the +3 memories found by the previous
        // message: five normal places, then every matching +3 memory.
        val corpus = many("two", "kiln", 10, importance = 2) + many("three", "harbour", 3, importance = 3) +
            listOf(pepper, juniper)
        val result = search("kiln", listOf("harbour"), count = 1, corpus = corpus)
        assertEquals(
            listOf("two00", "two01", "two02", "two03", "two04", "three00", "three01", "three02"),
            sent(result, maximumMemories = 5)
        )
        // The other way round: +3 memories found by the new message take
        // normal places first; the rest are still added. +2 never is.
        val swapped = search("harbour", listOf("kiln"), count = 1, corpus = corpus)
        assertEquals(listOf("three00", "three01", "three02", "two00", "two01"), sent(swapped, maximumMemories = 5))
        assertEquals(listOf("three00", "three01", "three02"), sent(swapped, maximumMemories = 2))
        // With importance ratings off, +3 has no protection.
        val off = search("kiln", listOf("harbour"), count = 1, corpus = corpus, useImportanceRatings = false)
        assertEquals(5, sent(off, maximumMemories = 5, useImportanceRatings = false).size)
    }

    @Test
    fun aPlusThreeFromAnEarlierMessageBeyondTheCandidateCutoffIsStillSent() {
        // More ordinary matches than the enforcer will ever examine at
        // Maximum Memories 1, then a +3 and a +2 the previous message matched.
        val cutoff = RetrievalBackfill.scanCap(1) + 1
        val corpus = many("two", "kiln", cutoff + 4, importance = 2) +
            cand("protected", "harbour master", importance = 3) + cand("ordinary", "harbour wall", importance = 2)
        val result = search("kiln", listOf("harbour"), count = 1, corpus = corpus, candidateLimit = cutoff)
        assertEquals(cutoff, result.current.ranked.size)
        assertEquals("protected", ids(result).last())
        assertFalse("ordinary" in ids(result))
        assertEquals(listOf("ordinary"), result.beyondCandidateLimit.map { it.scored.memory.memoryId })
        assertEquals(listOf("two00", "protected"), sent(result, maximumMemories = 1))
        // Even when every ordinary candidate is filtered out.
        assertEquals(listOf("protected"), sent(result, maximumMemories = 1) { it == "protected" })
    }

    @Test
    fun aPlusThreeThatDoesNotFitIsNotSentAndAPlusTwoNeverPassesTheLimit() {
        val corpus = listOf(pepper, cand("violin", "The violin lesson is on Thursday", importance = 3),
            cand("juniper", "Juniper the terrier walks at dawn", importance = 2))
        val result = search("Tell me about Pepper", listOf("Juniper and the violin"), count = 1, corpus = corpus)
        assertEquals(listOf("pepper", "violin"), sent(result, maximumMemories = 1))
        // Request capacity still applies to a +3 memory.
        assertEquals(listOf("pepper"), sent(result, maximumMemories = 1) { it != "violin" })
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
        // A message searched without its embedding is always named.
        assertTrue(byMeaning.searchedByKeywordsOnly.isEmpty())
        assertEquals(listOf(1), noVector.searchedByKeywordsOnly)
        assertEquals(listOf(0, 1, 2), search("And then?", listOf("a", "b", "c"), count = 2).searchedByKeywordsOnly)
        // ...but keyword search still runs for it.
        assertEquals(listOf("subject"), ids(run(message("the harbour festival"))))
    }

    @Test
    fun anEarlierLongMessagesSectionMatchIsCarriedAndMarked() {
        val index = LiveRetrieval.index(noise() + meaning("subject", 0.70, content = "Pepper is a tabby cat"))
        fun filler(count: Int, from: Int) = (from until from + count).joinToString(" ") { "zq$it" }
        val long = filler(200, 0) + " Pepper seems unwell lately " + filler(96, 200)
        var sectionReads = 0
        val earlier = PreviousMessageSearch.Message.withVector(long, elsewhere) {
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

    @Test
    fun aMemoryOutsideTheCurrentScopeIsNotCarriedFromAnEarlierMessage() {
        // The library is whatever is eligible for this chat now. A memory
        // the previous message matched under another companion or project
        // is not in it, so it cannot be returned.
        val history = listOf("Tell me about Pepper", "Tell me about Juniper")
        val thisScope = listOf(juniper, violin)
        assertEquals(listOf("juniper"), ids(search("And then?", history, count = 2, corpus = thisScope)))
    }

    @Test
    fun aBlankPreviousMessageFindsNothingAndStillUsesItsPlace() {
        // Which messages are passed in is the caller's choice; this states
        // what happens to the ones that are.
        val history = listOf("   ", "Tell me about Pepper")
        assertTrue(ids(search("And then?", history, count = 1)).isEmpty())
        assertEquals(listOf("pepper"), ids(search("And then?", history, count = 2)))
    }
}
