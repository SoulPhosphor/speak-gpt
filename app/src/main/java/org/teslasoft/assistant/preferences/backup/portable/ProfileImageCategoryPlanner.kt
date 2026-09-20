/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.teslasoft.assistant.preferences.profileimages.ProfileImageRecord

/** Stable content-hash planning for the complete Avatar/Profile Images gallery. */
object ProfileImageCategoryPlanner {
    data class Report(val protectedCurrentHashes: List<String>)

    sealed class Result {
        data class Ready(
            val records: List<ProfileImageRecord>,
            val report: Report
        ) : Result()
        data object Invalid : Result()
    }

    fun plan(
        current: List<ProfileImageRecord>,
        backup: List<ProfileImageRecord>,
        mode: PortableRestoreMode,
        protectedCurrentHashes: Set<String> = emptySet()
    ): Result {
        if (!valid(current) || !valid(backup)) return Result.Invalid
        if (mode == PortableRestoreMode.MERGE) {
            val currentHashes = current.mapTo(HashSet(), ProfileImageRecord::hash)
            return Result.Ready(
                current + backup.filter { it.hash !in currentHashes },
                Report(emptyList())
            )
        }
        val backupHashes = backup.mapTo(HashSet(), ProfileImageRecord::hash)
        val retained = current.filter { it.hash in protectedCurrentHashes && it.hash !in backupHashes }
        return Result.Ready(backup + retained, Report(retained.map(ProfileImageRecord::hash)))
    }

    private fun valid(records: List<ProfileImageRecord>): Boolean =
        records.all { HASH.matches(it.hash) && it.createdAt >= 0L } &&
            records.map(ProfileImageRecord::hash).distinct().size == records.size

    private val HASH = Regex("^[0-9a-f]{64}$")
}
