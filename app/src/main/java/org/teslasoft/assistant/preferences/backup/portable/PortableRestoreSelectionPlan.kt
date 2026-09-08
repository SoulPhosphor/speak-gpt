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

/**
 * Read-only gate between a validated package inventory and restore staging.
 * Missing categories are never silently removed: the first pass returns
 * [Result.Missing], and only [restoreAvailable] records the user's explicit
 * decision to continue with the requested categories that exist.
 *
 * Package corruption is deliberately outside this type. [PortablePackage]
 * must decode and validate every declared artifact before an inventory can be
 * supplied here, so damaged data fails instead of being described as absent.
 */
object PortableRestoreSelectionPlan {
    data class Selection(
        val category: PortableRestoreCategory,
        val mode: PortableRestoreMode
    )

    sealed class Result {
        data class Ready(val selections: List<Selection>) : Result()
        data class Missing(
            val requested: List<Selection>,
            val missing: List<PortableRestoreCategory>,
            val available: List<Selection>
        ) : Result()

        data object EmptySelection : Result()
        data object NothingAvailable : Result()
        data object DuplicateCategory : Result()
    }

    fun inspect(
        requested: List<Selection>,
        inventory: PortableRestoreInventory.Inventory
    ): Result {
        if (requested.isEmpty()) return Result.EmptySelection
        if (requested.map { it.category }.distinct().size != requested.size) {
            return Result.DuplicateCategory
        }
        val ordered = requested.sortedBy { it.category.ordinal }
        val available = ordered.filter { it.category in inventory.available }
        val missing = ordered.map { it.category }.filterNot(inventory.available::contains)
        return when {
            missing.isEmpty() -> Result.Ready(ordered)
            available.isEmpty() -> Result.NothingAvailable
            else -> Result.Missing(ordered, missing, available)
        }
    }

    /** Call only after the user chooses Restore Available Categories. */
    fun restoreAvailable(result: Result.Missing): Result.Ready =
        Result.Ready(result.available)
}
