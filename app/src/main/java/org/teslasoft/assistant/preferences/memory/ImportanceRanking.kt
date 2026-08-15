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
 * Canonical importance behavior shared by every retrieval layer.
 *
 * Stored ratings use the signed scale -2, -1, 0, +1, +2, +3. Missing values
 * are supplied by callers as 0. The master toggle controls whether ratings
 * have any runtime effect, but never mutates the stored value.
 *
 * -2..+2 form the ordinary symmetric ranking scale around neutral 0.
 * +3 has the same ranking contribution as +2 and additionally means
 * "mandatory when otherwise eligible": it can exceed the normal memory-count
 * limit, but it does not bypass scope/relevance eligibility or later prompt
 * assembly filters such as character budget, lore overlap, and cooldown.
 */
object ImportanceRanking {

    /** Clamp persisted/imported values to the supported signed scale. */
    fun normalizeStoredImportance(rawValue: Double): Double =
        rawValue.coerceIn(-2.0, 3.0)

    /**
     * Convert the stored rating to a symmetric -1..+1 ranking contribution.
     * +3 deliberately saturates at the +2 contribution because its extra
     * meaning is mandatory inclusion, not a larger ranking boost.
     */
    fun normalizedRankingImportance(storedValue: Double): Double =
        normalizeStoredImportance(storedValue).coerceAtMost(2.0) / 2.0

    /**
     * The configured importance blend coefficient is active only when the
     * master toggle is on. Turning the setting off contributes exactly zero
     * without rewriting any memory.
     */
    fun effectiveImportanceWeight(storedWeight: Double, useImportanceRatings: Boolean): Double =
        if (useImportanceRatings) storedWeight else 0.0

    /** True only for the +3 rating while the master feature is enabled. */
    fun isMandatory(storedValue: Double, useImportanceRatings: Boolean): Boolean =
        useImportanceRatings && normalizeStoredImportance(storedValue) >= 3.0

    /**
     * Return the normal top-K head plus every later mandatory candidate,
     * preserving ranking order and never duplicating a mandatory item already
     * present in the head. Eligibility must be applied before this helper.
     */
    fun <T> includeMandatory(
        rankedEligible: List<T>,
        topK: Int,
        isMandatory: (T) -> Boolean
    ): List<T> {
        val normalCount = topK.coerceAtLeast(0)
        if (rankedEligible.isEmpty()) return emptyList()
        val head = rankedEligible.take(normalCount)
        if (rankedEligible.size <= normalCount) return head
        val out = ArrayList<T>(head.size)
        out.addAll(head)
        for (candidate in rankedEligible.drop(normalCount)) {
            if (isMandatory(candidate)) out.add(candidate)
        }
        return out
    }
}
