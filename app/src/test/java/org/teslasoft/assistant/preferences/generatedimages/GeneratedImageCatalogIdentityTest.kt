/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.teslasoft.assistant.util.GeneratedImageStorage

class GeneratedImageCatalogIdentityTest {

    private fun record(id: String, hash: String = "same-hash") =
        GeneratedImageCatalogRecord(
            imageId = id,
            fileHash = hash,
            assetFileName = "$id.png",
            mimeType = "image/png",
            width = 1,
            height = 1,
            createdAt = 10L,
            originChatId = "chat-uuid",
            originChatName = "Original Chat",
            originMessageId = id,
            locked = false
        )

    @Test
    fun renamingAnyDisplayLabelCannotChangeImageUuid() {
        val original = record("550e8400-e29b-41d4-a716-446655440000")
        val renamedChat = original.copy(originChatName = "Renamed Chat")

        assertEquals(original.imageId, renamedChat.imageId)
        assertEquals(original.assetFileName, renamedChat.assetFileName)
        assertEquals(original.originChatId, renamedChat.originChatId)
    }

    @Test
    fun equalBytesFromIndependentGenerationsKeepSeparateIdentityAndFiles() {
        val first = record("550e8400-e29b-41d4-a716-446655440000")
        val second = record("066f47ec-1c4b-4ce6-bd34-09f1408cfc5f")

        assertEquals(first.fileHash, second.fileHash)
        assertNotEquals(first.imageId, second.imageId)
        assertNotEquals(first.assetFileName, second.assetFileName)
        assertNotEquals(
            GeneratedImageStorage.catalogFileName(first.imageId, "png"),
            GeneratedImageStorage.catalogFileName(second.imageId, "png")
        )
    }

    @Test
    fun copiedLegacyReferenceDeduplicatesWithoutUsingChatLabel() {
        val first = LegacyGeneratedImageIdentity.resolve(null, "hash", 1234L, "hash.png")
        val copied = LegacyGeneratedImageIdentity.resolve(null, "hash", 1234L, "hash.png")
        assertEquals(first, copied)
    }

    @Test
    fun distinctLegacyCreationTimesDoNotCollapseIdenticalBytes() {
        val first = LegacyGeneratedImageIdentity.resolve(null, "hash", 1234L, "hash.png")
        val second = LegacyGeneratedImageIdentity.resolve(null, "hash", 1235L, "hash.png")
        assertNotEquals(first, second)
    }

    @Test
    fun existingStableIdAlwaysOutranksLegacyTuple() {
        assertEquals(
            "stable-image-uuid",
            LegacyGeneratedImageIdentity.resolve(
                "stable-image-uuid",
                "different-hash",
                999L,
                "different.png"
            )
        )
    }
}
