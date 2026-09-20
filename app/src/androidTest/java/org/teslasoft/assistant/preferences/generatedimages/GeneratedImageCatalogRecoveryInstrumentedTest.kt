package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import org.teslasoft.assistant.preferences.memory.DatabaseKeys

@RunWith(AndroidJUnit4::class)
class GeneratedImageCatalogRecoveryInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val createdFiles = ArrayList<File>()

    @Before
    fun resetCatalog() {
        GeneratedImageCatalogStore.invalidateInstance()
        listOf("", "-wal", "-shm", "-journal").forEach {
            context.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME + it).delete()
        }
        GeneratedImageCatalogHealth.clear(context)
    }

    @After
    fun cleanup() {
        GeneratedImageCatalogStore.invalidateInstance()
        createdFiles.forEach { it.delete() }
        GeneratedImageCatalogHealth.clear(context)
    }

    @Test
    fun absentCatalogWithGalleryOnlyUuidFilePreservesBytesAndNeedsRecovery() {
        val file = imageFile("png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(GeneratedImageCatalogHealth.missingDatabaseRequiresRecovery(context))
        assertEquals(
            GeneratedImageCatalogStorageState.NEEDS_RECOVERY,
            GeneratedImageCatalogReconciler.run(context)
        )
        assertTrue(file.exists())
    }

    @Test
    fun emptyCatalogNeverDeletesUnindexedUuidFile() {
        assertEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        )
        val file = imageFile("png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        assertEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogReconciler.run(context)
        )
        assertTrue(file.exists())
    }

    @Test
    fun backfillRecreatesReferencedRowButCatalogRemainsRecoveryGated() {
        val imageId = UUID.randomUUID().toString()
        val chatId = "chat-fixture"
        val messageId = UUID.randomUUID().toString()
        val hash = "fixture-hash"
        val file = File(imagesDir(), "$imageId.png").also(createdFiles::add)
        file.writeBytes(byteArrayOf(7, 8, 9))
        val metadata = GeneratedImageMetadata(
            imageId, hash, "image/png", 1, 1, "endpoint", "model", "fixture",
            null, 123L, GeneratedImageMetadata.STATUS_COMPLETE, null,
            assetFileName = file.name
        )
        val list = arrayListOf(hashMapOf("id" to chatId, "name" to "Fixture"))
        val history = arrayListOf(
            hashMapOf<String, Any>(
                "message" to "~file:$hash",
                "isBot" to true,
                GeneratedImageMetadata.KEY to metadata.toJson(),
                SearchableMessageProjection.MESSAGE_ID_KEY to messageId
            )
        )
        assertTrue(SecurePrefs.get(context, "chat_list").edit()
            .putString("data", Gson().toJson(list)).commit())
        assertTrue(SecurePrefs.get(context, "chat_$chatId").edit()
            .putString("chat", Gson().toJson(history)).commit())

        val outcome = GeneratedImageCatalogBackfill.run(context)
        assertEquals(GeneratedImageCatalogStorageState.NEEDS_RECOVERY, outcome.state)
        assertTrue(file.exists())

        GeneratedImageCatalogStore.invalidateInstance()
        val key = DatabaseKeys.readExisting(context, DatabaseKeys.KEY_GENERATED_IMAGES)
        assertNotNull(key)
        GeneratedImageCatalogStore.openForTest(
            context,
            GeneratedImageCatalogStore.DATABASE_NAME,
            key!!
        ).use { recovered ->
            val record = recovered.lookup(imageId).record
            assertNotNull(record)
            assertEquals(messageId, record!!.originMessageId)
        }
    }

    @Test
    fun onlyJournalNamedTemporaryFileIsRemoved() {
        assertEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        )
        val imageId = UUID.randomUUID().toString()
        val asset = "$imageId.png"
        val proven = File(imagesDir(), "$asset.catalogtmp").also(createdFiles::add)
        val unrelated = File(imagesDir(), "unrelated.catalogtmp").also(createdFiles::add)
        proven.writeBytes(byteArrayOf(1))
        unrelated.writeBytes(byteArrayOf(2))
        assertTrue(GeneratedImageRegistrationJournal.begin(context, imageId, asset))

        assertEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageRegistrationJournal.recover(context)
        )
        assertFalse(proven.exists())
        assertTrue(unrelated.exists())
    }

    /** 8.2: a catalog whose key is unavailable is LOCKED, not empty. Nothing
     *  may be deleted while the store cannot be read, and the rows must still
     *  be there once the key comes back. */
    @Test
    fun lockedCatalogDeletesNoImageAndLosesNoRow() {
        val imageId = provisionCatalogWithOneRegisteredImage()
        val file = File(imagesDir(), "$imageId.png")
        val databaseBefore = catalogBytes()
        val key = requireNotNull(DatabaseKeys.readExisting(context, DatabaseKeys.KEY_GENERATED_IMAGES))

        assertTrue(DatabaseKeys.clearExisting(context, DatabaseKeys.KEY_GENERATED_IMAGES))
        try {
            assertEquals(
                GeneratedImageCatalogStorageState.LOCKED,
                GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
            )
            GeneratedImageCatalogMaintenance.run(context)
            assertTrue(file.exists())
            assertArrayEquals(databaseBefore, catalogBytes())
        } finally {
            assertTrue(
                DatabaseKeys.replaceExisting(context, DatabaseKeys.KEY_GENERATED_IMAGES, key)
            )
            GeneratedImageCatalogStore.invalidateInstance()
        }

        assertEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        )
        assertNotNull(GeneratedImageCatalogStore.lookup(context, imageId).record)
    }

    /** 8.2: the wrong key must not be treated as an empty catalog. The store is
     *  never reopened as a fresh one over the old bytes, no image is deleted,
     *  and the original key still reads the original rows. */
    @Test
    fun wrongKeyCatalogDeletesNoImageAndNeverOverwritesTheStore() {
        val imageId = provisionCatalogWithOneRegisteredImage()
        val file = File(imagesDir(), "$imageId.png")
        val databaseBefore = catalogBytes()
        val key = requireNotNull(DatabaseKeys.readExisting(context, DatabaseKeys.KEY_GENERATED_IMAGES))
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(key.toList(), wrongKey.toList())

        assertTrue(
            DatabaseKeys.replaceExisting(context, DatabaseKeys.KEY_GENERATED_IMAGES, wrongKey)
        )
        try {
            assertNotEquals(
                GeneratedImageCatalogStorageState.AVAILABLE,
                GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
            )
            GeneratedImageCatalogMaintenance.run(context)
            assertTrue(file.exists())
            assertArrayEquals(databaseBefore, catalogBytes())
        } finally {
            assertTrue(
                DatabaseKeys.replaceExisting(context, DatabaseKeys.KEY_GENERATED_IMAGES, key)
            )
            GeneratedImageCatalogStore.invalidateInstance()
        }

        // The corrupt flag is a deliberately sticky signal owned by the health
        // record, not by these bytes. Clearing it here is what lets the test
        // assert the thing it is actually about: the store survived intact.
        GeneratedImageCatalogHealth.clear(context)
        GeneratedImageCatalogStore.invalidateInstance()
        assertEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        )
        assertNotNull(GeneratedImageCatalogStore.lookup(context, imageId).record)
    }

    /** 8.2: damaged catalog bytes are not deletion authority for anything. */
    @Test
    fun corruptCatalogDeletesNoImageAndIsNeverReplaced() {
        val imageId = provisionCatalogWithOneRegisteredImage()
        val file = File(imagesDir(), "$imageId.png")

        val databaseFile = context.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME)
        val damaged = databaseFile.readBytes()
        for (index in 0 until minOf(64, damaged.size)) {
            damaged[index] = (damaged[index].toInt() xor 0x5a).toByte()
        }
        databaseFile.writeBytes(damaged)

        assertNotEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        )
        GeneratedImageCatalogMaintenance.run(context)
        assertTrue(file.exists())
        assertArrayEquals(damaged, catalogBytes())
        assertNotEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogStore.lookup(context, imageId).state
        )
    }

    /** 8.2: recovery is idempotent on both sides of the catalog commit. A file
     *  whose row committed survives every later run; a file the journal proved
     *  this attempt created, with no row, is cleaned up once and then left
     *  alone. */
    @Test
    fun registrationRecoveryIsIdempotentAcrossProcessDeath() {
        val committedId = provisionCatalogWithOneRegisteredImage()
        val committed = File(imagesDir(), "$committedId.png")
        val committedTemp = File(imagesDir(), "$committedId.png.catalogtmp")
            .also(createdFiles::add)
        committedTemp.writeBytes(byteArrayOf(9))
        assertTrue(GeneratedImageRegistrationJournal.begin(context, committedId, committed.name))
        assertTrue(GeneratedImageRegistrationJournal.markFileReady(context, committedId, true))

        val abandonedId = UUID.randomUUID().toString()
        val abandoned = File(imagesDir(), "$abandonedId.png").also(createdFiles::add)
        abandoned.writeBytes(byteArrayOf(8))
        assertTrue(GeneratedImageRegistrationJournal.begin(context, abandonedId, abandoned.name))
        assertTrue(GeneratedImageRegistrationJournal.markFileReady(context, abandonedId, true))

        repeat(2) {
            assertEquals(
                GeneratedImageCatalogStorageState.AVAILABLE,
                GeneratedImageRegistrationJournal.recover(context)
            )
            assertTrue(committed.exists())
            assertFalse(committedTemp.exists())
            assertFalse(abandoned.exists())
            assertTrue(GeneratedImageRegistrationJournal.entriesForTest(context).isEmpty())
            assertNotNull(GeneratedImageCatalogStore.lookup(context, committedId).record)
        }
    }

    /** Creates the catalog before any UUID-named file exists, so the store is
     *  provisioned rather than immediately recovery-gated, then registers one
     *  committed image. Returns its image ID. */
    private fun provisionCatalogWithOneRegisteredImage(): String {
        assertEquals(
            GeneratedImageCatalogStorageState.AVAILABLE,
            GeneratedImageCatalogStore.ensureAvailableForRegistration(context)
        )
        val imageId = UUID.randomUUID().toString()
        val file = File(imagesDir(), "$imageId.png").also(createdFiles::add)
        file.writeBytes(byteArrayOf(1, 2, 3))
        val result = GeneratedImageCatalogStore.register(
            context,
            GeneratedImageCatalogRecord(
                imageId = imageId,
                fileHash = "fixture-hash-$imageId",
                assetFileName = file.name,
                mimeType = "image/png",
                width = 1,
                height = 1,
                createdAt = 123L,
                originChatId = "chat-fixture",
                originChatName = "Fixture",
                originMessageId = UUID.randomUUID().toString()
            )
        )
        assertTrue(result.success)
        // Close before any caller snapshots the file: an open store may still
        // hold the new row in its write-ahead log.
        GeneratedImageCatalogStore.invalidateInstance()
        return imageId
    }

    private fun catalogBytes(): ByteArray =
        context.getDatabasePath(GeneratedImageCatalogStore.DATABASE_NAME).readBytes()

    private fun imageFile(extension: String): File =
        File(imagesDir(), "${UUID.randomUUID()}.$extension").also(createdFiles::add)

    private fun imagesDir(): File = requireNotNull(context.getExternalFilesDir("images")).apply { mkdirs() }
}
