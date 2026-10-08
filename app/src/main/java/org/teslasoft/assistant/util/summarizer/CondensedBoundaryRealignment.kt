/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

/**
 * The summary bookmark, the manual compaction marker, and both regeneration
 * locks each count the chat's oldest stored messages. Removing stored
 * messages from inside that counted prefix shifts every later message down,
 * so each boundary must shrink by the number of removed messages it covered;
 * otherwise the first message after it would be treated as already
 * condensed and never sent in full or summarized.
 */
object CondensedBoundaryRealignment {

    /** [boundary] after removing the contiguous stored range [start, end). */
    fun afterRangeRemoval(boundary: Int, start: Int, end: Int): Int {
        if (boundary <= 0 || end <= start) return boundary.coerceAtLeast(0)
        val covered = (minOf(end, boundary) - start).coerceAtLeast(0)
        return (boundary - covered).coerceAtLeast(0)
    }
}
