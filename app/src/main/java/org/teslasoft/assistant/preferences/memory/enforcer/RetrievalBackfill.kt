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
     * room to backfill). The margin bounds the expensive per-candidate work —
     * the lore near-duplicate check may embed each examined candidate — so
     * the worst case per turn is top-K + 64 embeds, which is already
     * generous for an on-device model. This is an internal work bound, not a
     * retrieval limit: it changes what is retrieved only in the extreme case
     * where more than this many ranked-relevant candidates in a row are all
     * removed by cooldown or lore overlap, and reaching it is recorded in
     * the assembly notes so that case is never silent.
     */
    const val SCAN_MARGIN = 64

    /** The documented scan cap for one turn's candidate walk. */
    fun scanCap(topK: Int): Int = topK + SCAN_MARGIN

    data class Selection<T>(
        val kept: List<T>,
        val examined: Int,
        /** True when the cap stopped the walk while slots were still open and
         *  unexamined candidates remained. */
        val scanCapReached: Boolean
    )

    /** Walk [candidates] best-first, keeping survivors of [survives], until
     *  [topK] are kept, the list is exhausted, or [scanCap] candidates were
     *  examined. [survives] records its own removal reason at the call site. */
    fun <T> select(
        candidates: List<T>,
        topK: Int,
        scanCap: Int = scanCap(topK),
        survives: (T) -> Boolean
    ): Selection<T> {
        if (topK <= 0) return Selection(emptyList(), 0, false)
        val kept = ArrayList<T>(minOf(topK, candidates.size))
        var examined = 0
        for (c in candidates) {
            if (kept.size >= topK) break
            if (examined >= scanCap) return Selection(kept, examined, true)
            examined++
            if (survives(c)) kept.add(c)
        }
        return Selection(kept, examined, false)
    }
}
