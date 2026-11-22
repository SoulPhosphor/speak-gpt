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
 * Reads a Pending suggestion's current Possible Matches through
 * [PossibleMatchFinder] and arranges them with [PendingMatchPresentation]
 * (Stage 6C). Always reads the live store, so an edited, archived or deleted
 * match is reflected each time it runs.
 */
object PendingMatchLoader {

    data class Loaded(
        val ordered: List<PendingMatchPresentation.Presented>,
        /** The current record of every match in [ordered]. */
        val records: Map<String, MemoryRecord>
    )

    fun load(context: Context, draftId: String): Loaded {
        val store = MemoryStore.getInstance(context)
        val matches = PossibleMatchFinder.find(context, draftId).matches
        val records = HashMap<String, MemoryRecord>()
        for (m in matches) store.getMemory(m.memoryId)?.let { records[m.memoryId] = it }
        val ordered = PendingMatchPresentation.order(
            matches,
            statusOf = { records[it]?.status },
            linksBack = { id ->
                records[id]?.status == "draft" &&
                    store.relationshipHintsForDraft(id).any { it.memoryId == draftId }
            }
        )
        return Loaded(ordered, ordered.associate { it.memoryId to records.getValue(it.memoryId) })
    }
}
