package org.teslasoft.assistant.preferences.chatsearch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSearchStoreInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val names = ArrayList<String>()
    private val key = "chat-search-test-key".toByteArray()

    @After fun cleanup() {
        names.forEach { name -> listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() } }
    }

    @Test fun encryptedFtsSupportsExactAndSingleAndMultiCharacterPrefixes() {
        val name = "chat_search_${System.nanoTime()}.db".also(names::add)
        ChatSearchStore.openForTest(context, name, key).use { store ->
            store.beginGeneration(1, Locale.US.toLanguageTag())
            store.insertDocuments(1, listOf(document("Searchable sentence")))
            store.activateGeneration(1, 0)
            assertEquals(1, store.candidates(1, "\"s\"*", 10, 0).size)
            assertEquals(1, store.candidates(1, "\"sea\"*", 10, 0).size)
            assertEquals(1, store.candidates(1, "\"searchable\"", 10, 0).size)
            assertTrue(store.verifyIntegrity())
            store.rebuildFullTextIndex()
            assertEquals(1, store.candidates(1, "\"search\"*", 10, 0).size)
        }
        listOf("", "-wal", "-shm").forEach { suffix ->
            val file = context.getDatabasePath(name + suffix)
            if (file.exists()) {
                assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Searchable sentence"))
            }
        }
        ChatSearchStore.openForTest(context, name, key).use {
            assertEquals(1, it.candidates(1, "\"search\"*", 10, 0).size)
        }
    }

    @Test fun activeGenerationSwitchAndExternalContentDeleteRemainConsistent() {
        val name = "chat_search_${System.nanoTime()}.db".also(names::add)
        ChatSearchStore.openForTest(context, name, key).use { store ->
            store.beginGeneration(1, Locale.US.toLanguageTag())
            store.insertDocuments(1, listOf(document("First corpus")))
            store.activateGeneration(1, 0)
            store.beginGeneration(2, Locale.US.toLanguageTag())
            store.insertDocuments(2, listOf(document("Second corpus").copy(documentKey = "message:chat:two")))
            assertEquals(1, store.candidates(1, "\"first\"", 10, 0).size)
            store.activateGeneration(2, 0)
            assertEquals(0, store.candidates(2, "\"first\"", 10, 0).size)
            assertEquals(1, store.candidates(2, "\"second\"", 10, 0).size)
            store.deleteChat(2, "chat")
            assertEquals(0, store.candidates(2, "\"second\"", 10, 0).size)
            assertTrue(store.verifyIntegrity())
        }
    }

    @Test fun wrongKeyCannotOpenEncryptedSearchDatabase() {
        val name = "chat_search_${System.nanoTime()}.db".also(names::add)
        ChatSearchStore.openForTest(context, name, key).use { it.writableDatabase }
        var failed = false
        try {
            ChatSearchStore.openForTest(context, name, "wrong-key".toByteArray()).use {
                it.readableDatabase.rawQuery("SELECT count(*) FROM search_meta", emptyArray()).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun document(text: String) = SearchDocument(
        chatId = "chat", documentKey = "message:chat:one", kind = SearchDocumentKind.MESSAGE,
        rawText = text, indexText = SearchTextPolicy.indexText(text, Locale.US),
        contentFingerprint = SearchableMessageProjection.fingerprint(text), sourceRevision = null,
        chatTimestamp = 1, messageTimestamp = 1, messageId = "123e4567-e89b-12d3-a456-426614174000",
        legacyOrdinal = null, legacyRole = null
    )
}
