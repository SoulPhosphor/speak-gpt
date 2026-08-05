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
 * The single source of truth for how a memory's scope maps to a Memory Browser
 * DISPLAY GROUP (canonical recovery plan Phase 2, item 4).
 *
 * The browser organizes memories into two human-facing sections:
 *
 *  - Roleplay — the three fiction scopes: `world`, `campaign`, `rp_character`.
 *  - General  — every other scope, including `global`, `real_life`, `project`,
 *               and `companion`.
 *
 * This is ORGANIZATION ONLY. It must never be used to decide retrieval
 * eligibility, target boundaries, or the fiction wall. In particular:
 *
 *  - a Companion memory shown under the General group is still a Companion
 *    memory — restricted to its one companion, never a generic General memory;
 *  - the Roleplay group is a browser section, not a generic retrieval scope;
 *  - world / campaign / character / project / companion targets are untouched
 *    by grouping.
 *
 * Eligibility and scope isolation live in the store query
 * ([MemoryStore.activeMemoriesForScope]) and [RetrievalScope]; this helper is
 * deliberately pure and knows nothing about them, so it can never widen or
 * narrow what a memory can be retrieved for. Pure Kotlin, unit tested
 * (MemoryScopeGroupingTest).
 */
object MemoryScopeGrouping {

    /** The three fiction scopes that display under the Roleplay group. This is
     *  the ONE definition of that set — every browser/grouping call site reads
     *  it instead of re-listing the strings. */
    val ROLEPLAY_SCOPES: Set<String> = setOf("world", "campaign", "rp_character")

    /** A Memory Browser display group. Not a scope, not a retrieval category —
     *  purely which section a memory row is listed under. */
    enum class BrowserGroup { ROLEPLAY, GENERAL }

    /**
     * The display group for a memory scope. A roleplay scope groups under
     * [BrowserGroup.ROLEPLAY]; everything else — including `companion`, which
     * stays a restricted Companion memory — groups under [BrowserGroup.GENERAL].
     * An unknown/blank scope groups under General (the browser still lists it;
     * grouping never hides a memory).
     */
    fun groupFor(scope: String?): BrowserGroup =
        if (scope != null && scope in ROLEPLAY_SCOPES) BrowserGroup.ROLEPLAY
        else BrowserGroup.GENERAL

    /** True when a scope displays under the Roleplay group. Convenience for the
     *  many call sites that only need the roleplay/not-roleplay split (pending
     *  Add-to-Card affordance, browser section headers). */
    fun isRoleplayGroup(scope: String?): Boolean = groupFor(scope) == BrowserGroup.ROLEPLAY
}
