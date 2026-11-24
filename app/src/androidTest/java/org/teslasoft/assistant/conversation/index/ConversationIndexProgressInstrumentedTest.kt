package org.teslasoft.assistant.conversation.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException

/**
 * Step 12 on a device (G.16a; Owner, 2026-11-24): the encrypted store's
 * per-message progress. A message is finished only when every one of its
 * passages has its vector made or failed for good; a waiting passage keeps it
 * unfinished; passages cut for another model are not counted. The numbers
 * built from it match the settings status's definition of indexed.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIndexProgressInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "conversation_index_progress_test_${System.nanoTime()}.db"
    private val keyName = "conversation_index_progress_test_key_${System.nanoTime()}"
    private lateinit var store: ConversationIndexStore
    private val chats = HashMap<String, Map<String, IndexSourceMessage>>()
    private val versions = HashMap<String, Int>()
    private val source = IndexMessageSource { chat, message -> chats[chat]?.get(message) }
    private val pause = object : EmbeddingPauseState { override var paused = false }

    private fun reader(model: String) = SearchTextReader(
        vectorModel = model, vectorDimensions = DIMENSIONS, hardLimit = 400,
        prefixFor = { "title: $it | text: " }, countModelInput = ::words
    )

    private inner class Vectors(override val tokenLimit: Int = 400, private val afterRead: (Int) -> Unit = {}) :
        PassageVectorReader {
        override val vectorModel = MODEL
        override val vectorDimensions = DIMENSIONS
        var reads = 0
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            if (countTokens(modelInput) > tokenLimit) throw InputTooLongException(countTokens(modelInput), tokenLimit)
            reads++
            afterRead(reads)
            return FloatArray(DIMENSIONS) { it + reads.toFloat() }
        }
    }

    @Before fun setUp() {
        val key = DatabaseKeys.getOrCreate(context, keyName, false) ?: error("test key unavailable")
        store = ConversationIndexStore.openForTest(context, name, key)
    }

    @After fun tearDown() {
        try { store.close() } catch (_: Exception) { }
        for (suffix in listOf("", "-wal", "-shm", "-journal")) context.getDatabasePath(name + suffix).delete()
        DatabaseKeys.clearExisting(context, keyName)
        pause.paused = false
    }

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String) =
        IndexSourceMessage(id, true, text, false, MessageCompletionState.DONE, 200L, null)
    private fun longReply(id: String) = reply(id, (1..120).joinToString(" ") { "wide$it" })

    private fun save(chatId: String, messages: List<IndexSourceMessage>, model: String = MODEL): String {
        chats[chatId] = messages.associateBy { it.messageId }
        versions[chatId] = (versions[chatId] ?: 0) + 1
        val updater = ConversationIndexUpdater(store, reader(model), 40, 5)
        val described = ConversationIndexCoordinator.describedVersion(versions[chatId].toString(), updater.settingsKey)
        val outcome = updater.update(chatId, described, messages, {
            ConversationIndexCoordinator.describedVersion(versions[chatId].toString(), updater.settingsKey)
        })
        assertTrue("$outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
        return updater.settingsKey
    }

    private fun pass(vectors: Vectors = Vectors()) =
        PassageEmbeddingWorker(store, source, reader(MODEL), vectors, pause).runPass()

    private fun progress() = store.messageProgress(MODEL, DIMENSIONS)

    @Test fun aMessageIsFinishedOnlyWhenEveryPassageIsMadeOrFailed() {
        save("chat", listOf(user("u1", "short"), longReply("a1"), user("u2", "also short")))
        assertTrue("the long reply has several passages", store.chatProgress("chat").passages > 3)
        assertEquals(MessageProgress(messages = 3, finished = 0), progress()["chat"])

        // Every passage of the long reply is too long for the model: they fail, and still count as finished.
        val outcome = pass(Vectors(tokenLimit = 20))
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Finished)
        assertTrue(store.chatProgress("chat").failed > 0)
        assertEquals(MessageProgress(messages = 3, finished = 3), progress()["chat"])
        assertFalse(store.chatProgress("chat").fullyIndexed)
    }

    @Test fun aMessageWithOneWaitingPassageIsNotFinished() {
        save("done", listOf(user("u1", "short")))
        assertTrue(pass() is PassageEmbeddingWorker.Outcome.Finished)
        // Only the long reply waits now; pause after its first passage.
        save("long", listOf(longReply("a1")))
        val outcome = pass(Vectors { reads -> if (reads == 1) pause.paused = true })
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Paused)
        assertEquals(1, store.chatProgress("long").made)
        assertTrue(store.chatProgress("long").waiting > 0)
        assertEquals(MessageProgress(messages = 1, finished = 0), progress()["long"])
        assertEquals(MessageProgress(messages = 1, finished = 1), progress()["done"])
    }

    @Test fun passagesCutForAnotherModelAreNotCounted() {
        save("other", listOf(user("u1", "short")), model = OTHER_MODEL)
        save("mine", listOf(user("u1", "short")))
        assertEquals(setOf("mine"), progress().keys)
    }

    @Test fun theNumbersFromTheEncryptedStoreMatchTheSettingsStatus() {
        save("indexed", listOf(user("u1", "short"), reply("a1", "fine")))
        save("failed", listOf(user("u1", "short"), longReply("a1")))
        val key = save("waiting", listOf(user("u1", "later")))
        // Paused after the first vector read: other vectors are still waiting.
        assertTrue(pass(Vectors(tokenLimit = 20) { reads -> if (reads == 1) pause.paused = true })
            is PassageEmbeddingWorker.Outcome.Paused)
        val live = listOf("indexed", "failed", "waiting")
        val summaries = store.chatSummaries(MODEL, DIMENSIONS)
        val numbers = IndexingProgressMath.numbers(
            live, summaries, progress(), emptyMap(), { versions[it].toString() }, key
        )
        val messages = progress().values
        val expectedPercent = messages.sumOf { it.finished } * 100 / messages.sumOf { it.messages }
        assertEquals(5, messages.sumOf { it.messages })
        assertEquals(expectedPercent, numbers.percent)
        assertEquals(3, numbers.totalChats)
        assertEquals(
            ConversationIndexCounting.count(live, summaries, { versions[it].toString() }, key).indexed,
            numbers.indexedChats
        )
    }

    private companion object {
        const val MODEL = "progress-device-model"
        const val OTHER_MODEL = "progress-device-other"
        const val DIMENSIONS = 4
        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
