/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.reasoning

/**
 * The fallback level to retry with when a provider rejects an explicit
 * reasoning effort (dynamic minimal/xhigh learning, owner ruling Aug 2026).
 *
 * Only the two extreme levels are ever offered without positive evidence and so
 * can be rejected: [ReasoningEffort.MINIMAL] and [ReasoningEffort.XHIGH]. The
 * substitution moves toward the safe universal middle, per the owner's rule:
 *
 * - a rejected **minimal** steps UP to the nearest supported level (low, then
 *   medium, then high);
 * - a rejected **extra high** steps DOWN to the nearest supported level (high,
 *   then medium, then low).
 *
 * When no explicit level remains in that direction the result is null, and the
 * caller falls back to [ReasoningEffort.AUTO] (send no explicit effort — the
 * provider/model default), which is always accepted.
 */
object ReasoningEffortSubstitution {

    /** Explicit levels from least to most reasoning. */
    private val ASCENDING = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH
    )

    /**
     * The nearest supported explicit level to retry with after [rejected] was
     * refused, or null when none remains in the safe direction (caller uses
     * [ReasoningEffort.AUTO]). [supported] is the model's currently offered
     * explicit levels (AUTO/OFF are ignored here).
     */
    fun substitute(rejected: ReasoningEffort, supported: Collection<ReasoningEffort>): ReasoningEffort? {
        val index = ASCENDING.indexOf(rejected)
        if (index < 0) return null
        val supportedSet = supported.toSet()
        // minimal (the low extreme) falls UP toward the middle; every other
        // rejected level — in practice only xhigh — falls DOWN.
        return if (rejected == ReasoningEffort.MINIMAL) {
            ASCENDING.drop(index + 1).firstOrNull { it in supportedSet }
        } else {
            ASCENDING.take(index).asReversed().firstOrNull { it in supportedSet }
        }
    }
}
