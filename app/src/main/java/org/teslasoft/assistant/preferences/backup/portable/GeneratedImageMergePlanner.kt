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

package org.teslasoft.assistant.preferences.backup.portable

import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogTombstone

/** Stable-image-ID merge rules for the complete Generated Images category. */
object GeneratedImageMergePlanner {
    enum class State { ACTIVE, DELETED }

    data class Conflict(
        val imageId: String,
        val currentState: State,
        val backupState: State
    )

    data class Report(
        val addedActive: Int,
        val addedTombstones: Int,
        val identicalSkipped: Int,
        val conflicts: List<Conflict>
    )

    data class Plan(
        val snapshot: GeneratedImageCatalogSnapshot,
        val report: Report
    )

    fun merge(
        current: GeneratedImageCatalogSnapshot,
        backup: GeneratedImageCatalogSnapshot
    ): Plan {
        require(GeneratedImagePortableCatalog.validate(current) == null)
        require(GeneratedImagePortableCatalog.validate(backup) == null)

        val active = current.active.associateByTo(LinkedHashMap()) { it.imageId }
        val tombstones = current.tombstones.associateByTo(LinkedHashMap()) { it.imageId }
        val assets = LinkedHashMap<String, String>()
        current.active.forEach { record -> assets.putIfAbsent(record.assetFileName, record.fileHash) }
        val conflicts = ArrayList<Conflict>()
        var addedActive = 0
        var addedTombstones = 0
        var identicalSkipped = 0

        backup.active.forEach { incoming ->
            val existingActive = active[incoming.imageId]
            val existingTombstone = tombstones[incoming.imageId]
            val assetHash = assets[incoming.assetFileName]
            when {
                existingActive == incoming -> identicalSkipped++
                existingActive != null -> conflicts.add(
                    Conflict(incoming.imageId, State.ACTIVE, State.ACTIVE)
                )
                existingTombstone != null -> conflicts.add(
                    Conflict(incoming.imageId, State.DELETED, State.ACTIVE)
                )
                assetHash != null && !assetHash.equals(incoming.fileHash, ignoreCase = true) ->
                    conflicts.add(Conflict(incoming.imageId, State.ACTIVE, State.ACTIVE))
                else -> {
                    active[incoming.imageId] = incoming
                    assets.putIfAbsent(incoming.assetFileName, incoming.fileHash)
                    addedActive++
                }
            }
        }

        backup.tombstones.forEach { incoming ->
            val existingActive = active[incoming.imageId]
            val existingTombstone = tombstones[incoming.imageId]
            when {
                existingTombstone == incoming -> identicalSkipped++
                existingTombstone != null -> conflicts.add(
                    Conflict(incoming.imageId, State.DELETED, State.DELETED)
                )
                existingActive != null -> conflicts.add(
                    Conflict(incoming.imageId, State.ACTIVE, State.DELETED)
                )
                else -> {
                    tombstones[incoming.imageId] = incoming
                    addedTombstones++
                }
            }
        }

        val meta = LinkedHashMap(backup.meta).apply { putAll(current.meta) }
        val backfill = LinkedHashMap(backup.backfillChats).apply { putAll(current.backfillChats) }
        return Plan(
            snapshot = GeneratedImageCatalogSnapshot(
                active = active.values.toList(),
                tombstones = tombstones.values.toList(),
                meta = meta,
                backfillChats = backfill
            ),
            report = Report(addedActive, addedTombstones, identicalSkipped, conflicts)
        )
    }
}
