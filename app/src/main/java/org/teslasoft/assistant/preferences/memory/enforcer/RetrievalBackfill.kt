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
     * the worst case per turn is top-K + 64 ORDINARY candidates. Explicit +3
     * memories sit outside that ordinary work/count budget so they cannot
     * consume the slots they are supposed to be added on top of.
     *
     * This remains an internal work bound, not a relevance gate. Reaching it
     * is recorded in assembly notes so the extreme backfill case is visible.
     */
    const val SCAN_MARGIN = 64

    /** The documented ordinary-candidate scan cap for one turn's walk. */
    fun scanCap(topK: Int): Int = topK + SCAN_MARGIN

    data class Selection<T>(
        val kept: List<T>,
        /** Total candidates actually passed to [survives], mandatory included. */
        val examined: Int,
        /** True when the ordinary scan cap blocked one or more ordinary
         * candidates while ordinary slots were still open. */
        val scanCapReached: Boolean
    )

    /** Walk [candidates] best-first. Ordinary survivors fill [topK]; a
     * candidate marked by [isMandatory] is still examined and kept after the
     * normal count is full. Mandatory candidates do not consume [scanCap],
     * because +3 is explicitly additive to the normal memory-count budget.
     * [survives] still owns cooldown, lore-overlap, and character-budget
     * filtering, so mandatory means count-cap override, not relevance/filter
     * bypass. */
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
        var ordinaryExamined = 0
        var capBlockedOrdinary = false
        for (c in candidates) {
            val mandatory = isMandatory(c)
            if (!mandatory && kept.count { !isMandatory(it) } >= normalLimit) continue
            if (!mandatory && ordinaryExamined >= scanCap) {
                capBlockedOrdinary = true
                continue
            }
            examined++
            if (!mandatory) ordinaryExamined++
            if (survives(c)) {
                if (mandatory) {
                    kept.add(c)
                } else if (kept.count { !isMandatory(it) } < normalLimit) {
                    kept.add(c)
                }
            }
        }
        return Selection(kept, examined, capBlockedOrdinary)
    }
}