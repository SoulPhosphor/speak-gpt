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
 * The Phase 1 storage move from a fixed six-value `kind` enumeration to
 * user-owned Memory Types (canonical recovery plan §5, Phase 1 items 1–4).
 *
 * This object is the single source of truth for two things that MUST agree
 * everywhere they run:
 *
 *  1. the five starter Types the store seeds once (Fact, Preference, Event,
 *     Status, Instruction — Lore is deliberately NOT a Type, §5.1);
 *  2. how a legacy `kind` string maps onto a Type id during migration and
 *     backup import.
 *
 * The mapping is intentionally lossless-for-text and conservative:
 *  - a recognized starter kind maps to its matching seeded Type id;
 *  - legacy `lore` maps to **No Type** (null) — it is not an Associative
 *    Memory Type, and the memory's text/scope/targets/tags/lifecycle/
 *    timestamps are never touched by this mapping;
 *  - any other/absent kind also maps to No Type rather than discarding the
 *    memory (§5.2: an absent or invalid Type becomes No Type, never a
 *    dropped memory).
 *
 * It is pure Kotlin (no Android, no SQLCipher) so the owner's required
 * migration cases run as ordinary JVM unit tests — the store itself has no
 * JVM harness, so the decision logic is extracted here and both the
 * `MemoryStore.onUpgrade` migration and `MemorySeedCodec` import call it.
 */
object MemoryTypeMigration {

    /** A seeded starter Type: a stable internal id plus its initial name.
     *  The id never changes (rename edits only the name), so migration and
     *  every stored `memories.type_id` stay valid across a rename. */
    data class StarterType(val typeId: String, val name: String)

    /**
     * The five starter Types, in seed order (§5.1). Ids are fixed literals so
     * the legacy-kind mapping below is deterministic and testable; names are
     * the initial user-facing labels and may later be renamed by the user.
     */
    val STARTER_TYPES: List<StarterType> = listOf(
        StarterType("mtype-fact", "Fact"),
        StarterType("mtype-preference", "Preference"),
        StarterType("mtype-event", "Event"),
        StarterType("mtype-status", "Status"),
        StarterType("mtype-instruction", "Instruction")
    )

    /** Legacy `kind` value → starter Type id. Only the five recognized
     *  starter kinds appear here; `lore` and everything else fall through to
     *  No Type. Keyed by the lowercase legacy string the old six-value
     *  enumeration used. */
    private val LEGACY_KIND_TO_TYPE_ID: Map<String, String> = mapOf(
        "fact" to "mtype-fact",
        "preference" to "mtype-preference",
        "event" to "mtype-event",
        "status" to "mtype-status",
        "instruction" to "mtype-instruction"
    )

    /**
     * The Type id a memory should carry given its legacy [kind], or null for
     * **No Type**. `lore`, an unrecognized value, blank, or null all become
     * No Type — never a dropped memory. Matching is case-insensitive and
     * trims surrounding whitespace so an imported backup's stray casing does
     * not silently orphan a memory to No Type when it is really a Fact.
     */
    fun typeIdForLegacyKind(kind: String?): String? {
        val normalized = kind?.trim()?.lowercase() ?: return null
        return LEGACY_KIND_TO_TYPE_ID[normalized]
    }

    /** True when [kind] is the legacy `lore` value, which must become No Type
     *  (§5.1). Exposed for tests and for readable call sites. */
    fun isLegacyLore(kind: String?): Boolean =
        kind?.trim()?.equals("lore", ignoreCase = true) == true

    /** Type id → seeded starter id, reversed. */
    private val TYPE_ID_TO_LEGACY_KIND: Map<String, String> =
        LEGACY_KIND_TO_TYPE_ID.entries.associate { (kind, id) -> id to kind }

    /**
     * The legacy `kind` string a memory should carry so it never disagrees with
     * its [typeId] (Phase 1 Type transition, item 4). `type_id` is the source of
     * truth; `kind` is written only as a consistent, inert shadow so the
     * compatibility-period readers that still consult `kind` (dedup identity,
     * Instruction rendering, browser filters) stay correct until they are moved
     * onto `type_id` in a later phase.
     *
     *  - a seeded starter Type id → its legacy string (`fact`, `preference`, …);
     *  - No Type (null) → empty string;
     *  - any user-created custom Type → empty string (no legacy equivalent
     *    exists; the memory simply has no legacy kind).
     *
     * Because `kind` is always derived from `typeId` this way, a writer can
     * never produce the forbidden `kind = fact` with `typeId = null`, nor a
     * stale `typeId` paired with a freshly chosen `kind`.
     */
    fun legacyKindForTypeId(typeId: String?): String {
        if (typeId == null) return ""
        return TYPE_ID_TO_LEGACY_KIND[typeId] ?: ""
    }
}
