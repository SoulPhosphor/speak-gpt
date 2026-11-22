/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

/**
 * Memories the chat already used are clues (memory development plan M.2,
 * Stage 6B requirements). The scanner's existing-memory list stays Active
 * only (owner decision, 2026-11-22); recorded clues that are still Active
 * and eligible in the scene join the search results there. A clue already
 * found by the search keeps its place; the others take up to [MAX_CLUES]
 * places directly after the top search results, so the prompt's memory limit
 * cannot push them all out.
 */
object ScannerClues {

    const val MAX_CLUES = 3

    /**
     * @param searched the search results, best first.
     * @param clues eligible Active clue memories, in the order the section's
     *  replies recorded them.
     * @param promptSlots how many memories the request can show.
     */
    fun <T> forPrompt(searched: List<T>, clues: List<T>, promptSlots: Int, idOf: (T) -> String): List<T> {
        val searchedIds = searched.mapTo(HashSet(), idOf)
        val extra = clues.distinctBy(idOf).filter { idOf(it) !in searchedIds }.take(MAX_CLUES)
        if (extra.isEmpty()) return searched
        val at = (promptSlots - extra.size).coerceIn(0, searched.size)
        return searched.take(at) + extra + searched.drop(at)
    }
}
