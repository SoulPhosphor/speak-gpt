/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLCipher coverage. The app is arm64-only, so CI compiles this suite;
 * execution belongs on an arm64 device/emulator. */
@RunWith(AndroidJUnit4::class)
class GeneratedImageCatalogInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "generated-image-catalog-test-key".toByteArray()
    private val names = ArrayList<String>()

    @After
    fun cleanup() {
        for (name in names) {
            try { context.getDatabasePath(name).delete() } catch (_: Exception) { }
            try { context.getDatabasePath("$name-wal").delete() } catch (_: Exception) { }
            try { context.getDatabasePath("$name-shm").delete() } catch (_: Exception) { }
        }
    }

    private fun name(): String = "generated_catalog_${System.nanoTime()}.db".also { names.add(it) }

    private fun record(
        id: String,
        hash: String = "hash",
        chatId: String? = "chat-id",
        chatName: String? = "Chat Name",
        source: GeneratedImageCatalogRecord.Source = GeneratedImageCatalogRecord.Source.GENERATED
    ) = GeneratedImageCatalogRecord(
        imageId = id,
        fileHash = hash,
        assetFileName = "$id.png",
        mimeType = "image/png",
        width = 100,
        height = 100,
        createdAt = 123L,
        originChatId = chatId,
        originChatName = chatName,
        originMessageId = id,
        locked = false,
        source = source
    )

    @Test
    fun createCloseReopenPreservesImmutableUuidAndMutableChatLabel() {
        val dbName = name()
        GeneratedImageCatalogStore.openForTest(context, dbName, key).use { store ->
            assertTrue(store.register(record("image-uuid")))
            assertTrue(store.renameOriginChat("chat-id", "Renamed Chat"))
        }
        GeneratedImageCatalogStore.openForTest(context, dbName, key).use { store ->
            val restored = store.lookup("image-uuid").record!!
            assertEquals("image-uuid", restored.imageId)
            assertEquals("image-uuid.png", restored.assetFileName)
            assertEquals("Renamed Chat", restored.originChatName)
            assertEquals("chat-id", restored.originChatId)
            var rekeyBlocked = false
            try {
                store.writableDatabase.execSQL(
                    "UPDATE generated_images SET image_id = 'replacement' WHERE image_id = 'image-uuid'"
                )
            } catch (_: Exception) {
                rekeyBlocked = true
            }
            assertTrue(rekeyBlocked)
            assertEquals("image-uuid", store.lookup("image-uuid").record!!.imageId)
            assertNull(store.integrityCheck())
        }
    }

    @Test
    fun equalHashesRemainSeparateRowsAndCopiedUuidIsIdempotent() {
        GeneratedImageCatalogStore.openForTest(context, name(), key).use { store ->
            assertTrue(store.register(record("image-a", hash = "identical")))
            assertTrue(store.register(record("image-b", hash = "identical")))
            assertTrue(store.register(record("image-a", hash = "identical")))
            assertEquals(2, store.allActive().size)
            assertEquals(2, store.activeAssetReferenceCount("image-a.png") + store.activeAssetReferenceCount("image-b.png"))
        }
    }

    @Test
    fun backfillConflictingChatReferencesClearOwnershipInsteadOfTransferringIt() {
        GeneratedImageCatalogStore.openForTest(context, name(), key).use { store ->
            val first = record(
                "legacy-id",
                chatId = "chat-a",
                chatName = "A",
                source = GeneratedImageCatalogRecord.Source.BACKFILL
            )
            val copied = first.copy(originChatId = "chat-b", originChatName = "B")
            assertTrue(store.upsertBackfill(first))
            assertTrue(store.upsertBackfill(copied))
            val restored = store.lookup("legacy-id").record!!
            assertNull(restored.originChatId)
            assertNull(restored.originChatName)
            assertNull(restored.originMessageId)
        }
    }

    @Test
    fun missingActiveRowBecomesNonActiveTombstone() {
        GeneratedImageCatalogStore.openForTest(context, name(), key).use { store ->
            assertTrue(store.register(record("missing-id")))
            assertTrue(store.tombstoneMissing("missing-id", "missing-id.png"))
            val lookup = store.lookup("missing-id")
            assertNull(lookup.record)
            assertTrue(lookup.tombstoned)
            assertFalse(store.hasActiveFileHash("hash"))
        }
    }

    @Test
    fun versionOneCatalogMigratesWithoutRekeyingExistingImage() {
        val dbName = name()
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, key, null, null)
        db.execSQL(
            "CREATE TABLE generated_images (" +
                "image_id TEXT PRIMARY KEY, file_hash TEXT NOT NULL, asset_file_name TEXT NOT NULL, " +
                "mime_type TEXT, width INTEGER, height INTEGER, created_at INTEGER NOT NULL, " +
                "origin_chat_id TEXT, origin_chat_name TEXT, locked INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "INSERT INTO generated_images " +
                "(image_id,file_hash,asset_file_name,created_at,origin_chat_id,origin_chat_name,locked) " +
                "VALUES ('old-uuid','old-hash','old-uuid.png',1,'chat','Old Name',0)"
        )
        db.version = 1
        db.close()

        GeneratedImageCatalogStore.openForTest(context, dbName, key).use { store ->
            val restored = store.lookup("old-uuid").record!!
            assertEquals("old-uuid", restored.imageId)
            assertEquals(GeneratedImageCatalogRecord.Source.BACKFILL, restored.source)
            assertTrue(store.renameOriginChat("chat", "New Name"))
            assertEquals("old-uuid", store.lookup("old-uuid").record!!.imageId)
        }
    }
}
