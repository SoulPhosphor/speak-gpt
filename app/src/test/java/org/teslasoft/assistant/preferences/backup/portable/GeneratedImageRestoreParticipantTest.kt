package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.util.Hash

class GeneratedImageRestoreParticipantTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun stagesThenAppliesAndRollsBackWithoutReadingLiveDataDuringValidation() {
        val oldBytes = png(1)
        val newBytes = png(2)
        val oldRecord = record(ID_ONE, "one.png", oldBytes)
        val newRecord = record(ID_TWO, "two.png", newBytes)
        val liveDir = tmp.newFolder("live")
        File(liveDir, oldRecord.assetFileName).writeBytes(oldBytes)
        val artifacts = artifacts(snapshot(newRecord), newRecord, newBytes)
        val backend = FakeBackend(snapshot(oldRecord), liveDir)
        val staging = tmp.newFolder("empty_staging")
        val participant = GeneratedImageRestoreParticipant(
            artifacts,
            PortableRestoreMode.MERGE,
            emptySet(),
            staging,
            backend
        )

        assertTrue(participant.validate())
        assertEquals(0, backend.snapshotCalls)
        assertTrue(participant.stage())
        assertEquals(1, backend.snapshotCalls)
        assertTrue(participant.apply())
        assertEquals(listOf(ID_ONE, ID_TWO), backend.value.active.map { it.imageId })
        assertEquals(1, participant.report?.merge?.addedActive)
        assertTrue(participant.rollback())
        assertEquals(snapshot(oldRecord), backend.value)
        assertEquals(1, backend.restoreOriginalCalls)

