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
 * The pure reference-remapping decision for a merge import that minted new ids
 * for some incoming memories ([MemoryIdImport.Disposition.INSERT_REMAPPED]).
 * When a memory's id changes, every reference to its OLD id elsewhere in the
 * import payload (a `supersedes` pointer, a proposal target, a roleplay-tag
 * target) must follow — but only where the old id unambiguously names one
 * memory. Kept pure so the whole ambiguity rule is unit-tested on the JVM.
 *
 * ## What is ambiguous
 *
 * An old id is ambiguous when it could mean two different memories at once:
 *  - it is still held by a LIVE existing store memory (the remap happened
 *    BECAUSE the incoming memory was a different logical record sharing that
 *    live id — a reference to the id could mean either one), or
 *  - more than one incoming memory carried that same old id.
 *
 * A tombstoned collision is NOT ambiguous: the old memory is deleted, so a
 * reference to its id can only mean the incoming memory, and it is remapped.
 */
object MemoryIdRemap {

    /** One incoming memory's remap outcome, fed in to build the resolution. */
    data class Entry(
        /** The id the memory arrived with. */
        val originalId: String,
        /** The id it will actually be written under (== originalId when kept). */
        val finalId: String,
        /** True when [finalId] was freshly minted (originalId is being retired). */
        val remapped: Boolean,
        /** True when originalId is still held by a LIVE existing store memory. */
        val collidesWithLive: Boolean
    )

    /** How a reference to a given old id must be resolved. */
    sealed interface Ref {
        /** Leave the id unchanged (it names an existing/kept memory). */
        data class Keep(val id: String) : Ref
        /** Repoint the reference to [newId]. */
        data class Remap(val newId: String) : Ref
        /** The old id names more than one memory — do not guess; skip the ref. */
        object Ambiguous : Ref
    }

    /**
     * Build the resolution map for every id that appears as an incoming memory.
     * Ids not present here are not remapped (they name existing store memories or
     * are dangling) and keep their value.
     */
    fun buildResolution(entries: List<Entry>): Map<String, Ref> {
        val byOriginal: Map<String, List<Entry>> = entries.groupBy { it.originalId }
        val out = HashMap<String, Ref>(byOriginal.size)
        for ((originalId, group) in byOriginal) {
            out[originalId] = when {
                // The same old id carried by two incoming memories: unresolvable.
                group.size > 1 -> Ref.Ambiguous
                else -> {
                    val e = group.single()
                    when {
                        !e.remapped -> Ref.Keep(e.finalId)
                        e.collidesWithLive -> Ref.Ambiguous
                        else -> Ref.Remap(e.finalId)
                    }
                }
            }
        }
        return out
    }

    /**
     * Resolve a single reference to [oldId] against a built [resolution].
     * Returns the id to write, or null when the reference must be dropped
     * (ambiguous). A blank/absent reference resolves to null (nothing to write).
     * An id not in the resolution is kept unchanged.
     */
    fun resolve(resolution: Map<String, Ref>, oldId: String?): Resolved {
        if (oldId.isNullOrBlank()) return Resolved(null, dropped = false)
        return when (val r = resolution[oldId]) {
            null -> Resolved(oldId, dropped = false)
            is Ref.Keep -> Resolved(r.id, dropped = false)
            is Ref.Remap -> Resolved(r.newId, dropped = false)
            Ref.Ambiguous -> Resolved(null, dropped = true)
        }
    }

    /** [value] is the id to write (or null); [dropped] is true only when the
     *  reference was dropped BECAUSE it was ambiguous (a diagnostic-worthy skip),
     *  as opposed to simply being absent. */
    data class Resolved(val value: String?, val dropped: Boolean)
}
