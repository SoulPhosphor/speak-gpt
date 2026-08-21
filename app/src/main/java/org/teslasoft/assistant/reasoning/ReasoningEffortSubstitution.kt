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

    /** The universal middle levels a substitution may land on, low → high. Both
     *  extremes fall back only into this middle, never to the OTHER extreme, so
     *  a rejected minimal can never jump to xhigh (or vice versa). */
    private val SAFE_MIDDLE = listOf(
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH
    )

    /**
     * The nearest sensible supported level to retry with after [rejected] was
     * refused, or null when no safe middle level is supported (caller uses
     * [ReasoningEffort.AUTO], the unambiguous safe default). [supported] is the
     * model's currently offered explicit levels (AUTO/OFF are ignored here).
     *
     * Only the two optimistic extremes are ever rejected. A rejected **minimal**
     * steps UP to the nearest supported middle level (low → medium → high); a
     * rejected **extra high** steps DOWN (high → medium → low). The result is
     * never the opposite extreme, so the fallback can't make an absurd jump.
     */
    fun substitute(rejected: ReasoningEffort, supported: Collection<ReasoningEffort>): ReasoningEffort? {
        if (rejected != ReasoningEffort.MINIMAL && rejected != ReasoningEffort.XHIGH) return null
        val supportedSet = supported.toSet()
        val order = if (rejected == ReasoningEffort.MINIMAL) SAFE_MIDDLE else SAFE_MIDDLE.asReversed()
        return order.firstOrNull { it in supportedSet }
    }
}
