package org.teslasoft.assistant.preferences.sqlcipher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchStore
import org.teslasoft.assistant.preferences.chatsearch.SearchDocument
import org.teslasoft.assistant.preferences.chatsearch.SearchDocumentKind
import org.teslasoft.assistant.preferences.chatsearch.SearchTextPolicy
import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryStore

@RunWith(AndroidJUnit4::class)
class SqlCipherPostUpgradeInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun reopenAllPreUpgradeStoresAndVerifyEncryptedFts() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("phase8UpgradeStage") == "post"
        )
        assertTrue(android.os.Build.SUPPORTED_ABIS.first().startsWith("arm64"))
        System.loadLibrary("sqlcipher")

        fixtures().forEach(::verifyFixture)
        verifyEncryptedFts()
    }

    private fun verifyFixture(fixture: Fixture) {
        val original = context.getDatabasePath(fixture.databaseName)
        assertTrue(original.isFile)
        val key = DatabaseKeys.readExisting(context, fixture.keyName)
        assertNotNull(key)

        repeat(2) {
            SQLiteDatabase.openDatabase(
                original.path,
                key!!,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null
            ).use { db ->
                db.rawQuery("SELECT value FROM phase8_upgrade_fixture", emptyArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(fixture.token, cursor.getString(0))
                    assertEquals(1, cursor.count)
                }
                db.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).equals("ok", ignoreCase = true))
                }
            }
        }

        val wrongKeyCopy = copyForFailureTest(original, "wrong-key")
        assertOpenFails(wrongKeyCopy, "definitely-wrong-phase8-key".toByteArray())

        val corruptCopy = copyForFailureTest(original, "corrupt")
        val bytes = corruptCopy.readBytes()
        for (index in 0 until minOf(32, bytes.size)) bytes[index] = (bytes[index].toInt() xor 0x5a).toByte()
        corruptCopy.writeBytes(bytes)
        assertOpenFails(corruptCopy, key!!)

        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = context.getDatabasePath(fixture.databaseName + suffix)
            if (file.isFile) assertFalse(file.readBytes().containsSequence(fixture.token.toByteArray()))
        }
        assertFalse(wrongKeyCopy.readBytes().containsSequence(fixture.token.toByteArray()))
        assertFalse(corruptCopy.readBytes().containsSequence(fixture.token.toByteArray()))
        wrongKeyCopy.delete()
        corruptCopy.delete()
    }

    private fun verifyEncryptedFts() {
        val name = "phase8_chat_search_upgrade.db"
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
        val key = "phase8-search-key".toByteArray()
        val sentence = "phase8 encrypted fts fixture"
        ChatSearchStore.openForTest(context, name, key).use { store ->
            store.beginGeneration(1, Locale.US.toLanguageTag())
            store.insertDocuments(
                1,
                listOf(
                    SearchDocument(
                        chatId = "fixture-chat",
                        documentKey = "message:fixture-chat:one",
                        kind = SearchDocumentKind.MESSAGE,
                        rawText = sentence,
                        indexText = SearchTextPolicy.indexText(sentence, Locale.US),
                        contentFingerprint = SearchableMessageProjection.fingerprint(sentence),
                        sourceRevision = "fixture-revision",
                        chatTimestamp = 1,
                        messageTimestamp = 1,
                        messageId = "123e4567-e89b-12d3-a456-426614174000",
                        legacyOrdinal = null,
                        legacyRole = null
                    )
                )
            )
            store.activateGeneration(1, 0)
            assertEquals(1, store.candidates(1, "\"encrypted\"", 10, 0).size)
            assertTrue(store.verifyIntegrity())
        }
        ChatSearchStore.openForTest(context, name, key).use { store ->
            assertEquals(1, store.candidates(1, "\"fixture\"", 10, 0).size)
            assertTrue(store.verifyIntegrity())
        }
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = context.getDatabasePath(name + suffix)
            if (file.isFile) assertFalse(file.readBytes().containsSequence(sentence.toByteArray()))
            file.delete()
        }
    }

    private fun assertOpenFails(file: File, key: ByteArray) {
        var failed = false
        try {
            SQLiteDatabase.openDatabase(
                file.path,
                key,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null
            ).use { db ->
                db.rawQuery("PRAGMA integrity_check", emptyArray()).use { it.moveToFirst() }
            }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun copyForFailureTest(original: File, label: String): File =
        File(context.cacheDir, "phase8-${original.name}-$label.tmp").also {
            original.copyTo(it, overwrite = true)
        }

    private fun fixtures() = listOf(
        Fixture(MemoryStore.DATABASE_NAME, DatabaseKeys.KEY_MEMORY, "phase8-memory-fixture-v1"),
        Fixture("lorebook.db", DatabaseKeys.KEY_LOREBOOK, "phase8-lorebook-fixture-v1"),
        Fixture(
            GeneratedImageCatalogStore.DATABASE_NAME,
            DatabaseKeys.KEY_GENERATED_IMAGES,
            "phase8-generated-images-fixture-v1"
        )
    )

    private data class Fixture(val databaseName: String, val keyName: String, val token: String)

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { this[offset + it] == needle[it] }
        }
    }
}
