package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogTombstone
import org.teslasoft.assistant.util.Hash

class GeneratedImageRestoreTransactionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val oldRecord = record(
        "79b4e47b-b6d4-4e7d-8d8d-413215eab779",
        "79b4e47b-b6d4-4e7d-8d8d-413215eab779.png",
        "1".repeat(64),
        locked = true
    )
    private val newRecord = record(
        "b9a82bd0-eec4-49a9-8808-802f2bfe207f",
        "b9a82bd0-eec4-49a9-8808-802f2bfe207f.png",
        "2".repeat(64),
        locked = false
    )

    @Test
    fun successfulReplacementCommitsCatalogAndBytesTogether() {
        val images = tmp.newFolder("images")
        val journal = File(tmp.root, "journal")
        val oldBytes = byteArrayOf(1, 2, 3)
        val newBytes = byteArrayOf(4, 5, 6)
        File(images, oldRecord.assetFileName).writeBytes(oldBytes)
        val incoming = tmp.newFile("incoming.png").apply { writeBytes(newBytes) }
        val backend = FakeCatalog(snapshot(oldRecord))
        val desired = GeneratedImageCatalogSnapshot(
            active = listOf(newRecord),
            tombstones = listOf(
                GeneratedImageCatalogTombstone(
                    "legacy-${"a".repeat(64)}",
                    null,
                    123L,
                    "gallery_delete"
                )
            ),
            meta = mapOf("legacy_backfill_version" to "1"),
            backfillChats = emptyMap()
        )

        val result = GeneratedImageRestoreTransaction.replace(
            journal,
            images,
            desired,
            mapOf(newRecord.assetFileName to incoming),
            backend
        )

        assertEquals(GeneratedImageRestoreTransaction.Result.Success, result)
        assertEquals(desired, backend.value)
        assertFalse(File(images, oldRecord.assetFileName).exists())
        assertArrayEquals(newBytes, File(images, newRecord.assetFileName).readBytes())
        assertFalse(journal.exists())
    }

    @Test
    fun catalogFailureRollsBackFilesAndCatalog() {
        val images = tmp.newFolder("rollback_images")
        val journal = File(tmp.root, "rollback_journal")
        val oldBytes = byteArrayOf(1, 2, 3)
        File(images, oldRecord.assetFileName).writeBytes(oldBytes)
        val incoming = tmp.newFile("rollback_incoming.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val original = snapshot(oldRecord)
        val backend = FakeCatalog(original, failFirstReplace = true)

        val result = GeneratedImageRestoreTransaction.replace(
            journal,
            images,
            snapshot(newRecord),
            mapOf(newRecord.assetFileName to incoming),
            backend
        )

        assertEquals(
            GeneratedImageRestoreTransaction.Result.Failed(
                GeneratedImageRestoreTransaction.Failure.APPLY_FAILED
            ),
            result
        )
        assertEquals(original, backend.value)
        assertArrayEquals(oldBytes, File(images, oldRecord.assetFileName).readBytes())
        assertFalse(File(images, newRecord.assetFileName).exists())
        assertFalse(journal.exists())
    }

    @Test
    fun startupRecoveryRollsBackAnInterruptionAfterCatalogCommit() {
        val images = tmp.newFolder("crash_images")
        val journal = File(tmp.root, "crash_journal")
        val oldBytes = byteArrayOf(1, 1, 1)
        File(images, oldRecord.assetFileName).writeBytes(oldBytes)
        val incoming = tmp.newFile("crash_incoming.png").apply { writeBytes(byteArrayOf(2, 2, 2)) }
        val original = snapshot(oldRecord)
        val backend = FakeCatalog(original)

        var interrupted = false
        try {
            GeneratedImageRestoreTransaction.replace(
                journal,
                images,
                snapshot(newRecord),
                mapOf(newRecord.assetFileName to incoming),
                backend,
                GeneratedImageRestoreTransaction.InterruptionPoint.AFTER_CATALOG
            )
        } catch (_: Error) {
            interrupted = true
        }
        assertTrue(interrupted)
        assertTrue(journal.exists())
        assertEquals(snapshot(newRecord), backend.value)

        assertTrue(GeneratedImageRestoreTransaction.recover(journal, images, backend))
        assertEquals(original, backend.value)
        assertArrayEquals(oldBytes, File(images, oldRecord.assetFileName).readBytes())
        assertFalse(File(images, newRecord.assetFileName).exists())
        assertFalse(journal.exists())
    }

    @Test
    fun unreadableExistingJournalIsNeverDeletedOrGuessedAway() {
        val images = tmp.newFolder("damaged_journal_images")
        val journal = tmp.newFolder("damaged_journal")
        File(journal, "state.json").writeText("not valid journal data")
        val backend = FakeCatalog(snapshot(oldRecord))

        assertFalse(GeneratedImageRestoreTransaction.recover(journal, images, backend))
        assertTrue(journal.exists())
        assertTrue(File(journal, "state.json").exists())
        assertEquals(snapshot(oldRecord), backend.value)
    }

    @Test
    fun packagePreparationRejectsMissingAndExtraBytes() {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00
        )
        val hash = Hash.hash(Base64.getEncoder().encodeToString(bytes))
        val record = newRecord.copy(fileHash = hash)
        val catalogFile = tmp.newFile("restore_catalog.json").apply {
            writeText(GeneratedImagePortableCatalog.toJson(snapshot(record)))
        }
        val asset = tmp.newFile(record.assetFileName).apply { writeBytes(bytes) }
        val catalogArtifact = artifact(
            "generated_images/catalog.json",
            PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
            catalogFile,
            1
        )
        val assetArtifact = artifact(
            "generated_images/assets/${record.assetFileName}",
            PortablePackage.TYPE_GENERATED_IMAGE_ASSET,
            asset
        )

        assertTrue(
            GeneratedImagePortableRestoreManager.prepare(listOf(catalogArtifact, assetArtifact)) is
                GeneratedImagePortableRestoreManager.PrepareResult.Ready
        )
        val copiedReference = record.copy(
            imageId = "6057dbd6-d3b6-4e93-9cd7-49a73b04dd83",
            originChatId = "5531ee28-4e58-4be0-89a5-06d1d5c52cc1"
        )
        val copiedCatalog = tmp.newFile("copied_restore_catalog.json").apply {
            writeText(
                GeneratedImagePortableCatalog.toJson(
                    GeneratedImageCatalogSnapshot(
                        active = listOf(record, copiedReference),
                        tombstones = emptyList(),
                        meta = emptyMap(),
                        backfillChats = emptyMap()
                    )
                )
            )
        }
        assertTrue(
            GeneratedImagePortableRestoreManager.prepare(
                listOf(
                    artifact(
                        "generated_images/catalog.json",
                        PortablePackage.TYPE_GENERATED_IMAGES_CATALOG,
                        copiedCatalog,
                        1
                    ),
                    assetArtifact
                )
            ) is GeneratedImagePortableRestoreManager.PrepareResult.Ready
        )
        assertEquals(
            GeneratedImagePortableRestoreManager.PrepareResult.Invalid(
                GeneratedImagePortableRestoreManager.InvalidReason.MISSING_ASSET
            ),
            GeneratedImagePortableRestoreManager.prepare(listOf(catalogArtifact))
        )
        val extra = artifact(
            "generated_images/assets/extra.png",
            PortablePackage.TYPE_GENERATED_IMAGE_ASSET,
            asset
        )
        assertEquals(
            GeneratedImagePortableRestoreManager.PrepareResult.Invalid(
                GeneratedImagePortableRestoreManager.InvalidReason.EXTRA_ASSET
            ),
            GeneratedImagePortableRestoreManager.prepare(listOf(catalogArtifact, assetArtifact, extra))
        )
    }

    private fun record(id: String, name: String, hash: String, locked: Boolean) =
        GeneratedImageCatalogRecord(
            imageId = id,
            fileHash = hash,
            assetFileName = name,
            mimeType = "image/png",
            width = 1,
            height = 1,
            createdAt = 123L,
            originChatId = null,
            originChatName = "Deleted origin",
            originMessageId = id,
            locked = locked,
            source = GeneratedImageCatalogRecord.Source.GENERATED
        )

    private fun snapshot(record: GeneratedImageCatalogRecord) = GeneratedImageCatalogSnapshot(
        active = listOf(record),
        tombstones = emptyList(),
        meta = emptyMap(),
        backfillChats = emptyMap()
    )

    private fun artifact(
        name: String,
        type: String,
        file: File,
        schemaVersion: Int? = null
    ) = PortablePackage.ValidatedArtifact(name, type, file, null, null, schemaVersion)

    private class FakeCatalog(
        var value: GeneratedImageCatalogSnapshot,
        private var failFirstReplace: Boolean = false
    ) : GeneratedImageRestoreTransaction.CatalogBackend {
        override fun snapshot(): GeneratedImageCatalogSnapshot = value

        override fun replace(snapshot: GeneratedImageCatalogSnapshot): Boolean {
            if (failFirstReplace) {
                failFirstReplace = false
                return false
            }
            value = snapshot
            return true
        }
    }
}
