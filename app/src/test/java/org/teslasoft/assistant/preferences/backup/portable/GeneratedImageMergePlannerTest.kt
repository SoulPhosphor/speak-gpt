package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogTombstone

class GeneratedImageMergePlannerTest {

    @Test
    fun identicalIdentityIsSkippedAndNewIdentityIsAdded() {
        val existing = record(ID_ONE, "one.png", "1".repeat(64))
        val added = record(ID_TWO, "two.png", "2".repeat(64))

        val result = GeneratedImageMergePlanner.merge(
            snapshot(active = listOf(existing)),
            snapshot(active = listOf(existing, added))
        )

        assertEquals(listOf(existing, added), result.snapshot.active)
        assertEquals(1, result.report.identicalSkipped)
        assertEquals(1, result.report.addedActive)
        assertTrue(result.report.conflicts.isEmpty())
    }

    @Test
    fun divergentIdentityKeepsCurrentRecordAndReportsConflict() {
        val current = record(ID_ONE, "one.png", "1".repeat(64))
        val backup = current.copy(locked = true)

        val result = GeneratedImageMergePlanner.merge(
            snapshot(active = listOf(current)),
            snapshot(active = listOf(backup))
        )

        assertEquals(listOf(current), result.snapshot.active)
        assertEquals(
            listOf(
                GeneratedImageMergePlanner.Conflict(
                    ID_ONE,
                    GeneratedImageMergePlanner.State.ACTIVE,
                    GeneratedImageMergePlanner.State.ACTIVE
                )
            ),
            result.report.conflicts
        )
    }

    @Test
    fun currentDeletionWinsOverBackupActiveIdentity() {
        val deleted = tombstone(ID_ONE, "one.png")
        val result = GeneratedImageMergePlanner.merge(
            snapshot(tombstones = listOf(deleted)),
            snapshot(active = listOf(record(ID_ONE, "one.png", "1".repeat(64))))
        )

        assertTrue(result.snapshot.active.isEmpty())
        assertEquals(listOf(deleted), result.snapshot.tombstones)
        assertEquals(
            GeneratedImageMergePlanner.Conflict(
                ID_ONE,
                GeneratedImageMergePlanner.State.DELETED,
                GeneratedImageMergePlanner.State.ACTIVE
            ),
            result.report.conflicts.single()
        )
    }

    @Test
    fun currentActiveWinsOverBackupDeletion() {
        val current = record(ID_ONE, "one.png", "1".repeat(64))
        val result = GeneratedImageMergePlanner.merge(
            snapshot(active = listOf(current)),
            snapshot(tombstones = listOf(tombstone(ID_ONE, "one.png")))
        )

        assertEquals(listOf(current), result.snapshot.active)
        assertTrue(result.snapshot.tombstones.isEmpty())
        assertEquals(GeneratedImageMergePlanner.State.DELETED, result.report.conflicts.single().backupState)
    }

    @Test
    fun physicalNameCollisionWithDifferentBytesDoesNotOverwriteCurrentAsset() {
        val current = record(ID_ONE, "shared.png", "1".repeat(64))
        val incoming = record(ID_TWO, "shared.png", "2".repeat(64))
        val result = GeneratedImageMergePlanner.merge(
            snapshot(active = listOf(current)),
            snapshot(active = listOf(incoming))
        )

        assertEquals(listOf(current), result.snapshot.active)
        assertEquals(ID_TWO, result.report.conflicts.single().imageId)
    }

    @Test
    fun currentInternalMetadataWinsWhileNewKeysAreImported() {
        val result = GeneratedImageMergePlanner.merge(
            snapshot(meta = mapOf("shared" to "current"), backfill = mapOf("chat" to 20L)),
            snapshot(
                meta = mapOf("shared" to "backup", "new" to "value"),
                backfill = mapOf("chat" to 10L, "new-chat" to 30L)
            )
        )

        assertEquals(mapOf("shared" to "current", "new" to "value"), result.snapshot.meta)
        assertEquals(mapOf("chat" to 20L, "new-chat" to 30L), result.snapshot.backfillChats)
    }

    private fun snapshot(
        active: List<GeneratedImageCatalogRecord> = emptyList(),
        tombstones: List<GeneratedImageCatalogTombstone> = emptyList(),
        meta: Map<String, String> = emptyMap(),
        backfill: Map<String, Long> = emptyMap()
    ) = GeneratedImageCatalogSnapshot(active, tombstones, meta, backfill)

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
