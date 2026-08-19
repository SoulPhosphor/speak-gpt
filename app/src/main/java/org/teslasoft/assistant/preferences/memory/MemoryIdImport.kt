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
 * ## Why import PRESERVES a non-canonical id instead of rejecting it
 *
 * An import/restore is a PRESERVATION path, not a new-creation path. Canonical
 * format is required only when MINTING a new identity (that happens at the
 * generator, [MemoryId.generate]). An incoming record already carries an
 * identity: rejecting it because the id is not canonical would refuse to restore
 * a user's real, possibly legacy, memory. The export format carries no version
 * signal that could tell a genuine legacy id apart from garbage, so the safe
 * choice is to preserve any incoming id that names an identity at all, and treat
 * only a blank id — which names no identity — as invalid. Collision safety is
 * still enforced independently by the birth-timestamp check below.
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

        /** The incoming id names no identity (blank) — refuse it, visibly. */
        REJECT_INVALID
    }

    /**
     * Decide what to do with an incoming record carrying [incomingId] and
     * [incomingCreatedAt], given what the store already knows ([existing]). A
     * blank id is invalid; any non-blank id (canonical or grandfathered legacy)
     * is preserved, subject to the birth-timestamp collision check.
     */
    fun classify(
        incomingId: String?,
        incomingCreatedAt: String?,
        existing: Existing
    ): Disposition {
        if (incomingId.isNullOrBlank()) return Disposition.REJECT_INVALID
        return when (existing) {
            is Existing.None -> Disposition.INSERT
            is Existing.Live ->
                if (sameBirth(incomingCreatedAt, existing.createdAt)) Disposition.PRESERVE_EXISTING
                else Disposition.REJECT_COLLISION
            is Existing.Tombstoned ->
                // Unknown tombstone birth ⇒ cannot prove a collision; admit the
                // restore rather than block a legitimate one. A known, differing
                // birth ⇒ a different record reusing a freed id ⇒ refuse.
                if (existing.createdAt == null || sameBirth(incomingCreatedAt, existing.createdAt))
                    Disposition.INSERT
                else Disposition.REJECT_COLLISION
        }
    }

    private fun sameBirth(a: String?, b: String?): Boolean = a != null && a == b
}
