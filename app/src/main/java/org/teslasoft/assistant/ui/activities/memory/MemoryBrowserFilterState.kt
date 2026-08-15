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

package org.teslasoft.assistant.ui.activities.memory

/**
 * Shared filter state for the memory browser (owner ruling, July 8 2026:
 * the chip-row filters were replaced by the Memory Filters slide-out panel).
 *
 * Held as a process-wide singleton so the panel activity and the browser see
 * the same values without an intent round-trip — the panel edits state in
 * place, then closing the panel returns to the browser which reloads on
 * resume and reflects the change. State is intentionally NOT persisted across
 * process death; the defaults are the fresh-launch view.
 *
 * Sort and Source are single-value (Source only has two real choices — the
 * owner's "if only two options, no multi" rule). Scope, Type, Status and
 * Tags are multi-select; an empty set means "no filter, match anything."
 * Status defaults to {"active"} so the browser opens showing only active
 * memories, and drafts are one selection away.
 */
object MemoryBrowserFilterState {

    /** Sort: single-value. "newest" | "oldest". */
    var sort: String = "newest"

    /** Source: single-value. "all" | "hand" | "learned". */
    var source: String = "all"

    /** Superseded memories: single-value. "hide" (default — active + archived,
     *  no old versions) | "include" (active + archived + superseded together) |
     *  "only" (just superseded). Applies to the Memories view only; the
     *  Pending view is drafts, which are never superseded. */
    var superseded: String = "hide"

    /** Scope: multi-select. Empty = match anything.
     *  Keys: global | real_life | companion | project | world | campaign | rp_character. */
    val scope: MutableSet<String> = mutableSetOf()

    /** Type: multi-select. Empty = match anything.
     *  Keys: fact | preference | event | status | instruction | lore. */
    val type: MutableSet<String> = mutableSetOf()

    /** Status: multi-select. Empty = match anything. Defaults to Active only
     *  (owner ruling: fresh view shows Active; Pending is a click away). */
    val status: MutableSet<String> = mutableSetOf("active")

    /** Tags: multi-select over the tags that exist in the current base set.
     *  Empty = match anything. Values are the tag texts (case-insensitive
     *  match on read). */
    val tags: MutableSet<String> = mutableSetOf()

    /** Memory Importance: multi-select over the signed scale. Empty = match
     *  anything. Values are the clamped importance numbers as text ("-2", "-1",
     *  "0", "1", "2", "3"); a memory matches when its stored importance,
     *  clamped to the scale, is in the set. Lets the user browse or audit by
     *  rating independently of whether ratings currently affect ranking. */
    val importance: MutableSet<String> = mutableSetOf()

    fun reset() {
        sort = "newest"
        source = "all"
        superseded = "hide"
        scope.clear()
        type.clear()
        status.clear(); status.add("active")
        tags.clear()
        importance.clear()
    }
}
