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

package org.teslasoft.assistant.preferences.memory.enforcer

/**
 * Post-filter backfill (counterplan §10 A.2): retrieval hands the enforcer a
 * ranked candidate pool, not a final list it can only shrink. The enforcer
 * consumes candidates in rank order until the final top-K survive its
 * filters (cooldown, lore overlap), relevance is exhausted, or the
 * documented scan cap is reached — a filtered-out candidate frees its slot
 * for the next relevant one instead of silently shrinking the prompt.
 * Pure Kotlin, unit tested (RetrievalBackfillTest).
 */
object RetrievalBackfill {

    /**
     * Examination margin beyond the requested top-K. The scan cap for a turn
     * is top-K + this margin, so backfill headroom exists even at the maximum
     * policy top-K (a fixed cap equal to the maximum top-K would leave no
     * room to backfill). The margin bounds expensive per-candidate work. A +3
     * candidate may still be examined after this cap because the cap must not
     * silently drop a mandatory memory that was not already in the normal
     * top-K. Reaching the cap is recorded in assembly notes.
     */
    const val SCAN_MARGIN = 64

    /** The documented scan cap for one turn's ordinary candidate walk. */
    fun scanCap(topK: Int): Int = topK + SCAN_MARGIN

    data class Selection<T>(
        val kept: List<T>,
        val examined: Int,
        /** True when the cap blocked an ordinary candidate while a normal
         * top-K slot was still open. */
        val scanCapReached: Boolean
    )

    /**
     * Walk [candidates] best-first. All survivors, including +3 candidates,
     * count toward the normal [topK] while that list is filling. Once top-K
     * is full, ordinary candidates stop, but any later candidate marked by
     * [isMandatory] is still examined and appended if it survives. Thus +3
     * exceeds the count cap only when it would otherwise be missing.
     *
     * [survives] still owns cooldown, lore-overlap, and character-budget
     * filtering; mandatory means count-cap override, not filter bypass.
     */
    fun <T> select(
        candidates: List<T>,
        topK: Int,
        scanCap: Int = scanCap(topK),
        isMandatory: (T) -> Boolean = { false },
        survives: (T) -> Boolean
    ): Selection<T> {
        val normalLimit = topK.coerceAtLeast(0)
        val kept = ArrayList<T>(minOf(normalLimit, candidates.size))
        var examined = 0
        var capBlockedOrdinary = false
        for (candidate in candidates) {
            val mandatory = isMandatory(candidate)
            if (!mandatory && kept.size >= normalLimit) continue
            if (!mandatory && examined >= scanCap) {
                capBlockedOrdinary = true
                continue
            }

            examined++
            if (survives(candidate) && (mandatory || kept.size < normalLimit)) {
                kept.add(candidate)
            }
        }
        return Selection(kept, examined, capBlockedOrdinary)
    }
}