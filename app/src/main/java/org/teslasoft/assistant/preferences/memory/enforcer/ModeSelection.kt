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
 * The pure half of mode detection (enforcer spec §Mode detection): given
 * per-mode scores computed by the Android side, pick the ≤2 active modes with
 * the protective tie-break, honor overrides, apply suggested_mode activation,
 * and run the stickiness state machine. No Android, no JSON — unit-tested.
 *
 * The protective gradient is matched by normalized mode NAME (the operating
 * defaults ship those names; a user renaming their modes opts out of the
 * gradient, which is their call): steady presence > emotional support >
 * companion presence.
 */
object ModeSelection {

    /** Default score threshold; retrieval_policy may override ("mode_threshold"). */
    const val DEFAULT_THRESHOLD = 0.45

    /** Two gradient modes within this margin resolve toward the protective one. */
    const val ADJACENT_MARGIN = 0.08

    /** Sticky modes exit after this many consecutive no-signal turns WITH a
     *  task mode active — never because one message failed to re-trigger. */
    const val STICKY_MAX_MISSES = 2

    // Ordered least → most protective; index is the rank.
    private val PROTECTIVE_GRADIENT = listOf("companion presence", "emotional support", "steady presence")

    // Clear exit signals: the user says they're okay / moves on with energy.
    // Deliberately conservative — a missed exit costs a turn of extra
    // gentleness, a wrong exit drops someone mid-hard-moment.
    private val EXIT_PHRASES = listOf(
        "i'm okay", "im okay", "i am okay", "i'm ok", "im ok", "i am ok",
        "i'm fine", "im fine", "i am fine", "i'm good now", "feeling better",
        "let's move on", "lets move on", "back to work", "anyway, "
    )

    data class Candidate(
        val modeId: String,
        val name: String,
        /** Embedding similarity vs the mode's joined signals, plus keyword bonus. */
        val score: Double,
        /** Mode ids this mode's overrides list names. */
        val overrides: List<String> = emptyList()
    )

    data class StickyState(
        val modeIds: Set<String> = emptySet(),
        val misses: Int = 0
    )

    private fun protectiveRank(name: String): Int =
        PROTECTIVE_GRADIENT.indexOf(name.trim().lowercase().replace('-', ' '))

    fun isProtective(name: String): Boolean = protectiveRank(name) >= 0

    fun isExitSignal(userMessage: String): Boolean {
        val msg = userMessage.lowercase()
        return EXIT_PHRASES.any { msg.contains(it) }
    }

    /**
     * Pick the active modes for this turn, most important first, at most two.
     * [suggestedIds] come from retrieved protected memories' suggested_mode —
     * they activate directly, independent of scoring. [stickyIds] are modes
     * held over from previous turns.
     */
    fun select(
        candidates: List<Candidate>,
        threshold: Double,
        suggestedIds: Set<String> = emptySet(),
        stickyIds: Set<String> = emptySet()
    ): List<Candidate> {
        val byId = candidates.associateBy { it.modeId }

        // 1. Signal-cleared modes, best first.
        val cleared = candidates.filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .toMutableList()

        // 2. Adjacent-mode tie-break: when gradient modes score close, the
        // more protective one wins the slot (entering protection unnecessarily
        // costs almost nothing; missing it costs a lot).
        if (cleared.size >= 2) {
            val top = cleared[0]
            val topRank = protectiveRank(top.name)
            if (topRank >= 0) {
                val rival = cleared.drop(1).filter {
                    protectiveRank(it.name) > topRank && top.score - it.score <= ADJACENT_MARGIN
                }.maxByOrNull { protectiveRank(it.name) }
                if (rival != null) {
                    cleared.remove(rival)
                    cleared.add(0, rival)
                }
            }
        }

        // 3. Suggested and sticky modes join regardless of score, in a stable
        // order: suggested first (a protected memory asked for them), then the
        // signal-cleared modes (post-tie-break), then held-over sticky modes.
        val active = LinkedHashSet<Candidate>()
        suggestedIds.mapNotNull { byId[it] }.forEach { active.add(it) }
        cleared.forEach { active.add(it) }
        stickyIds.mapNotNull { byId[it] }.forEach { active.add(it) }

        // 4. Overrides: a selected mode drops the modes it overrides.
        val overridden = active.flatMap { it.overrides }.toSet()
        val resolved = active.filter { it.modeId !in overridden }

        // 5. Two slots. Scores decide — EXCEPT that when protective modes
        // would be squeezed out entirely by task modes, the best protective
        // one claims the second slot (missing protection costs more than a
        // redundant mode block). Protective modes render first.
        var picked = resolved.take(2)
        if (picked.none { isProtective(it.name) }) {
            val bestProtective = resolved.drop(2).firstOrNull { isProtective(it.name) }
            if (bestProtective != null) picked = listOf(picked.first(), bestProtective)
        }
        return picked.sortedByDescending { if (isProtective(it.name)) 1 else 0 }
    }

    /**
     * Advance the stickiness state after a turn. [activeProtectiveIds] are the
     * protective modes active THIS turn (they become/stay sticky);
     * [retriggered] is true when any sticky mode cleared threshold on its own;
     * [taskModeCleared] is true when a non-protective mode cleared threshold.
     */
    fun updateSticky(
        prev: StickyState,
        activeProtectiveIds: Set<String>,
        retriggered: Boolean,
        taskModeCleared: Boolean,
        exitSignal: Boolean
    ): StickyState {
        if (exitSignal) return StickyState()
        if (activeProtectiveIds.isNotEmpty() && retriggered) {
            return StickyState(prev.modeIds + activeProtectiveIds, 0)
        }
        if (prev.modeIds.isEmpty()) {
            return if (activeProtectiveIds.isEmpty()) StickyState()
            else StickyState(activeProtectiveIds, 0)
        }
        // Sticky modes present but not re-triggered this turn.
        val misses = prev.misses + 1
        return if (taskModeCleared && misses > STICKY_MAX_MISSES) StickyState()
        else StickyState(prev.modeIds + activeProtectiveIds, misses)
    }
}
