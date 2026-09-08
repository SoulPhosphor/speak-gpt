package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.profileimages.ProfileImageRecord

class ProfileImageCategoryPlannerTest {
    @Test
    fun `merge keeps current metadata and appends new backup hashes`() {
        val same = "a".repeat(64)
        val added = "b".repeat(64)
        val ready = ProfileImageCategoryPlanner.plan(
            listOf(ProfileImageRecord(same, 10L)),
            listOf(ProfileImageRecord(same, 99L), ProfileImageRecord(added, 20L)),
            PortableRestoreMode.MERGE
        ) as ProfileImageCategoryPlanner.Result.Ready

        assertEquals(
            listOf(ProfileImageRecord(same, 10L), ProfileImageRecord(added, 20L)),
            ready.records
        )
    }

    @Test
    fun `replace retains current images protected by unselected identities`() {
        val protected = "a".repeat(64)
        val removed = "b".repeat(64)
        val backup = "c".repeat(64)
        val ready = ProfileImageCategoryPlanner.plan(
            listOf(ProfileImageRecord(protected, 1L), ProfileImageRecord(removed, 2L)),
            listOf(ProfileImageRecord(backup, 3L)),
            PortableRestoreMode.REPLACE,
            setOf(protected)
        ) as ProfileImageCategoryPlanner.Result.Ready

        assertEquals(listOf(backup, protected), ready.records.map { it.hash })
        assertEquals(listOf(protected), ready.report.protectedCurrentHashes)
    }

    @Test
    fun `duplicate hashes are rejected`() {
        val hash = "a".repeat(64)
        assertEquals(
            ProfileImageCategoryPlanner.Result.Invalid,
            ProfileImageCategoryPlanner.plan(
                emptyList(),
                listOf(ProfileImageRecord(hash, 1L), ProfileImageRecord(hash, 2L)),
                PortableRestoreMode.MERGE
            )
        )
    }
}
