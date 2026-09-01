package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun imageFile(extension: String): File =
        File(imagesDir(), "${UUID.randomUUID()}.$extension").also(createdFiles::add)

    private fun imagesDir(): File = requireNotNull(context.getExternalFilesDir("images")).apply { mkdirs() }
}
