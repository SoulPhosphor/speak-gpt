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

/**
 * The canonical Pending filing service (canonical recovery plan Phase 2, item
 * 8): converts a VALIDATED [MemoryCandidate] into the one canonical Pending
 * Associative Memory representation and stores it.
 *
 * Every filing origin uses this service so none maintains separate filing
 * behavior:
 *  - the API Memory Assistant (the Archivist),
 *  - a validated computer-file import,
 *  - manual pending creation where applicable.
 *
 * Origin-specific transport wrappers (raw API suggestions, raw import payloads)
 * may exist BEFORE validation, but they converge on [MemoryCandidate] and then
 * on this service before any row is written. The API/computer transport is not
 * part of the candidate, so it can never be stored on the memory or shown on the
 * Pending card. The record shape is built by [PendingMemoryRecordFactory]; this
 * service only assigns a fresh id and timestamp and performs the store write.
 */
class PendingMemoryFiler private constructor(private val appContext: Context) {

    companion object {
        @Volatile private var instance: PendingMemoryFiler? = null

        fun getInstance(context: Context): PendingMemoryFiler =
            instance ?: synchronized(this) {
                instance ?: PendingMemoryFiler(context.applicationContext).also { instance = it }
            }
    }

    /**
     * File [candidate] as a canonical Pending draft. Returns the new memory id.
     * Runs the store write on the caller's thread — call it off the main thread.
     *
     * [generated] is a route hint, not memory data: true for a Memory Assistant /
     * computer-analysis proposal, false for manual creation. It records separate,
     * id-keyed bookkeeping so a generated draft's deletion counts as a content
     * rejection (no rerun refile) while a manual draft's does not. The canonical
     * memory object itself never learns which route filed it.
     */
    fun file(candidate: MemoryCandidate, generated: Boolean = false): String {
        val store = MemoryStore.getInstance(appContext)
        val record = PendingMemoryRecordFactory.build(
            candidate, MemoryStore.newId("m-"), MemoryStore.nowIso()
        )
        store.insertPendingMemory(record, generated = generated)
        return record.memoryId
    }
}
