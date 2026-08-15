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

package org.teslasoft.assistant.preferences.memory

/**
 * One shared contract for user-owned memory importance.
 *
 * The ordinary ranking scale is signed around neutral:
 * - -2 and -1 demote an otherwise eligible memory.
 * - 0 is neutral and is also the fallback for an absent/invalid rating.
 * - +1 and +2 promote an otherwise eligible memory.
 * - +3 is a mandatory-inclusion marker. It gets the same ranking contribution
 *   as +2, then independently bypasses the normal result-count cutoff after
 *   scope and relevance have already made the memory eligible.
 *
 * The "Use Importance Ratings" master toggle gates BOTH ranking influence and
 * +3 mandatory inclusion. Turning it off never rewrites stored ratings, so
 * turning it back on restores the user's values immediately.
 */
object ImportanceRanking {
    const val MIN_IMPORTANCE = -2
    const val NEUTRAL_IMPORTANCE = 0
    const val MAX_RANKING_IMPORTANCE = 2
    const val ALWAYS_INCLUDE_IMPORTANCE = 3
    const val MAX_IMPORTANCE = ALWAYS_INCLUDE_IMPORTANCE

    /** Clamp imported/stored values to the supported range; null means neutral. */
    fun sanitizeImportance(value: Int?): Int =
        value?.coerceIn(MIN_IMPORTANCE, MAX_IMPORTANCE) ?: NEUTRAL_IMPORTANCE

    /**
     * Signed ranking contribution in -1.0..1.0.
     *
     * +3 intentionally saturates at the +2 ranking value. Its extra meaning is
     * handled by [isAlwaysIncluded] and [selectWithMandatory], not by giving it
     * a magic oversized score.
     */
    fun normalizedRankingValue(value: Int?): Double {
        val ranked = sanitizeImportance(value).coerceAtMost(MAX_RANKING_IMPORTANCE)
        return ranked / MAX_RANKING_IMPORTANCE.toDouble()
    }

    /** True only when the feature is enabled and this memory carries +3. */
    fun isAlwaysIncluded(value: Int?, useImportanceRatings: Boolean): Boolean =
        useImportanceRatings && sanitizeImportance(value) == ALWAYS_INCLUDE_IMPORTANCE

    /**
     * Apply the normal result count while retaining every already-eligible +3
     * memory. This is deliberately called after relevance/scope eligibility.
     */
    fun selectWithMandatory(
        scored: List<ScoredMemory>,
        topK: Int,
        useImportanceRatings: Boolean
    ): List<ScoredMemory> {
        val sorted = scored.sortedByDescending { it.score }
        val normalLimit = topK.coerceAtLeast(0)
        if (!useImportanceRatings) return sorted.take(normalLimit)
        return sorted.filterIndexed { index, hit ->
            index < normalLimit || isAlwaysIncluded(hit.memory.importance, true)
        }
    }

    /**
     * The importance blend coefficient the ranker should actually use.
     * Stored ratings are never modified by this gate.
     */
    fun effectiveImportanceWeight(storedWeight: Double, useImportanceRatings: Boolean): Double =
        if (useImportanceRatings) storedWeight else 0.0
}
