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
 * ## Canonical creation vs. legacy recognition are deliberately separate
 *
 * The owner's id-hardening ruling requires that *new* identities always use the
 * canonical format, while *existing* identities are preserved unchanged even if
 * an older build minted them in a shape this validator would no longer produce.
 * Those two jobs must never be conflated into one permissive check, or "new
 * creation" would silently start accepting arbitrary strings.
 *
 *  - [generate] / [isCanonical] / [requireCanonical] — the STRICT gate. Used on
 *    every new-memory creation path. Only the exact canonical format passes.
 *
 *  - [isRecognized] — the LENIENT gate, for preservation paths only (migration,
 *    import, restore, copy). It accepts a canonical id AND a non-blank legacy id
 *    that a record already carries, so a grandfathered id rides through
 *    untouched. It must never be used to admit a *new* identity.
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
     * required prefix followed by a syntactically valid UUID. This is the gate a
     * newly created identity must pass; it deliberately rejects legacy shapes.
     */
    fun isCanonical(id: String?, type: Type): Boolean {
        if (id == null) return false
        if (!id.startsWith(type.prefix)) return false
        val uuidPart = id.substring(type.prefix.length)
        // For LOREBOOK the prefix is empty, so this is the whole string; a bare
        // UUID must NOT carry the associative prefix, which startsWith already
        // guarantees only for ASSOCIATIVE. Guard the empty-prefix case so an
        // `m-...` value is never mistaken for a canonical lorebook id.
        if (type == Type.LOREBOOK && id.startsWith(Type.ASSOCIATIVE.prefix)) return false
        return isUuid(uuidPart)
    }

    /**
     * Returns [id] when it is canonical for [type], else throws. The hard stop a
     * new-creation path uses so a malformed or hand-invented id can never reach
     * storage. [where] names the calling path for a legible failure.
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
     * Lenient recognition for PRESERVATION paths (migration / import / restore /
     * copy) only. True when [id] is either canonical for [type] or a non-blank
     * legacy id an existing record already carries. Never admit a *new* identity
     * with this — new creation uses [requireCanonical].
     */
    fun isRecognized(id: String?, type: Type): Boolean {
        if (id.isNullOrBlank()) return false
        if (isCanonical(id, type)) return true
        // A grandfathered legacy id: any non-blank, non-whitespace value the
        // record was already stored under. Preserved verbatim, never rewritten.
        return id.isNotBlank()
    }

    /** True when [s] is a syntactically valid UUID (canonical 8-4-4-4-12 form). */
    private fun isUuid(s: String): Boolean {
        // UUID.fromString is lenient about field widths, so validate the shape
        // ourselves: it must round-trip to the same canonical string.
        return try {
            UUID.fromString(s).toString() == s.lowercase()
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
