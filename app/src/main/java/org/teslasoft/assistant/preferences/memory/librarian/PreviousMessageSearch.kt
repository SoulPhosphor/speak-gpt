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

import org.teslasoft.assistant.preferences.memory.ImportanceRanking
import org.teslasoft.assistant.preferences.memory.ScoredMemory

/**
 * Searches saved memories with the new message and with the user's previous
 * messages, each on its own (conversation_recall_plan.md G.9a). Pure Kotlin,
 * unit tested in PreviousMessageSearchTest and measured on the saved-memory
 * test set's conversations in PreviousMessageRecallTest.
 *
 * Nothing in the app calls this yet: using it when a message is sent, the
 * setting for how many previous messages are searched, and preparing and
 * keeping earlier-message vectors all belong to the carry-over step.
 *
 * What it returns is a list of CANDIDATES, in the same form the existing
 * search hands to the enforcer. It decides nothing about Maximum Memories:
 * the enforcer's selection (RetrievalBackfill) walks the candidates in
 * order, applies its own filters, fills freed places from later candidates,
 * applies Maximum Memories, and adds every matching +3 memory that the count
 * would otherwise leave out. That selection is unchanged and is the only
 * place those rules live.
 *
 *  - Every message is its own search through [SavedMemorySearch], the same
 *    search a message gets when it is the one being sent. Messages are never
 *    joined into one text.
 *  - A memory found through an earlier message has passed the relevance gate
 *    against that message. Nothing is returned merely because it was sent
 *    before; this object keeps no result from one call to the next.
 *  - The new message's candidates come first, exactly as the existing
 *    search returns them. Candidates for the message before it follow, then
 *    for the one before that. Within one message the order is that
 *    message's own. A memory matched by several messages is listed once, at
 *    its newest match.
 *  - The candidate limit is the one the existing search already has: the
 *    number of candidates handed on, which the enforcer sets above Maximum
 *    Memories so it has spare candidates. Earlier messages add candidates
 *    only up to that same number. A matching +3 memory is always handed on,
 *    past the candidate limit too, whichever message found it and whether
 *    the whole message or a section found it.
 *  - The caller supplies only the user's messages, the vectors it already
 *    has, and the memories eligible now ([LiveRetrieval.Index]), so a changed
 *    scope or an edited or deleted memory takes effect on the next call.
 */
object PreviousMessageSearch {

    /** Most previous messages one search uses. */
    const val MAX_PREVIOUS_MESSAGES = 3

    /**
     * One user message to search with. Made with [withVector] or, only when
     * meaning search cannot run at all, [keywordsOnly].
     */
    class Message private constructor(
        val text: String,
        /** The message's embedding, or null for a [keywordsOnly] message. */
        val vector: FloatArray?,
        /** Reads the message's sections ([LongMessageSearch]). Asked at most
         *  once per search, and never for a [keywordsOnly] message. A caller
         *  that already holds the reading returns it instead of reading the
         *  message again. */
        val readSections: () -> LongMessageSearch.Reading
    ) {
        companion object {
            /** A message with its embedding: meaning and keyword search. */
            fun withVector(
                text: String,
                vector: FloatArray,
                readSections: () -> LongMessageSearch.Reading = { LongMessageSearch.Reading.NONE }
            ) = Message(text, vector, readSections)

            /**
             * A message searched by keywords alone. This is for when meaning
             * search is unavailable for the whole search (no embedding model,
             * or the model could not read the message), exactly as the
             * existing search falls back for a new message. It is not a way
             * to skip making an earlier message's embedding: the caller makes
             * a missing one first (G.9a). Every message searched this way is
             * named in [Result.searchedByKeywordsOnly] so it cannot pass
             * unnoticed.
             */
            fun keywordsOnly(text: String) = Message(text, null) { LongMessageSearch.Reading.NONE }
        }
    }

    /** One candidate and the message that found it. */
    data class Found(
        val scored: ScoredMemory,
        /** 0 for the new message, 1 for the message before it, and so on. */
        val messagesBack: Int,
        /** True when a section of that message found it, not the whole message. */
        val bySection: Boolean
    )

