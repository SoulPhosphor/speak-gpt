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
 * The lorebook injection safety budget (entry count + character total),
 * factored into one shared, pure implementation (counterplan Step 1.6) so the
 * enforcer's lore-notes truncation and the classic fallback path always agree
 * on exactly what was injected versus cut — previously each kept its own copy
 * of this loop, which is how "the debug log shows the raw matches, not what
 * entered the prompt" happened in the first place.
 *
 * Semantics are unchanged from the original loops: as soon as one candidate
 * doesn't fit (entry limit reached, or it alone would blow the character
 * budget), the walk stops — no backfill, no reordering. Lore stays simple and
 * deterministic (counterplan §5.6); this only makes the stopping point
 * explainable instead of silent.
 */
object LoreBookBudget {

    data class Cut(val match: LoreBookMatch, val reason: String)

    data class Selection(val kept: List<LoreBookMatch>, val cut: List<Cut>) {
        /** Stable ids for only the entries that survived this budget walk. */
        val injectedEntryIds: List<String>
            get() = kept.map { it.entry.id }
    }

    /**
     * Walk [matches] in order (already deduped by the caller), keeping up to
     * [maxEntries] whose combined content length stays within [maxChars].
     * Everything from the first candidate that doesn't fit onward is cut with
     * the reason that stopped the walk — those later entries were never
     * individually oversized, they simply never got evaluated once the walk
     * stopped, same as the original behavior.
     */
    fun select(
        matches: List<LoreBookMatch>,
        maxEntries: Int,
        maxChars: Int
    ): Selection {
        val kept = ArrayList<LoreBookMatch>()
        val cut = ArrayList<Cut>()
        var chars = 0
        for ((index, match) in matches.withIndex()) {
            val reason = when {
                kept.size >= maxEntries -> "entry limit ($maxEntries) reached"
                kept.isNotEmpty() && chars + match.entry.content.length > maxChars ->
                    "character budget ($maxChars) reached"
                else -> null
            }
            if (reason != null) {
                for (rest in matches.subList(index, matches.size)) cut.add(Cut(rest, reason))
                break
            }
            chars += match.entry.content.length
            kept.add(match)
        }
        return Selection(kept, cut)
    }
}
