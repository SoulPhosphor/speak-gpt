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

package org.teslasoft.assistant.preferences.lorebook

/**
 * Which matched lorebook entries are sent with one message. Typed sending,
 * voice and resending all use this one walk with the same settings
 * ([LorebookLimits]), so every way of sending sends the same entries.
 *
 * The matches arrive already deduplicated across books, in book order (the
 * companion's core lorebook first) and, inside a book, most recently edited
 * first. The walk keeps them in that order:
 *  - at most [maxEntries] entries; every later match is cut once that many
 *    are kept;
 *  - when a character limit is set ([maxChars] not null), an entry that would
 *    take the kept text past it is skipped, and later entries that still fit
 *    are kept. The limit is never exceeded.
 */
object LoreBookBudget {

    data class Cut(val match: LoreBookMatch, val reason: String)

    data class Selection(val kept: List<LoreBookMatch>, val cut: List<Cut>) {
        /** Stable ids for only the entries that survived this budget walk. */
        val injectedEntryIds: List<String>
            get() = kept.map { it.entry.id }
    }

    fun select(
        matches: List<LoreBookMatch>,
        maxEntries: Int,
        maxChars: Int?
    ): Selection {
        val kept = ArrayList<LoreBookMatch>()
        val cut = ArrayList<Cut>()
        var chars = 0
        for (match in matches) {
            val length = match.entry.content.length
            when {
                kept.size >= maxEntries -> cut.add(Cut(match, "entry limit ($maxEntries) reached"))
                maxChars != null && chars + length > maxChars ->
                    cut.add(Cut(match, "character budget ($maxChars) reached"))
                else -> {
                    chars += length
                    kept.add(match)
                }
            }
        }
        return Selection(kept, cut)
    }
}
