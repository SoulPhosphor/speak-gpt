package org.teslasoft.assistant.conversation.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.COMPANION
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.HUMAN
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.HfTokenizer
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope

/**
 * The conversation index's encrypted stores on a device (memory development
 * plan G.5, G.5a rules 1, 3, 4 and 7, G.5b "With the search text, in Step 8",
 * G.7). The search-text tests use the real EmbeddingGemma tokenizer that
 * Android Checks installs in `files/step2-model`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIndexStoreInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "conversation_index_test_${System.nanoTime()}.db"
    private lateinit var store: ConversationIndexStore
    private var version = "v1"

    private val reader = SearchTextReader(
        vectorModel = "test-model-8",
        vectorDimensions = 8,
        hardLimit = 400,
        prefixFor = { "title: $it | text: " },
        countModelInput = { text -> text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2 }
    )

    @Before fun open() {
        store = ConversationIndexStore.openForTest(context, name, "conversation-index-test-key".toByteArray())
    }

    @After fun close() {
        store.close()
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
    }

    private fun updater(target: Int = 40) = ConversationIndexUpdater(store, reader, target, 5)

    private fun user(id: String, text: String, scope: ScanMessageScope? = null) =
        IndexSourceMessage(id, false, text, false, null, 100L, scope)

    private fun reply(id: String, text: String, state: String? = MessageCompletionState.DONE, hidden: Boolean = false) =
        IndexSourceMessage(id, true, text, hidden, state, 200L, null)

    private val secret = "Meet me under the old clock tower at midnight"

    private fun chat() = listOf(
        user("u1", "$secret, I moved to Leeds.", ScanMessageScope(companionPersonaId = "persona-1", userPersonaId = "glamour-3", projectId = "project-9")),
        reply("a1", (1..150).joinToString(" ") { "harbour$it" }),
        user("u2", "Tell me about bicycles."),
        reply("a2", "Bicycles are fun.")
    )

    private fun update(messages: List<IndexSourceMessage>, recorded: String = version) =
        updater().update("chat", recorded, messages, { version })

    @Test fun storesPassagesWordsStateAndWaitingVectorsWithoutReadableText() {
        assertEquals(ConversationIndexUpdater.Outcome.Updated(4, 0, 0, 0), update(chat()))
        val stamps = store.indexedMessages("chat")
        assertEquals(setOf("u1", "a1", "u2", "a2"), stamps.keys)
        assertEquals(ScanMessageScope(companionPersonaId = "persona-1", userPersonaId = "glamour-3", projectId = "project-9"), stamps.getValue("u1").scope)
        assertNull("unknown scope stays unknown", stamps.getValue("u2").scope)
        // A recorded scope without a Glamour stores no Glamour reference but stays known.
        update(chat().toMutableList().also { it[2] = user("u2", "Tell me about bicycles.", ScanMessageScope(projectId = "project-9")) }, recorded = "v1")
        assertEquals(ScanMessageScope(projectId = "project-9"), store.indexedMessages("chat").getValue("u2").scope)
        assertNull(store.indexedMessages("chat").getValue("u2").scope!!.userPersonaId)
        assertEquals("u1", stamps.getValue("a1").exchangeId)

        val db = store.readableDatabase
        val passages = db.rawQuery("SELECT COUNT(*) FROM passages WHERE chat_id = 'chat'", null)
            .use { it.moveToFirst(); it.getInt(0) }
        assertTrue(passages > 4)
        val state = store.indexState("chat")!!
        assertEquals("v1", state.describedVersion)
        assertFalse(state.needsRefresh)
        assertEquals(passages, state.waitingVectors)
        assertFalse(state.fullyIndexed)
        db.rawQuery("SELECT state, vector FROM passage_vectors", null).use { cursor ->
            while (cursor.moveToNext()) {
                assertEquals("waiting", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }

        // Words can be searched, but the word index keeps no content.
        assertEquals(1, store.passagesWithWords("chat", listOf("clock", "tower")).size)
        assertTrue(store.passagesWithWords("chat", listOf("clock", "bicycle")).isEmpty())
        db.rawQuery("SELECT words FROM passage_keywords", null).use { cursor ->
            while (cursor.moveToNext()) assertTrue(cursor.isNull(0))
        }
        // No value anywhere in the database holds the message's words in readable order.
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
            .use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
        for (table in tables) {
            if (table == "passage_keywords") continue
            db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) {
                        val value = when (cursor.getType(column)) {
                            android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            android.database.Cursor.FIELD_TYPE_BLOB -> String(cursor.getBlob(column), Charsets.UTF_8)
                            else -> ""
                        }
                        for (phrase in listOf("clock tower", "old clock", "moved to Leeds", "Bicycles are fun", "harbour1 harbour2")) {
                            assertFalse("$table holds '$phrase'", value.contains(phrase, ignoreCase = true))
                        }
                    }
                }
            }
        }
    }

    @Test fun editsDeletionsHidingAndUnfinishedRepliesReplaceOnlyWhatChanged() {
        update(chat())
        val db = store.readableDatabase
        fun rowIds(message: String) = db.rawQuery(
            "SELECT row_id FROM passages WHERE chat_id = 'chat' AND message_id = ? ORDER BY passage_order", arrayOf(message)
        ).use { buildList { while (it.moveToNext()) add(it.getLong(0)) } }
        val untouched = rowIds("a1")
        val oldA2 = db.rawQuery("SELECT search_fingerprint FROM passages WHERE message_id = 'a2'", null)
            .use { it.moveToFirst(); it.getString(0) }

        version = "v2"
        val edited = chat().toMutableList().also { it[3] = reply("a2", "Bicycles need oiling.") }
        assertEquals(ConversationIndexUpdater.Outcome.Updated(1, 0, 0, 3), update(edited))
        assertEquals("an unchanged message keeps its rows", untouched, rowIds("a1"))
        assertNull("an unused vector is removed", store.vectorState(oldA2, "test-model-8", 8))
        assertTrue(store.passagesWithWords("chat", listOf("oil")).isNotEmpty())
        assertTrue(store.passagesWithWords("chat", listOf("fun")).isEmpty())

        version = "v3"
        val changed = listOf(
            edited[0],
            reply("a1", edited[1].modelText, hidden = true),
            edited[2],
            reply("a2", "Bicycles need oiling.", state = MessageCompletionState.STREAMING),
            reply("a3", "This reply failed.", state = MessageCompletionState.FAILED)
        )
        assertEquals(ConversationIndexUpdater.Outcome.Updated(0, 0, 2, 2), update(changed))
        assertEquals(setOf("u1", "u2"), store.indexedMessages("chat").keys)
        assertTrue("a hidden message's words are gone", store.passagesWithWords("chat", listOf("harbour1")).isEmpty())
        val remaining = db.rawQuery("SELECT COUNT(*) FROM passage_vectors", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(store.indexState("chat")!!.waitingVectors, remaining)

        version = "v4"
        // u2 moves to the top: it is restamped, not cut again.
        assertEquals(ConversationIndexUpdater.Outcome.Updated(0, 1, 1, 0), update(listOf(changed[2])))
        assertEquals(0, store.indexedMessages("chat").getValue("u2").chatPosition)
        assertEquals(setOf("u2"), store.indexedMessages("chat").keys)
    }

    @Test fun aSharedSearchTextKeepsItsVectorUntilTheLastPassageUsingItGoes() {
        val same = "Bicycles are fun."
        update(listOf(user("u1", same), user("u2", same)))
        val fingerprint = store.readableDatabase.rawQuery("SELECT search_fingerprint FROM passages WHERE message_id = 'u1'", null)
            .use { it.moveToFirst(); it.getString(0) }
        version = "v2"
        update(listOf(user("u2", same)))
        assertEquals("waiting", store.vectorState(fingerprint, "test-model-8", 8))
        version = "v3"
        update(emptyList())
        assertNull(store.vectorState(fingerprint, "test-model-8", 8))
        assertTrue(store.indexState("chat")!!.fullyIndexed)
    }

    @Test fun anUpdateInterruptedInsideAMessageLeavesItsPreviousRows() {
        update(chat())
        val before = store.indexedMessages("chat")
        val words = store.passagesWithWords("chat", listOf("clock"))
        version = "v2"
        store.afterOldRowsRemoved = { throw IllegalStateException("power lost") }
        val edited = chat().toMutableList().also { it[0] = user("u1", "Entirely new words about gardens.") }
        val outcome = update(edited)
        assertTrue(outcome is ConversationIndexUpdater.Outcome.Failed)
        store.afterOldRowsRemoved = null
        assertEquals(before, store.indexedMessages("chat"))
        assertEquals(words, store.passagesWithWords("chat", listOf("clock")))
        assertTrue(store.passagesWithWords("chat", listOf("garden")).isEmpty())
        val state = store.indexState("chat")!!
        assertTrue(state.needsRefresh)
        assertEquals("v1", state.describedVersion)

        assertTrue(update(edited) is ConversationIndexUpdater.Outcome.Updated)
        assertTrue(store.passagesWithWords("chat", listOf("garden")).isNotEmpty())
        assertFalse(store.indexState("chat")!!.needsRefresh)
    }

    @Test fun aResultThatFinishesAfterTheChatChangedIsThrownAway() {
        update(chat())
        val before = store.indexedMessages("chat")
        version = "v2"
        val edited = chat().map { if (it.isBot) it.copy(modelText = it.modelText + " Edited.") else it }
        // The chat is saved again while the first changed message is being cut.
        val slowReader = SearchTextReader("test-model-8", 8, 400, { "title: $it | text: " }) { text ->
            version = "v3"
            text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
        }
        val outcome = ConversationIndexUpdater(store, slowReader, 40, 5).update("chat", "v2", edited, { version })
        assertEquals(ConversationIndexUpdater.Outcome.Stale, outcome)
        assertEquals(before, store.indexedMessages("chat"))
        assertTrue(store.indexState("chat")!!.needsRefresh)

        // A direct write against an outdated expectation is refused too.
        val stamp = before.getValue("a2")
        assertEquals(IndexWrite.STALE, store.writeMessage("chat", "a2", stamp.copy(chatPosition = 99), null) { true })
        assertEquals(IndexWrite.STALE, store.writeMessage("chat", "a2", stamp, null) { false })
        assertEquals(before, store.indexedMessages("chat"))
    }

    @Test fun aChatChangeWhileAWriteIsInProgressRollsTheWriteBack() {
        update(chat())
        val before = store.indexedMessages("chat")
        version = "v2"
        // The chat is saved again after the old rows are removed and before the commit.
        store.afterOldRowsRemoved = { version = "v3" }
        val edited = chat().toMutableList().also { it[0] = user("u1", "Entirely new words about gardens.") }
        assertEquals(ConversationIndexUpdater.Outcome.Stale, update(edited, recorded = "v2"))
        assertEquals(before, store.indexedMessages("chat"))
        assertTrue(store.passagesWithWords("chat", listOf("garden")).isEmpty())

        // The same holds for a moved message that is only restamped.
        version = "v4"
        store.afterOldRowsRemoved = { version = "v5" }
        assertEquals(ConversationIndexUpdater.Outcome.Stale, update(chat().drop(2), recorded = "v4"))
        store.afterOldRowsRemoved = null
        assertEquals(before, store.indexedMessages("chat"))
        assertTrue(store.indexState("chat")!!.needsRefresh)
        assertEquals("v1", store.indexState("chat")!!.describedVersion)
    }

    @Test fun wordsWithApostrophesAreSearchableWithoutPhraseQueries() {
        update(listOf(user("u1", "Mr O'Brien and Siobhán's cousin visited Leeds.")))
        val terms = org.teslasoft.assistant.preferences.memory.librarian.KeywordText.terms("O'Brien Siobhán's")
        assertEquals(listOf("o'brien", "siobhán"), terms)
        assertEquals(1, store.passagesWithWords("chat", terms).size)
        assertEquals(1, store.passagesWithWords("chat", listOf("o'brien", "leed")).size)
        assertTrue(store.passagesWithWords("chat", listOf("brien")).isEmpty())
    }

    @Test fun realTokenizerSearchTextsFitTheModelAndHoldOnlyTheirOwnMessage() {
        val tokenizerFile = File(context.filesDir, "step2-model/tokenizer.json")
        assertTrue("Missing CI tokenizer fixture $tokenizerFile", tokenizerFile.isFile && tokenizerFile.canRead())
        val tokenizer = HfTokenizer.load(tokenizerFile)
        val model = EmbeddingModels.GEMMA_Q4
        val real = SearchTextReader.forEmbeddingModel(model, tokenizer)
        assertEquals(model.storedTextMaxTokens, real.hardLimit)
        assertEquals("title: Human | text: hello", real.modelInput(HUMAN, "hello"))

        val messages = mapOf(
            "ordinary" to (1..120).joinToString("\n\n") { "Paragraph $it: we packed box $it before moving to Leeds." },
            "no spaces" to "https://example.invalid/" + "x7".repeat(400),
            "Chinese" to "我们今天去公园散步然后在湖边吃午饭天气很好".repeat(40),
            "emoji" to "👨‍👩‍👧‍👦 family time 🎉 ".repeat(300),
            "code" to (1..400).joinToString("\n") { "fun item$it(value: Int) = value * $it" }
        )
        for ((target, overlap) in listOf(256 to 40, 2_000 to 2_000, 64 to 10)) {
            for ((name, text) in messages) {
                val built = PassageSearchText.build(text, COMPANION, real, target, overlap).passages
                if (target == 64) assertTrue("$name is cut into several passages", built.size > 1)
                assertEquals(text, built.joinToString("") { text.substring(it.start, it.end) })
                for ((index, passage) in built.withIndex()) {
                    val measured = tokenizer.countTokens(passage.modelInput)
                    assertTrue("$name/$target: $measured tokens", measured <= model.storedTextMaxTokens)
                    assertEquals(measured, passage.searchTokens)
                    assertEquals(model.documentPrefix.replaceFirst("title: none", "title: Companion") + passage.searchText, passage.modelInput)
                    assertEquals(text.substring(passage.searchStart, passage.end), passage.searchText)
                    if (index > 0) assertTrue(passage.searchStart >= built[index - 1].start)
                }
            }
        }
    }
}
