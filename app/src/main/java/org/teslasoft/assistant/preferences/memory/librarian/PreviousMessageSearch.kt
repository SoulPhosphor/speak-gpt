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
 *  - Every message is its own search through [SavedMemorySearch], the same
 *    search a message gets when it is the one being sent. Messages are never
 *    joined into one text.
 *  - A memory found through an earlier message has passed the relevance gate
 *    against that message. Nothing is returned merely because it was sent
 *    before; this object keeps no result from one call to the next.
 *  - A match for the new message comes before a match for the message before
 *    it, which comes before a match for the one before that. Within one
 *    message the order is that message's own.
 *  - A memory matched by several messages is listed once, at its newest
 *    match.
 *  - The new message's result is never changed, shortened or reordered.
 *    Earlier messages only fill the room it leaves under the memory limit;
 *    beyond the limit only a +3 memory is added, as in every other search
 *    ([ImportanceRanking.includeMandatory]).
 *  - The caller supplies only the user's messages, the vectors it already
 *    has, and the memories eligible now ([LiveRetrieval.Index]), so a changed
 *    scope or an edited or deleted memory takes effect on the next call.
 */
object PreviousMessageSearch {

    /** Most previous messages one search uses. */
    const val MAX_PREVIOUS_MESSAGES = 3

    /**
     * One user message to search with.
     *
     * [vector] is the message's embedding when the caller has one, or null:
     * keyword search then runs alone for that message, as it does for a new
     * message when meaning search is unavailable. [readSections] reads the
     * message's sections ([LongMessageSearch]) and is only asked when the
     * message has a vector.
     */
    class Message(
        val text: String,
        val vector: FloatArray?,
        val readSections: () -> LongMessageSearch.Reading = { LongMessageSearch.Reading.NONE }
    )

    /** One returned memory and the message that found it. */
    data class Found(
        val scored: ScoredMemory,
        /** 0 for the new message, 1 for the message before it, and so on. */
        val messagesBack: Int,
        /** True when a section of that message found it, not the whole message. */
        val bySection: Boolean
    )

    data class Result(
        /** What would be sent, in order. */
        val ranked: List<ScoredMemory>,
        /** [ranked], with the message that found each memory. */
        val found: List<Found>,
        /** The new message's own search, exactly as without previous messages. */
        val current: SavedMemorySearch.Result,
        /** Each searched previous message's own complete search, nearest first. */
        val previous: List<SavedMemorySearch.Result>,
        /** Memories an earlier message matched that the memory limit left out. */
        val leftOutByLimit: List<Found>
    )

    /**
     * Searches [index] with [current] and the nearest [previousCount]
     * (0 to [MAX_PREVIOUS_MESSAGES]) of [previousUserMessages], which holds
     * the user's earlier messages nearest first. With [previousCount] 0, or no
     * previous message, the result is exactly [current]'s own.
     */
    fun search(
        current: Message,
        previousUserMessages: List<Message>,
        previousCount: Int,
        index: LiveRetrieval.Index,
        params: LiveRetrieval.Params,
        limit: Int,
        useImportanceRatings: Boolean
    ): Result {
        val newest = SavedMemorySearch.search(
            current.text, current.vector, index, params, limit, useImportanceRatings, current.readSections
        )
        // An earlier message's search is not cut at the limit: what it may
        // add is decided once, in combine(), after the newer matches.
        val everything = maxOf(limit, index.corpus.size)
        val earlier = previousUserMessages.take(previousCount.coerceIn(0, MAX_PREVIOUS_MESSAGES)).map { message ->
            SavedMemorySearch.search(
                message.text, message.vector, index, params, everything, useImportanceRatings, message.readSections
            )
        }
        return combine(newest, earlier, limit, useImportanceRatings)
    }

    /**
     * Puts searches that were already run together: [current]'s result first
     * and untouched, then what each of [previous] (nearest first, each run
     * without a limit) found that no newer message did.
     */
    fun combine(
        current: SavedMemorySearch.Result,
        previous: List<SavedMemorySearch.Result>,
        limit: Int,
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
        val room = (limit - head.size).coerceAtLeast(0)
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
            leftOutByLimit = earlier.filter { it.scored.memory.memoryId !in addedIds }
        )
    }
}
