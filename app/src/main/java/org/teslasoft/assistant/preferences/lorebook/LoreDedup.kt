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

package org.teslasoft.assistant.preferences.lorebook

/**
 * Cross-book duplicate handling (counterplan Step 1.6): the same lore content
 * can live in more than one active book (e.g. copied into a persona's core
 * book and also into a shared book). Exact-normalized text match ONLY — never
 * semantic/fuzzy — so two entries the user actually wrote differently are
 * never treated as duplicates. First occurrence in the caller's book-priority
 * order wins, so a duplicate in a lower-priority book never displaces the
 * core book's copy. Pure Kotlin, unit-tested.
 */
object LoreDedup {

    /** Whitespace-collapsed, trimmed, case-folded content. Exact equality
     *  only — lore content is short and user-authored, so no fuzzy threshold. */
    fun normalize(content: String): String =
        content.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * [matches] with same-normalized-content duplicates removed. The incoming
     * order IS the book-priority order (core book first), so the first
     * occurrence of each normalized content survives.
     */
    fun dedup(matches: List<LoreBookMatch>): List<LoreBookMatch> {
        val seen = HashSet<String>()
        val out = ArrayList<LoreBookMatch>(matches.size)
        for (m in matches) {
            if (seen.add(normalize(m.entry.content))) out.add(m)
        }
        return out
    }

    /** The matches [dedup] would drop, each paired with the earlier match
     *  whose content it duplicates — for "why was this cut" diagnostics. */
    fun droppedDuplicates(matches: List<LoreBookMatch>): List<Pair<LoreBookMatch, LoreBookMatch>> {
        val firstByKey = HashMap<String, LoreBookMatch>()
        val out = ArrayList<Pair<LoreBookMatch, LoreBookMatch>>()
        for (m in matches) {
            val key = normalize(m.entry.content)
            val first = firstByKey[key]
            if (first == null) firstByKey[key] = m else out.add(m to first)
        }
        return out
    }
}
