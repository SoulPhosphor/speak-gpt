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
 * The pure identity-disposition decision for one incoming memory during a MERGE
 * import. Kept out of [MemoryStore] so the whole rule is unit-tested on the JVM;
 * the store supplies the database facts and acts on the returned [Disposition].
 *
 * The merge import REPAIRS the cases it can resolve safely and defers only the
 * cases that genuinely need the user's intent:
 *
 *  - [Disposition.INSERT] — a canonical id novel to this store: write it as-is.
 *  - [Disposition.INSERT_REMAPPED] — the incoming record is a DIFFERENT logical
 *    memory than whatever currently holds (or once held) its id, or the id is
 *    non-canonical. Mint a fresh canonical id and import it as a separate
 *    memory; references are remapped where unambiguous (see [MemoryIdRemap]).
 *      · same id, different birth timestamp  → different memory        (case 1)
 *      · non-canonical / blank incoming id    → malformed identity      (case 2)
 *      · id matches a tombstone, different birth (or unknown birth) → the
 *        tombstoned id stays burned; import under a new id             (case 3)
 *  - [Disposition.PRESERVE_EXISTING] — the SAME logical record is already
 *    present and its substance is identical: keep the stored one, write nothing.
 *  - [Disposition.CONFLICT_VERSION] — the SAME logical memory (same id, same
 *    birth) but the imported substance DIFFERS from the live one. Two versions
 *    of one memory: the system must not pick for the user. Store structured
 *    conflict data for a future UI; write nothing now.                 (case 4)
 *  - [Disposition.CONFLICT_RESTORE] — the import contains the exact memory the
 *    user previously deleted (id matches a tombstone with the SAME birth). Only
 *    the user can say whether importing should resurrect it. Store structured
 *    conflict data for a future UI; write nothing now.                 (case 5)
 *
 * A whole-file backup RESTORE is a separate, identity-preserving file
 * replacement and never runs this path.
 */
object MemoryIdImport {

    /** The store-known state of an incoming id. [Live] also carries the live
     *  record's substance so a same-birth import can be told identical (skip)
     *  from a changed version (conflict). */
    sealed interface Existing {
        object None : Existing
        data class Live(val createdAt: String, val substance: Substance) : Existing
        data class Tombstoned(val createdAt: String?) : Existing
    }

    enum class Disposition {
        INSERT,
        INSERT_REMAPPED,
        PRESERVE_EXISTING,
        CONFLICT_VERSION,
        CONFLICT_RESTORE
    }

    /**
     * The user-meaningful state of a memory, used only to tell an identical
     * re-import (skip) from a genuine version change (conflict). Deliberately
     * EXCLUDES derived/bookkeeping fields (embedding text, timestamps, the
     * supersedes reference, legacy card hints): a difference there is not a
     * content change. Target/link sets are order-normalized so a reordered
     * export never looks like a change. Comparing broadly errs toward flagging a
     * conflict, which defers to the user and never silently drops an edit.
     */
    data class Substance(
        val content: String,
        val scope: String,
        val typeId: String?,
        val importance: Int,
        val tagsJson: String,
        val protectionJson: String?,
        val modeHintsJson: String,
        val status: String,
        val companionIds: List<String>,
        val entityRefs: List<String>,
        val worldIds: List<String>,
        val roleplayCharacterIds: List<String>,
        val campaignIds: List<String>,
        val projectIds: List<String>
    )

    /** Build the comparable [Substance] of a memory record. The JSON-valued
     *  fields are whitespace-normalized so a pretty-vs-compact reformat from an
     *  export round-trip never reads as a content change (both sides are
     *  transformed identically, so a genuine value change still differs). */
    fun substanceOf(m: MemoryRecord): Substance = Substance(
        content = m.content,
        scope = m.scope,
        typeId = m.typeId,
        importance = m.importance,
        tagsJson = normalizeJson(m.tagsJson),
        protectionJson = m.protectionJson?.let { normalizeJson(it) },
        modeHintsJson = normalizeJson(m.modeHintsJson),
        status = m.status,
        companionIds = m.companionIds.sorted(),
        entityRefs = m.entityRefs.sorted(),
        worldIds = m.worldIds.sorted(),
        roleplayCharacterIds = m.roleplayCharacterIds.sorted(),
        campaignIds = m.campaignIds.sorted(),
        projectIds = m.projectIds.sorted()
    )

    private fun normalizeJson(s: String): String = s.filterNot { it.isWhitespace() }

    /**
     * Decide what to do with an incoming record of [type] carrying [incomingId],
     * [incomingCreatedAt], and [incomingSubstance], given what the store already
     * knows ([existing]).
     */
    fun classify(
        incomingId: String?,
        incomingCreatedAt: String?,
        incomingSubstance: Substance,
        type: MemoryId.Type,
        existing: Existing
    ): Disposition {
        // Case 2: a non-canonical or blank id is not an identity we can keep.
        if (!MemoryId.isCanonical(incomingId, type)) return Disposition.INSERT_REMAPPED
        return when (existing) {
            is Existing.None -> Disposition.INSERT
            is Existing.Live ->
                when {
                    // Case 1: same id, different birth ⇒ a different memory.
                    !sameBirth(incomingCreatedAt, existing.createdAt) -> Disposition.INSERT_REMAPPED
                    // Same logical record, unchanged ⇒ nothing to do.
                    existing.substance == incomingSubstance -> Disposition.PRESERVE_EXISTING
                    // Case 4: same memory, two versions ⇒ the user must choose.
                    else -> Disposition.CONFLICT_VERSION
                }
            is Existing.Tombstoned ->
                // Case 5: proven the exact deleted memory ⇒ the user must choose
                // whether to resurrect. Case 3 (different or unknown birth) ⇒ a
                // different memory; the tombstoned id stays burned, import anew.
                if (existing.createdAt != null && sameBirth(incomingCreatedAt, existing.createdAt))
                    Disposition.CONFLICT_RESTORE
                else Disposition.INSERT_REMAPPED
        }
    }

    private fun sameBirth(a: String?, b: String?): Boolean = a != null && a == b
}