        participant.cleanup()
        assertFalse(staging.exists())
    }

    @Test
    fun precomputedPlanDrivesValidationReportAndStageWithoutAnotherSnapshot() {
        val oldBytes = png(4)
        val newBytes = png(5)
        val oldRecord = record(ID_ONE, "one.png", oldBytes)
        val newRecord = record(ID_TWO, "two.png", newBytes)
        val liveDir = tmp.newFolder("precomputed_live")
        File(liveDir, oldRecord.assetFileName).writeBytes(oldBytes)
        val prepared = GeneratedImagePortableRestoreManager.Prepared(
            snapshot = snapshot(newRecord),
            assets = artifacts(snapshot(newRecord), newRecord, newBytes)
                .filter { it.type == PortablePackage.TYPE_GENERATED_IMAGE_ASSET }
                .associate { it.path.substringAfterLast('/') to it.file }
        )
        val planned = GeneratedImageCategoryPlanner.plan(
            current = snapshot(oldRecord),
            backup = prepared.snapshot,
            mode = PortableRestoreMode.MERGE,
            protectedCurrentImageIds = emptySet()
        )
        val backend = FakeBackend(snapshot(oldRecord), liveDir)
        val participant = GeneratedImageRestoreParticipant(
            emptyList(),
            PortableRestoreMode.MERGE,
            emptySet(),
            File(tmp.root, "precomputed_staging"),
            backend,
            precomputed = GeneratedImageRestoreParticipant.PreparedPlan(
                current = snapshot(oldRecord),
                incoming = prepared,
                desired = planned.desired,
                report = planned.report
            )
        )

        assertTrue(participant.validate())
        assertEquals(planned.report, participant.report)
        assertTrue(participant.stage())
        assertEquals(0, backend.snapshotCalls)
        assertTrue(participant.apply())
        assertEquals(planned.desired, backend.value)
    }

    @Test
    fun missingCurrentBytesFailStagingBeforeAnyReplace() {
        val oldBytes = png(1)
        val newBytes = png(2)
        val oldRecord = record(ID_ONE, "one.png", oldBytes)
        val newRecord = record(ID_TWO, "two.png", newBytes)
        val backend = FakeBackend(snapshot(oldRecord), tmp.newFolder("missing_live"))
        val participant = GeneratedImageRestoreParticipant(
            artifacts(backup = snapshot(newRecord), newRecord = newRecord, bytes = newBytes),
            PortableRestoreMode.REPLACE,
            emptySet(),
            File(tmp.root, "staging_missing"),
            backend
        )

        assertTrue(participant.validate())
        assertFalse(participant.stage())
        assertEquals(0, backend.replaceCalls)
        assertEquals(snapshot(oldRecord), backend.value)
    }

    @Test
    fun rollbackRemovesStoreThatDidNotExistBeforeStaging() {
        val newBytes = png(3)
        val newRecord = record(ID_TWO, "two.png", newBytes)
        val backend = FakeBackend(
            value = snapshot(),
            liveDir = tmp.newFolder("unprovisioned_live"),
            wasProvisioned = false
        )
        val participant = GeneratedImageRestoreParticipant(
            artifacts(snapshot(newRecord), newRecord, newBytes),
            PortableRestoreMode.REPLACE,
            emptySet(),
            File(tmp.root, "staging_unprovisioned"),
            backend
        )

        assertTrue(participant.validate())
        assertTrue(participant.stage())
        assertTrue(participant.apply())
        assertTrue(participant.rollback())
        assertEquals(snapshot(), backend.value)
        assertEquals(1, backend.removeProvisionedStoreCalls)
    }

    private fun artifacts(
        backup: GeneratedImageCatalogSnapshot = snapshot(),
        newRecord: GeneratedImageCatalogRecord,
        bytes: ByteArray
    ): List<PortablePackage.ValidatedArtifact> {
        val catalog = tmp.newFile("catalog-${newRecord.imageId}.json").apply {
            writeText(GeneratedImagePortableCatalog.toJson(backup))
        }
        val image = tmp.newFile("asset-${newRecord.imageId}.png").apply { writeBytes(bytes) }
        return listOf(
            PortablePackage.ValidatedArtifact(
                "generated_images/catalog.json",
                PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
                catalog,
                null,
                null,
                1
            ),
            PortablePackage.ValidatedArtifact(
                "generated_images/assets/${newRecord.assetFileName}",
                PortablePackage.TYPE_GENERATED_IMAGE_ASSET,
                image,
                null,
                null,
                null
            )
        )
    }

    private fun snapshot(
        vararg record: GeneratedImageCatalogRecord
    ) = GeneratedImageCatalogSnapshot(record.toList(), emptyList(), emptyMap(), emptyMap())

    private fun record(id: String, file: String, bytes: ByteArray) = GeneratedImageCatalogRecord(
        imageId = id,
        fileHash = Hash.hash(Base64.getEncoder().encodeToString(bytes)),
        assetFileName = file,
        mimeType = "image/png",
        width = 1,
        height = 1,
        createdAt = 1L,
        originChatId = null,
        originChatName = null,
        originMessageId = null
    )

    private fun png(marker: Int) = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        marker.toByte(), 0x00, 0x00, 0x00
    )

    private class FakeBackend(
        var value: GeneratedImageCatalogSnapshot,
        private val liveDir: File,
        private val wasProvisioned: Boolean = true
    ) : GeneratedImageRestoreParticipant.Backend {
        var snapshotCalls = 0
        var replaceCalls = 0
        var restoreOriginalCalls = 0
        var removeProvisionedStoreCalls = 0

        override fun snapshot(): GeneratedImageCatalogSnapshot {
            snapshotCalls++
            return value
        }

        override fun assetFile(fileName: String): File? =
            File(liveDir, fileName).takeIf(File::isFile)

        override fun replace(
            snapshot: GeneratedImageCatalogSnapshot,
            assets: Map<String, File>
        ): Boolean {
            replaceCalls++
            value = snapshot
            return true
        }

        override fun restoreOriginal(
            snapshot: GeneratedImageCatalogSnapshot,
            assets: Map<String, File>
        ): Boolean {
            restoreOriginalCalls++
            return replace(snapshot, assets)
        }

        override fun recoverPending(): Boolean = true

        override fun wasProvisionedBeforeStage(): Boolean = wasProvisioned

        override fun removeProvisionedStore(): Boolean {
            removeProvisionedStoreCalls++
            return true
        }
    }

    private companion object {
        const val ID_ONE = "79b4e47b-b6d4-4e7d-8d8d-413215eab779"
        const val ID_TWO = "b9a82bd0-eec4-49a9-8808-802f2bfe207f"
    }
}
