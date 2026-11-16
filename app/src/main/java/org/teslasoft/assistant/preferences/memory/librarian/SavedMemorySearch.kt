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
 * Shared live/test boundary: whole-message ranking first, one additional
 * reading, then section-only candidates. Eligibility is supplied by the store.
 *
 * [search]'s limit is the number of candidates handed on, not Maximum
 * Memories: live chat asks for more candidates than it may send, and the
 * enforcer's selection (RetrievalBackfill) applies Maximum Memories and the
 * +3 rule to them. A matching +3 memory is therefore always handed on, even
 * past the candidate limit, whether the whole message or a section found it.
 */
object SavedMemorySearch {
    data class Result(
        val whole: LiveRetrieval.Result,
        val sections: LongMessageSearch.Reading,
        val sectionMatches: List<LongMessageSearch.Match>,
        val ranked: List<ScoredMemory>,
        val sectionIds: Set<String>
    )

    fun search(
        query: String, queryVector: FloatArray?, index: LiveRetrieval.Index,
        params: LiveRetrieval.Params, limit: Int, useImportanceRatings: Boolean,
        plusThreeTopMatch: Boolean = true,
        readSections: () -> LongMessageSearch.Reading
    ): Result {
        val whole = LiveRetrieval.search(query, queryVector, index, params, limit,
            useImportanceRatings, includeMandatoryImportance = useImportanceRatings,
            plusThreeTopMatch = plusThreeTopMatch)
        val reading = if (queryVector != null) readSections() else LongMessageSearch.Reading.NONE
        val scoringStarted = System.nanoTime()
        val matches = LongMessageSearch.matches(reading, index, params, whole.explanations, useImportanceRatings)
        val scoringMillis = (System.nanoTime() - scoringStarted) / 1_000_000
        val sections = if (reading.sections.isEmpty()) reading else reading.copy(
            millis = reading.millis + scoringMillis, scoringMillis = scoringMillis)
        val included = ImportanceRanking.includeMandatory(
            matches, (limit - whole.ranked.size).coerceAtLeast(0)
        ) { ImportanceRanking.isMandatory(it.explanation.memory.importance.toDouble(), useImportanceRatings) }
        return Result(whole, sections, matches, whole.ranked + included.map { it.scored },
            included.map { it.explanation.memory.memoryId }.toSet())
    }
}
