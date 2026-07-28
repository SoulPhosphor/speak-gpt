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

import org.teslasoft.assistant.preferences.memory.enforcer.PromptAssembler

/**
 * Bounded, defaulted retrieval-policy values (counterplan §5.5): the stored
 * or imported retrieval_policy row is data, not code — a negative top_k, an
 * extreme budget, or a non-finite weight must degrade to safe defaults
 * instead of throwing the whole turn back to lore-only assembly. Every
 * substitution is reported back to the caller so it reaches the debug log.
 * Pure Kotlin, unit tested (RetrievalPolicyTest).
 */
object RetrievalPolicy {

    const val DEFAULT_TOP_K = 8
    const val MIN_TOP_K = 1
    const val MAX_TOP_K = 64

    // A budget below this cannot fit one useful memory; above the cap it
    // would dwarf any realistic context window.
    const val MIN_CHAR_BUDGET = 500
    const val MAX_CHAR_BUDGET = 60_000

    // Canonical scoring defaults (the librarian reads these).
    const val DEFAULT_W_SIM = 0.6
    const val DEFAULT_W_IMP = 0.3
    const val DEFAULT_W_REC = 0.1
    private const val MAX_WEIGHT = 100.0

    /** A bounded value plus the reason a default/cap was substituted, or null
     *  when the stored value (or a normal absence) was used as-is. */
    data class Bounded<T>(val value: T, val substitutionNote: String?)

    /** Bound a raw JSON `top_k` value (null = absent). */
    fun boundTopK(raw: Any?): Bounded<Int> =
        boundInt(raw, "top_k", DEFAULT_TOP_K, MIN_TOP_K, MAX_TOP_K)

    /** Bound a raw JSON `memory_char_budget` value (null = absent). */
    fun boundCharBudget(raw: Any?): Bounded<Int> =
        boundInt(raw, "memory_char_budget", PromptAssembler.DEFAULT_CHAR_BUDGET, MIN_CHAR_BUDGET, MAX_CHAR_BUDGET)

    private fun boundInt(raw: Any?, name: String, default: Int, min: Int, max: Int): Bounded<Int> {
        if (raw == null) return Bounded(default, null)
        val n = (raw as? Number)?.toDouble()
        if (n == null || !n.isFinite()) {
            return Bounded(default, "retrieval policy $name is malformed — using default $default")
        }
        val v = n.toInt()
        return when {
            v < min -> Bounded(default, "retrieval policy $name ($v) is out of range — using default $default")
            v > max -> Bounded(max, "retrieval policy $name ($v) is out of range — capped at $max")
            else -> Bounded(v, null)
        }
    }

    /**
     * Bound stored [similarity, importance, recency] weights. Null (no policy
     * row / no weights object) silently uses the defaults; a wrong-sized,
     * non-finite, negative, oversized, or all-zero set is replaced by the
     * defaults with a note — an all-zero set would rank purely by context
     * boost, letting an irrelevant memory into the prompt.
     */
    fun boundWeights(raw: DoubleArray?): Bounded<DoubleArray> {
        val defaults = doubleArrayOf(DEFAULT_W_SIM, DEFAULT_W_IMP, DEFAULT_W_REC)
        if (raw == null) return Bounded(defaults, null)
        val malformed = raw.size != 3 ||
            raw.any { !it.isFinite() || it < 0.0 || it > MAX_WEIGHT } ||
            raw.all { it == 0.0 }
        return if (malformed) {
            Bounded(defaults, "retrieval policy weights are malformed — using defaults")
        } else {
            Bounded(raw, null)
        }
    }
}
