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
 * The one shared companion-deletion service (canonical recovery plan Phase 2,
 * item 9). Every UI route that deletes a companion — the Companion detail
 * screen's Delete action and the persona-delete mirror in [MemoryCompanionSync]
 * — calls this service, so the count shown before deletion and the cascade that
 * runs are always the same. No activity duplicates the deletion SQL or the
 * cascade rules.
 *
 * The atomic cascade itself lives in [MemoryStore.deleteCompanion], which after
 * explicit confirmation permanently deletes every memory targeted SOLELY to this
 * companion across Pending / Active / Archived / Superseded, drops their
 * embeddings (FK cascade), removes companion-target join rows, discards
 * in-flight temporary analysis candidates aimed at the companion, tombstones the
 * records, and leaves General memories and memories shared with a surviving
 * companion untouched.
 */
class CompanionDeletionService private constructor(private val appContext: Context) {

    companion object {
        @Volatile private var instance: CompanionDeletionService? = null

        fun getInstance(context: Context): CompanionDeletionService =
            instance ?: synchronized(this) {
                instance ?: CompanionDeletionService(context.applicationContext).also { instance = it }
            }
    }

    private val store: MemoryStore get() = MemoryStore.getInstance(appContext)

    /**
     * How many memories this deletion will ACTUALLY delete permanently: the
     * memories this companion solely owns (a memory also targeted to another
     * companion survives with only this link removed). This is the number to
     * disclose in the destructive confirmation, and it matches exactly what
     * [deleteCompanion] then removes.
     */
    fun plannedMemoryDeletionCount(companionId: String): Int =
        store.companionSoleOwnedMemoryCount(companionId)

    /**
     * Run the full confirmed companion-deletion cascade atomically. Call only
     * after explicit user confirmation, off the main thread.
     */
    fun deleteCompanion(companionId: String) {
        store.deleteCompanion(companionId, deleteMemories = true)
    }
}