    data class Result(
        /** The candidates for the enforcer's selection, in order. */
        val ranked: List<ScoredMemory>,
        /** [ranked], with the message that found each candidate. */
        val found: List<Found>,
        /** The new message's own search, exactly as without previous messages. */
        val current: SavedMemorySearch.Result,
        /** Each searched previous message's own complete search, nearest first. */
        val previous: List<SavedMemorySearch.Result>,
        /** Earlier-message matches not handed on because the candidate limit
         *  was reached. Never a matching +3 memory. */
        val beyondCandidateLimit: List<Found>,
        /** The messages searched without an embedding, as messages back (0 is
         *  the new message). Empty when every message had its embedding. An
         *  earlier message listed here while the new message is not means an
         *  embedding that should have been made was missing. */
        val searchedByKeywordsOnly: List<Int> = emptyList()
    )

    /**
     * Searches [index] with [current] and the nearest [previousCount]
     * (0 to [MAX_PREVIOUS_MESSAGES]) of [previousUserMessages], which holds
     * the user's earlier messages nearest first. With [previousCount] 0, or no
     * previous message, the result is exactly [current]'s own.
     *
     * [candidateLimit] is the number of candidates to hand on, the same
     * number the existing search is given. It is not Maximum Memories.
     */
    fun search(
        current: Message,
        previousUserMessages: List<Message>,
        previousCount: Int,
        index: LiveRetrieval.Index,
        params: LiveRetrieval.Params,
        candidateLimit: Int,
        useImportanceRatings: Boolean,
        /** The +3 top-match rule (G.10); off reproduces the Step 3 records. */
        plusThreeTopMatch: Boolean = true
    ): Result {
        val newest = SavedMemorySearch.search(
            current.text, current.vector, index, params, candidateLimit, useImportanceRatings,
            plusThreeTopMatch, readSections = current.readSections
        )
        // An earlier message's search is not cut: what it may add is decided
        // once, in combine(), after the newer candidates.
        val everything = maxOf(candidateLimit, index.corpus.size)
        val earlier = previousUserMessages.take(previousCount.coerceIn(0, MAX_PREVIOUS_MESSAGES)).map { message ->
            SavedMemorySearch.search(
                message.text, message.vector, index, params, everything, useImportanceRatings,
                plusThreeTopMatch, readSections = message.readSections
            )
        }
        val searched = listOf(current) + previousUserMessages.take(earlier.size)
        return combine(newest, earlier, candidateLimit, useImportanceRatings).copy(
            searchedByKeywordsOnly = searched.indices.filter { searched[it].vector == null }
        )
    }

    /**
     * Puts searches that were already run together: [current]'s candidates
     * first and untouched, then what each of [previous] (nearest first, each
     * run without a limit) found that no newer message did, up to
     * [candidateLimit] candidates in all plus every matching +3 memory.
     */
    fun combine(
        current: SavedMemorySearch.Result,
        previous: List<SavedMemorySearch.Result>,
        candidateLimit: Int,
        useImportanceRatings: Boolean
    ): Result {
        val seen = HashSet<String>()
        val head = current.ranked.map { scored ->
            seen.add(scored.memory.memoryId)
            Found(scored, 0, scored.memory.memoryId in current.sectionIds)
        }
        val earlier = ArrayList<Found>()
        previous.forEachIndexed { position, result ->
            for (scored in result.ranked) {
                val id = scored.memory.memoryId
                if (seen.add(id)) earlier.add(Found(scored, position + 1, id in result.sectionIds))
            }
        }
        val room = (candidateLimit - head.size).coerceAtLeast(0)
        val added = ImportanceRanking.includeMandatory(earlier, room) { found ->
            ImportanceRanking.isMandatory(found.scored.memory.importance.toDouble(), useImportanceRatings)
        }
        val found = head + added
        val addedIds = added.map { it.scored.memory.memoryId }.toSet()
        return Result(
            ranked = found.map { it.scored },
            found = found,
            current = current,
            previous = previous,
            beyondCandidateLimit = earlier.filter { it.scored.memory.memoryId !in addedIds }
        )
    }
}
