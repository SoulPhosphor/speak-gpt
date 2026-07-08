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

package org.teslasoft.assistant.preferences.memory.archivist

/**
 * Size planning for Archivist runs (owner answer 4, July 8 2026: the user may
 * queue any number of conversations; the display batches "due to size" and
 * the AI's context window is protected). Two independent plans, both pure so
 * they unit-test:
 *
 * - [splitIntoRequests]: one MODEL CALL never carries more than
 *   [MAX_REQUEST_CHARS] of transcript text. Conversations already go to the
 *   model one at a time; this splits a single oversized conversation (the
 *   owner's "30 pages long" case) across several calls, whole transcript
 *   rows at a time. Characters stand proxy for tokens (~4 chars/token, so
 *   200k chars ≈ 50k tokens — the cap a single stored transcript row already
 *   has).
 *
 * - [planBatches]: groups conversations into the DISPLAY batches behind the
 *   owner's approved wording ("Batch One / x of x Conversations"). Purely
 *   presentational grouping — it changes no request shape.
 */
object ArchivistBatchPlanner {

    const val MAX_REQUEST_CHARS = 200_000

    /** A display batch closes when it holds this much transcript text… */
    const val BATCH_MAX_CHARS = 400_000

    /** …or this many conversations, whichever comes first. */
    const val BATCH_MAX_CONVERSATIONS = 10

    /**
     * Split one conversation's transcript rows (by rendered size) into
     * request chunks of whole rows, each chunk at most [maxChars]. A single
     * row larger than the budget travels alone — rows are the atom (the
     * store caps them at 200k chars on write, so an oversized one is legacy
     * data, not the normal case). Returns lists of row INDICES into the
     * input, in order; never empty when the input isn't.
     */
    fun splitIntoRequests(rowSizes: List<Int>, maxChars: Int = MAX_REQUEST_CHARS): List<List<Int>> {
        if (rowSizes.isEmpty()) return emptyList()
        val chunks = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var currentSize = 0
        for ((index, size) in rowSizes.withIndex()) {
            if (current.isNotEmpty() && currentSize + size > maxChars) {
                chunks.add(current)
                current = ArrayList()
                currentSize = 0
            }
            current.add(index)
            currentSize += size
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    /**
     * Group conversations (by total rendered size, in run order) into
     * contiguous display batches. Returns index ranges into the input; one
     * range when everything fits a single batch (the plain "Conversation
     * x of x" display), several when the run is big enough to batch.
     */
    fun planBatches(
        conversationSizes: List<Int>,
        maxChars: Int = BATCH_MAX_CHARS,
        maxCount: Int = BATCH_MAX_CONVERSATIONS
    ): List<IntRange> {
        if (conversationSizes.isEmpty()) return emptyList()
        val batches = ArrayList<IntRange>()
        var start = 0
        var size = 0
        for (i in conversationSizes.indices) {
            val overSize = i > start && size + conversationSizes[i] > maxChars
            val overCount = i - start >= maxCount
            if (overSize || overCount) {
                batches.add(start until i)
                start = i
                size = 0
            }
            size += conversationSizes[i]
        }
        batches.add(start until conversationSizes.size)
        return batches
    }
}
