package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogTombstone

class GeneratedImageCategoryPlannerTest {

    @Test
    fun replacementRetainsCurrentImageUsedByUnselectedDataAndReportsIt() {
        val retained = record(ID_ONE, "one.png", "1".repeat(64))
        val incoming = record(ID_TWO, "two.png", "2".repeat(64))
        val result = GeneratedImageCategoryPlanner.plan(
            current = snapshot(active = listOf(retained)),
            backup = snapshot(
                active = listOf(incoming),
                tombstones = listOf(tombstone(ID_ONE, "one.png"))
            ),
            mode = PortableRestoreMode.REPLACE,
            protectedCurrentImageIds = setOf(ID_ONE)
        ) as GeneratedImageCategoryPlanner.Result.Ready

        assertEquals(listOf(incoming, retained), result.snapshot.active)
        assertTrue(result.snapshot.tombstones.isEmpty())
        assertEquals(listOf(ID_ONE), result.report.protectedCurrentImageIds)
    }

    @Test
    fun replacementDoesNotRetainUnreferencedCurrentImage() {
        val result = GeneratedImageCategoryPlanner.plan(
            current = snapshot(active = listOf(record(ID_ONE, "one.png", "1".repeat(64)))),
            backup = snapshot(active = listOf(record(ID_TWO, "two.png", "2".repeat(64)))),
            mode = PortableRestoreMode.REPLACE
        ) as GeneratedImageCategoryPlanner.Result.Ready

        assertEquals(listOf(ID_TWO), result.snapshot.active.map { it.imageId })
        assertTrue(result.report.protectedCurrentImageIds.isEmpty())
    }

    @Test
    fun backupVersionWinsWhenItUsesTheSameProtectedIdentity() {
        val current = record(ID_ONE, "one.png", "1".repeat(64))
        val backup = current.copy(fileHash = "2".repeat(64), locked = true)
        val result = GeneratedImageCategoryPlanner.plan(
            current = snapshot(active = listOf(current)),
            backup = snapshot(active = listOf(backup)),
            mode = PortableRestoreMode.REPLACE,
            protectedCurrentImageIds = setOf(ID_ONE)
        ) as GeneratedImageCategoryPlanner.Result.Ready

        assertEquals(listOf(backup), result.snapshot.active)
        assertTrue(result.report.protectedCurrentImageIds.isEmpty())
    }

    @Test
    fun protectedFileNameCollisionRejectsInsteadOfOverwritingEitherImage() {
        val result = GeneratedImageCategoryPlanner.plan(
            current = snapshot(active = listOf(record(ID_ONE, "shared.png", "1".repeat(64)))),
            backup = snapshot(active = listOf(record(ID_TWO, "shared.png", "2".repeat(64)))),
            mode = PortableRestoreMode.REPLACE,
            protectedCurrentImageIds = setOf(ID_ONE)
        )

        assertEquals(
            GeneratedImageCategoryPlanner.Result.Rejected(
                GeneratedImageCategoryPlanner.Rejection.ASSET_NAME_COLLISION
            ),
            result
        )
    }

    @Test
    fun mergeUsesStableIdentityRulesAndIgnoresReplacementProtection() {
        val current = record(ID_ONE, "one.png", "1".repeat(64))
        val incoming = record(ID_TWO, "two.png", "2".repeat(64))
        val result = GeneratedImageCategoryPlanner.plan(
            current = snapshot(active = listOf(current)),
            backup = snapshot(active = listOf(incoming)),
            mode = PortableRestoreMode.MERGE,
            protectedCurrentImageIds = setOf(ID_ONE)
        ) as GeneratedImageCategoryPlanner.Result.Ready

        assertEquals(listOf(current, incoming), result.snapshot.active)
        assertEquals(1, result.report.merge?.addedActive)
        assertTrue(result.report.protectedCurrentImageIds.isEmpty())
    }

    private fun snapshot(
        active: List<GeneratedImageCatalogRecord> = emptyList(),
        tombstones: List<GeneratedImageCatalogTombstone> = emptyList()
    ) = GeneratedImageCatalogSnapshot(active, tombstones, emptyMap(), emptyMap())

    private fun record(id: String, file: String, hash: String) = GeneratedImageCatalogRecord(
        imageId = id,
        fileHash = hash,
        assetFileName = file,
        mimeType = "image/png",
        width = 1,
        height = 1,
        createdAt = 1L,
        originChatId = null,
        originChatName = null,
        originMessageId = null
    )

    private fun tombstone(id: String, file: String) = GeneratedImageCatalogTombstone(
        imageId = id,
        assetFileName = file,
        deletedAt = 2L,
        reason = "gallery_delete"
    )

    private companion object {
        const val ID_ONE = "79b4e47b-b6d4-4e7d-8d8d-413215eab779"
        const val ID_TWO = "b9a82bd0-eec4-49a9-8808-802f2bfe207f"
    }
}
