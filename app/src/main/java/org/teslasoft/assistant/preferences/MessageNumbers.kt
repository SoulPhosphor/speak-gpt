/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences

/**
 * Permanent message numbers (owner decision, Stage 6A question 10). Every
 * message slot in a conversation has a sequential number beside its UUID,
 * starting at 1. A message keeps its number when edited, and every
 * regenerated version of a reply shares its slot's number. Deleting a
 * message never renumbers others, and a number is never used again: the
 * highest number ever assigned is kept in the chat's settings
 * ([COUNTER_KEY]), so deleting the end of a conversation does not bring
 * its numbers back. Gaps are normal.
 *
 * UUIDs stay the authoritative identity. Numbers help people and the Memory
 * Scanner's AI read a conversation; they are not memory source references.
 */
object MessageNumbers {
    /** The message map key holding the number. */
    const val KEY = "messageNumber"

    /** The chat settings key holding the highest number ever assigned. */
    const val COUNTER_KEY = "message_number_counter"

    /** The message's permanent number, or null when it has none yet. */
    fun numberOf(message: Map<String, *>): Long? = when (val v = message[KEY]) {
        is Number -> v.toLong()
        is String -> v.toDoubleOrNull()?.toLong()
        else -> null
    }?.takeIf { it > 0 }

    /** True when any message still needs a number (or shares one). */
    fun needsNumbers(messages: List<Map<String, *>>): Boolean {
        val seen = HashSet<Long>()
        return messages.any { m -> numberOf(m)?.let { !seen.add(it) } ?: true }
    }

    /**
     * Gives every message without a number the next unused one, in the
     * chat's order, and returns the new highest number ever assigned.
     * [storedCounter] is that highest number as recorded before. Existing
     * messages pre-date the counter, so on first use they are numbered in
     * their current order. When two messages carry the same number (for
     * example after merging copies of a chat), the first keeps it and the
     * later one receives a new number; no message and no UUID changes.
     */
    fun ensure(messages: List<MutableMap<String, Any>>, storedCounter: Long): Long {
        var highest = storedCounter.coerceAtLeast(0)
        for (m in messages) numberOf(m)?.let { if (it > highest) highest = it }
        val seen = HashSet<Long>()
        for (m in messages) {
            val number = numberOf(m)
            if (number != null && seen.add(number)) continue
            highest += 1
            m[KEY] = highest
            seen.add(highest)
        }
        return highest
    }
}
