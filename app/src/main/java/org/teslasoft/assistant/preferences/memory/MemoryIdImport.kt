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
 * The pure identity-disposition decision for an incoming memory during an
 * import or restore. Kept out of [MemoryStore] so the whole same-record vs.
 * identity-collision rule is unit-tested on the JVM; the store only supplies the
 * database facts (is the id live, is it tombstoned, and the birth timestamps)
 * and then acts on the returned [Disposition].
 *
 * ## The rule
 *
 * A memory's permanent id is a random UUID, so two *different* logical records
 * sharing one id never happens by chance — it means corrupted, hand-edited, or
 * reused data. The discriminator is the immutable birth timestamp `created_at`
 * (never derived from mutable content): the same logical record always carries
 * the same `created_at`, a different record almost never does. When the id
 * already exists but `created_at` disagrees, the incoming record is refused
 * rather than silently overwriting or merging an unrelated memory.
 *
 * A deleted id leaves a tombstone carrying the original `created_at`. That lets
 * a genuine RESTORE of the deleted memory (timestamps match) be admitted while a
 * DIFFERENT memory trying to reuse the freed id (timestamps differ) is refused —
 * ids are never reused across logical records.
 *
 * ## Import requires a canonical id — there is no non-canonical legacy format
 *
 * Import/restore is a preservation path, but "preserve any non-blank id" would
 * re-admit exactly the arbitrary-string leniency the id-hardening ruling removed
 * — only at the import layer. Investigation of the shipped export format settles
 * it: the associative `memory_id` has been `m-<uuid>` since the memory schema's
 * inception (the v1.11 seed template and every example use `m-<uuid>`; the
 * generator has only ever produced it; the codec's sole "legacy" handling is the
 * retired `kind`/`title` fields, never the id shape). A GENUINE legacy
 * associative identity is therefore already canonical, so requiring canonical
 * preserves every real identity while refusing arbitrary malformed input. A
 * non-canonical associative id in an import is not a legacy identity to keep — it
 * is malformed, and is refused ([Disposition.REJECT_INVALID]).
 *
 * (This governs the portable MERGE import only. A whole-file backup RESTORE is an
 * atomic file replacement that never runs this path, so a user's full-database
 * restore is unaffected regardless of id shape.)
 *
 * ## Ambiguous identity fails closed
 *
 * When an id's identity cannot be proven — an incoming record with no birth
 * timestamp against a live row, or a tombstone whose birth timestamp is unknown
 * (written before v30 recorded it) — the record is REFUSED, not admitted. The
 * ruling is to fail safely rather than risk a different logical memory silently
 * reclaiming an id.
 */
object MemoryIdImport {

    /** What the database already knows about an incoming id. */
    sealed interface Existing {
        /** No live row and no tombstone — the id is new to this store. */
        object None : Existing

        /** A live memory currently holds this id, born at [createdAt]. */
        data class Live(val createdAt: String) : Existing

        /**
         * The id was deleted and tombstoned. [createdAt] is the deleted memory's
         * birth timestamp, or null when it is unknown (a tombstone written before
         * birth timestamps were recorded).
         */
        data class Tombstoned(val createdAt: String?) : Existing
    }

    /** The action the store must take for one incoming record. */
    enum class Disposition {
        /** Write the record; its id is free (or a legitimate restore of it). */
        INSERT,

        /** The same logical record is already present; keep the stored one. */
        PRESERVE_EXISTING,

        /** A different logical record wears this id — refuse it, visibly. */
        REJECT_COLLISION,

        /** The incoming id is not a canonical id — refuse it, visibly. */
        REJECT_INVALID
    }

    /**
     * Decide what to do with an incoming record of [type] carrying [incomingId]
     * and [incomingCreatedAt], given what the store already knows ([existing]).
     * A non-canonical id is invalid; identity that cannot be proven fails closed.
     */
    fun classify(
        incomingId: String?,
        incomingCreatedAt: String?,
        type: MemoryId.Type,
        existing: Existing
    ): Disposition {
        if (!MemoryId.isCanonical(incomingId, type)) return Disposition.REJECT_INVALID
        return when (existing) {
            is Existing.None -> Disposition.INSERT
            is Existing.Live ->
                if (sameBirth(incomingCreatedAt, existing.createdAt)) Disposition.PRESERVE_EXISTING
                else Disposition.REJECT_COLLISION
            is Existing.Tombstoned ->
                // Fail closed on an unknown tombstone birth: the restore cannot be
                // proven to be the same record, so it is refused rather than risk
                // a different memory reclaiming the freed id. A matching known
                // birth is a genuine restore and is admitted.
                if (existing.createdAt != null && sameBirth(incomingCreatedAt, existing.createdAt))
                    Disposition.INSERT
                else Disposition.REJECT_COLLISION
        }
    }

    private fun sameBirth(a: String?, b: String?): Boolean = a != null && a == b
}
