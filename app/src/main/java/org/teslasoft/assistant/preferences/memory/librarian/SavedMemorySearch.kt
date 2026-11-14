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

import org.teslasoft.assistant.preferences.memory.ScoredMemory

/** Shared live/test boundary: whole-message ranking first, one additional
 * reading, then section-only candidates. Eligibility is supplied by the store. */
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
        readSections: () -> LongMessageSearch.Reading
    ): Result {
        val whole = LiveRetrieval.search(query, queryVector, index, params, limit,
            useImportanceRatings, includeMandatoryImportance = useImportanceRatings)
        val reading = if (queryVector != null) readSections() else LongMessageSearch.Reading.NONE
        val scoringStarted = System.nanoTime()
        val matches = LongMessageSearch.matches(reading, index, params, whole.explanations, useImportanceRatings)
        val scoringMillis = (System.nanoTime() - scoringStarted) / 1_000_000
        val sections = if (reading.sections.isEmpty()) reading else reading.copy(
            millis = reading.millis + scoringMillis, scoringMillis = scoringMillis)
        val included = matches.take((limit - whole.ranked.size).coerceAtLeast(0))
        return Result(whole, sections, matches, whole.ranked + included.map { it.scored },
            included.map { it.explanation.memory.memoryId }.toSet())
    }
}
