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

import android.content.Context
import org.teslasoft.assistant.preferences.memory.librarian.Librarian

/**
 * The one shared Type-management service (canonical recovery plan Phase 2, item
 * 1). Every route that lists, adds, renames, counts, or deletes a Memory Type
 * goes through here so the behavior is identical everywhere — starter Types and
 * user-created Types alike.
 *
 * The atomic storage operations live in [MemoryStore] (a rename edits only the
 * name by stable id; a delete reassigns affected memories to No Type and removes
 * the Type in one transaction, never deleting a memory). This service adds the
 * cross-cutting concern the store cannot own: after a rename or delete it kicks
 * the librarian's existing background missing-vector repair so the affected
 * memories — whose stale vectors the store already dropped (item 2) — are
 * re-embedded off the UI thread. No synchronous re-embedding runs here; no
 * provenance or source metadata is written.
 */
class MemoryTypeService private constructor(private val appContext: Context) {

    companion object {
        @Volatile private var instance: MemoryTypeService? = null

        fun getInstance(context: Context): MemoryTypeService =
            instance ?: synchronized(this) {
                instance ?: MemoryTypeService(context.applicationContext).also { instance = it }
            }
    }

    private val store: MemoryStore get() = MemoryStore.getInstance(appContext)

    /** Every user-owned Memory Type, starter Types first (seed order). */
    fun listTypes(): List<MemoryTypeRecord> = store.getMemoryTypes()

    /** Add a new Type with a fresh stable id; returns the created record. */
    fun addType(name: String): MemoryTypeRecord = store.addMemoryType(name)

    /** How many memories are currently assigned to a Type (every lifecycle
     *  state). Use this to disclose the count before a delete. */
    fun countMemories(typeId: String): Int = store.countMemoriesForType(typeId)

    /**
     * Rename a Type by its stable id. The id never changes, so no memory is
     * rewritten. Queues an embedding refresh for every affected memory and
     * returns their ids.
     */
    fun renameType(typeId: String, newName: String): List<String> {
        val affected = store.renameMemoryType(typeId, newName)
        requestRefresh(affected)
        return affected
    }

    /**
     * Delete a Type without deleting any memories: affected memories become No
     * Type atomically. Queues an embedding refresh for every affected memory and
     * returns their ids. A deleted starter Type is not re-seeded.
     */
    fun deleteType(typeId: String): List<String> {
        val affected = store.deleteMemoryType(typeId)
        requestRefresh(affected)
        return affected
    }

    /** Kick the librarian's background self-repair so the memories whose stale
     *  vectors were just dropped get re-embedded off the UI thread. Best-effort:
     *  when nothing was affected there is nothing to refresh. */
    private fun requestRefresh(affected: List<String>) {
        if (affected.isEmpty()) return
        try {
            Librarian.getInstance(appContext).requestMissingVectorRepair()
        } catch (_: Exception) {
            // The dropped vectors are also picked up by the next retrieval turn's
            // complete-set check, so a failed kick here only delays the refresh.
        }
    }
}
