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

import java.util.UUID

/**
 * The single authority for minting and validating the permanent identity of a
 * memory record. Every path that CREATES an Associative/companion memory or a
 * Lorebook entry mints its id here; no creation path invents its own string.
 *
 * Two memory families, two canonical formats — both a random UUID v4 at the
 * core, so an id can never be derived from mutable content and two independently
 * created memories are astronomically unlikely to collide:
 *
 *  - [Type.ASSOCIATIVE] : `m-<uuid>`  (the existing intended associative format)
 *  - [Type.LOREBOOK]    : `<uuid>`    (the existing intended lorebook format)
 *
 * The canonical UUID text is exactly the lowercase 8-4-4-4-12 form that
 * [UUID.toString] emits. This object DEFINES that representation, so uppercase
 * or short-field variants are NOT canonical even though `UUID.fromString` would
 * parse them — see [isCanonical].
 *
 * ## Canonical creation vs. legacy preservation are deliberately separate
 *
 * The owner's id-hardening ruling requires that *new* identities always use the
 * canonical format, while *existing* identities are preserved unchanged even if
 * an older build minted them in a shape this validator would no longer produce.
 *
 * This object only owns the STRICT side — the format every NEW identity must
 * satisfy:
 *
 *  - [generate] / [isCanonical] / [requireCanonical]
 *
 * It deliberately provides NO "recognize any legacy string" helper. A permissive
 * check would let a malformed id arriving in a current-format import quietly
 * become grandfathered, which is exactly what the ruling forbids. Legacy ids are
 * preserved only where there is real EVIDENCE that they are pre-existing
 * identities, and that evidence lives at the call site, not in a format guess:
 *
 *  - MIGRATION preserves the ids already stored in the database verbatim (their
 *    presence in the DB is the evidence); it may scan and report non-canonical
 *    ones, but never rewrites them.
 *  - A genuinely OLDER backup/export format preserves the ids it carries because
 *    that format predates canonical ids (the format version is the evidence).
 *  - A CURRENT-format import/restore requires the canonical format
 *    ([requireCanonical]); a malformed id there fails rather than being admitted
 *    as legacy.
 *
 * ## Preserve vs. copy
 *
 * Identity-PRESERVING operations — export, import of the same record, backup,
 * restore, sync, and in-place migration — keep the record's existing id
 * unchanged. Intentionally DUPLICATING a memory as a new, independent memory is
 * a creation, not a preservation: it mints a fresh id via [generate] and must
 * never carry the source record's id.
 *
 * Pure Kotlin (no Android, no store) so it unit-tests on the JVM and can be
 * shared by the pure record factories. Transactional reservation and tombstone
 * checks against the live database live in [MemoryStore]; this object owns the
 * FORMAT contract only.
 */
object MemoryId {

    /** The two memory families that own an independent permanent id. */
    enum class Type(val prefix: String) {
        /** Associative / companion memories: `m-<uuid>`. */
        ASSOCIATIVE("m-"),

        /** Lorebook entries: a bare `<uuid>` with no type prefix. */
        LOREBOOK("")
    }

    /** A fresh canonical id for [type]. Never derived from any record content. */
    fun generate(type: Type): String = type.prefix + UUID.randomUUID().toString()

    /**
     * True only when [id] is EXACTLY the canonical format for [type]: the
     * required prefix followed by the canonical lowercase UUID text. This is the
     * gate a newly created identity must pass; it rejects legacy or malformed
     * shapes (including uppercase or short-field UUID text).
     */
    fun isCanonical(id: String?, type: Type): Boolean {
        if (id == null) return false
        if (!id.startsWith(type.prefix)) return false
        // For LOREBOOK the prefix is empty; guard so an `m-...` value is never
        // mistaken for a canonical bare-uuid lorebook id.
        if (type == Type.LOREBOOK && id.startsWith(Type.ASSOCIATIVE.prefix)) return false
        return isCanonicalUuid(id.substring(type.prefix.length))
    }

    /**
     * Returns [id] when it is canonical for [type], else throws. The hard stop a
     * new-creation (or current-format import) path uses so a malformed or
     * hand-invented id can never reach storage. [where] names the calling path
     * for a legible failure.
     */
    fun requireCanonical(id: String?, type: Type, where: String): String {
        if (!isCanonical(id, type)) {
            throw IllegalArgumentException(
                "Non-canonical ${type.name.lowercase()} memory id at $where: " +
                    formatForError(id)
            )
        }
        return id!!
    }

    /**
     * True when [s] is the canonical lowercase 8-4-4-4-12 UUID text. Compared
     * against the ORIGINAL string (not a lowercased copy), so uppercase or
     * short-field text that `UUID.fromString` would otherwise accept is rejected
     * — this object defines canonical as exactly what [UUID.toString] emits.
     */
    private fun isCanonicalUuid(s: String): Boolean {
        return try {
            UUID.fromString(s).toString() == s
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun formatForError(id: String?): String =
        when {
            id == null -> "<null>"
            id.isBlank() -> "<blank>"
            else -> "\"$id\""
        }
}
