/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogSnapshot
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogTombstone
import org.teslasoft.assistant.util.Hash
import java.util.Base64

class GeneratedImagePortableCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val record = GeneratedImageCatalogRecord(
        imageId = "79b4e47b-b6d4-4e7d-8d8d-413215eab779",
        fileHash = "a".repeat(64),
        assetFileName = "79b4e47b-b6d4-4e7d-8d8d-413215eab779.png",
        mimeType = "image/png",
        width = 1024,
        height = 1024,
        createdAt = 1_725_000_000_000L,
        originChatId = "648778e3-c0e3-465a-a330-8768aa4be72a",
        originChatName = "Stable title metadata",
        originMessageId = "79b4e47b-b6d4-4e7d-8d8d-413215eab779",
        locked = true,
        source = GeneratedImageCatalogRecord.Source.GENERATED
    )

    @Test
    fun logicalCatalogRoundTripPreservesOwnershipLocksAndTombstones() {
        val snapshot = GeneratedImageCatalogSnapshot(
            active = listOf(record),
            tombstones = listOf(
                GeneratedImageCatalogTombstone(
                    imageId = "legacy-${"b".repeat(64)}",
                    assetFileName = "${"b".repeat(64)}.jpg",
                    deletedAt = 1_724_000_000_000L,
                    reason = "gallery_delete"
                )
            ),
            meta = mapOf("legacy_backfill_version" to "1"),
            backfillChats = mapOf(record.originChatId!! to 1_725_000_000_001L)
        )

        val parsed = GeneratedImagePortableCatalog.parse(
            GeneratedImagePortableCatalog.toJson(snapshot)
        )

        assertTrue(parsed is GeneratedImagePortableCatalog.ParseResult.Ok)
        assertEquals(snapshot, (parsed as GeneratedImagePortableCatalog.ParseResult.Ok).snapshot)
    }

    @Test
    fun duplicateImageIdentityIsRejected() {
        val json = GeneratedImagePortableCatalog.toJsonUnchecked(
            GeneratedImageCatalogSnapshot(
                active = listOf(record, record),
                tombstones = emptyList(),
                meta = emptyMap(),
                backfillChats = emptyMap()
            )
        )

        assertTrue(
            GeneratedImagePortableCatalog.parse(json) is
                GeneratedImagePortableCatalog.ParseResult.Invalid
        )
    }

    @Test
    fun traversalAssetNameIsRejected() {
        val unsafe = record.copy(assetFileName = "../outside.png")
        val parsed = GeneratedImagePortableCatalog.parse(
            GeneratedImagePortableCatalog.toJsonUnchecked(
                GeneratedImageCatalogSnapshot(
                    active = listOf(unsafe),
                    tombstones = emptyList(),
                    meta = emptyMap(),
                    backfillChats = emptyMap()
                )
            )
        )

        assertTrue(parsed is GeneratedImagePortableCatalog.ParseResult.Invalid)
    }

    @Test
    fun activeIdentityCannotAlsoBeATombstone() {
        val parsed = GeneratedImagePortableCatalog.parse(
            GeneratedImagePortableCatalog.toJsonUnchecked(
                GeneratedImageCatalogSnapshot(
                    active = listOf(record),
                    tombstones = listOf(
                        GeneratedImageCatalogTombstone(
                            record.imageId,
                            record.assetFileName,
                            record.createdAt,
                            "missing"
                        )
                    )
                )
            )
        )

        assertTrue(parsed is GeneratedImagePortableCatalog.ParseResult.Invalid)
    }

    @Test
    fun assetValidationChecksBytesHashMimeAndExtension() {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00
        )
        val file = tmp.newFile(record.assetFileName).apply { writeBytes(bytes) }
        val expectedHash = Hash.hash(Base64.getEncoder().encodeToString(bytes))

        assertTrue(
            GeneratedImagePortableBackup.isValidAsset(
                file,
                record.copy(fileHash = expectedHash)
            )
        )
        assertTrue(
            !GeneratedImagePortableBackup.isValidAsset(
                file,
                record.copy(fileHash = "0".repeat(64))
            )
        )
    }
}
