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

import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot

/**
 * Plans the complete Generated Images category without touching storage.
 * Replacement retains a current image only when an unselected category still
 * references it. Merge delegates to the stable-ID merge policy.
 */
object GeneratedImageCategoryPlanner {
    enum class Rejection { INVALID_CURRENT, INVALID_BACKUP, ASSET_NAME_COLLISION }

    data class Report(
        val merge: GeneratedImageMergePlanner.Report? = null,
        val protectedCurrentImageIds: List<String> = emptyList()
    )

    sealed class Result {
        data class Ready(
            val snapshot: GeneratedImageCatalogSnapshot,
            val report: Report
        ) : Result()

        data class Rejected(val reason: Rejection) : Result()
    }

    fun plan(
        current: GeneratedImageCatalogSnapshot,
        backup: GeneratedImageCatalogSnapshot,
        mode: PortableRestoreMode,
        protectedCurrentImageIds: Set<String> = emptySet()
    ): Result {
        if (GeneratedImagePortableCatalog.validate(current) != null) {
            return Result.Rejected(Rejection.INVALID_CURRENT)
        }
        if (GeneratedImagePortableCatalog.validate(backup) != null) {
            return Result.Rejected(Rejection.INVALID_BACKUP)
        }
        if (mode == PortableRestoreMode.MERGE) {
            val merged = GeneratedImageMergePlanner.merge(current, backup)
            return Result.Ready(merged.snapshot, Report(merge = merged.report))
        }

        val backupIds = backup.active.mapTo(HashSet()) { it.imageId }
        val retained = current.active.filter {
            it.imageId in protectedCurrentImageIds && it.imageId !in backupIds
        }
        val hashesByName = backup.active.associate { it.assetFileName to it.fileHash }.toMutableMap()
        for (record in retained) {
            val existingHash = hashesByName[record.assetFileName]
            if (existingHash != null && !existingHash.equals(record.fileHash, ignoreCase = true)) {
                return Result.Rejected(Rejection.ASSET_NAME_COLLISION)
            }
            hashesByName.putIfAbsent(record.assetFileName, record.fileHash)
        }
        val retainedIds = retained.mapTo(HashSet()) { it.imageId }
        return Result.Ready(
            snapshot = GeneratedImageCatalogSnapshot(
                active = backup.active + retained,
                tombstones = backup.tombstones.filterNot { it.imageId in retainedIds },
                meta = backup.meta,
                backfillChats = backup.backfillChats
            ),
            report = Report(protectedCurrentImageIds = retained.map { it.imageId })
        )
    }
}
