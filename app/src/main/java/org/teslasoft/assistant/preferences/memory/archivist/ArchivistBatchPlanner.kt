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
 * Display planning for Archivist runs (owner answer 4, July 8 2026: the user
 * may queue any number of conversations and the display batches "due to
 * size"). Request sizing is token-based and lives in
 * [ArchivistConversationChunker]; this object only groups conversations into
 * the DISPLAY batches behind the
 *   owner's approved wording ("Batch One / x of x Conversations"). Purely
 *   presentational grouping — it changes no request shape.
 */
object ArchivistBatchPlanner {

    /** A display batch closes when it holds this much transcript text… */
    const val BATCH_MAX_CHARS = 400_000

    /** …or this many conversations, whichever comes first. */
    const val BATCH_MAX_CONVERSATIONS = 10

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
