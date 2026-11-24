package org.teslasoft.assistant.conversation.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.chatstore.ChatStorageAuthority
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation

/**
 * Step 10 on a device (memory development plan G.12; G.5a rules 3, 4, 7, 9):
 * the real saved-chat reader over chats saved through the app's own save
 * path, and the whole-chat upkeep of the encrypted index store. Every G.12
 * event is also covered by `ConversationIndexCoordinatorTest` (JVM).
 */
@RunWith(AndroidJUnit4::class)
class ConversationIndexUpkeepInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = ChatPreferences.getChatPreferences()
    private val name = "conversation_index_upkeep_test_${System.nanoTime()}.db"
    private lateinit var store: ConversationIndexStore
    private val chatIds = ArrayList<String>()

    private val reader = SearchTextReader(
        vectorModel = "test-model-8",
        vectorDimensions = 8,
        hardLimit = 400,
        prefixFor = { "title: $it | text: " },
        countModelInput = { text -> text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2 }
    )

    private class Runner : IndexTaskRunner {
        val tasks = ArrayDeque<() -> Unit>()
        override fun run(delayMillis: Long, task: () -> Unit) { tasks.addLast(task) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst()() }
    }

    private class Vectors : PassageVectorReader {
        override val vectorModel = "test-model-8"
        override val vectorDimensions = 8
        override val tokenLimit = 400
        val inputs = ArrayList<String>()
        override fun countTokens(modelInput: String) = modelInput.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
        override fun read(modelInput: String): FloatArray {
            inputs.add(modelInput)
            return FloatArray(8) { (modelInput.hashCode() + it).toFloat() }
        }
    }

    private class Pause : EmbeddingPauseState { override var paused = false }

    private lateinit var source: SavedChatIndexSource
    private val runner = Runner()
    private lateinit var coordinator: ConversationIndexCoordinator

    @Before fun open() {
        PortableRestoreProcessGate.clear(context)
        ChatStorageAuthority.clearForTest(context)
        store = ConversationIndexStore.openForTest(context, name, "conversation-index-upkeep-key".toByteArray())
        source = SavedChatIndexSource(context)
        coordinator = ConversationIndexCoordinator(
            source = source,
            storage = { store },
            maintenance = { store },
            updaterFor = { ConversationIndexUpdater(store, reader, 40, ConversationIndexSettings.OVERLAP_WORDS) },
            requestVectors = {},
            runner = runner,
            changeWaitMillis = 0
        )
    }

    @After fun close() {
        store.close()
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
        chatIds.forEach { id ->
            SecurePrefs.get(context, "chat_$id").edit().clear().commit()
            SecurePrefs.get(context, "settings.$id").edit().clear().commit()
        }
    }

    private fun newChat() = UUID.randomUUID().toString().also(chatIds::add)

    private fun message(id: String?, text: String, bot: Boolean = false) = hashMapOf<String, Any>(
        "message" to text, "isBot" to bot
    ).apply {
        if (id != null) put(MessageIdentity.KEY, id)
        if (bot) put(MessageCompletionState.KEY_STATE, MessageCompletionState.DONE)
    }

    private fun stored(chatId: String) =
        ArrayList(prefs.getChatByIdResult(context, chatId, DamagedChatOperation.MEMORY).messages)

    /** What the save hookup does, without turning indexing on in the app. */
    private fun save(chatId: String, messages: ArrayList<HashMap<String, Any>>, synchronous: Boolean = true) {
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, messages, synchronous = synchronous))
        ConversationIndexChatChanges.changed(chatId)
        coordinator.chatChanged(chatId)
    }

    private fun long(word: String) = (1..120).joinToString(" ") { "$word$it" }

    @Test fun theIndexFollowsEverySaveThroughTheRealChatStorage() {
        val chatId = newChat()
        val ids = List(4) { UUID.randomUUID().toString() }
        save(chatId, arrayListOf(
            message(ids[0], "I moved to Leeds."), message(ids[1], long("leeds"), bot = true),
            message(ids[2], "Tell me about bicycles."), message(ids[3], "Bicycles are fun.", bot = true)
        ))
        runner.runAll()
        assertEquals(ids.toSet(), store.indexedMessages(chatId).keys)
        val firstVersion = store.indexState(chatId)!!.describedVersion

        // Hidden from the AI: a save that does not wait for the disk.
        val hidden = stored(chatId).apply { this[3][MessageVisibility.KEY] = true }
        save(chatId, hidden, synchronous = false)
        runner.runAll()
        assertEquals(ids.take(3).toSet(), store.indexedMessages(chatId).keys)
        assertNotEquals(firstVersion, store.indexState(chatId)!!.describedVersion)
        assertNull(source.currentMessage(chatId, ids[3])?.takeIf(IndexEligibility::isEligible))

        // Shown again, then edited through the app's edit path.
        save(chatId, stored(chatId).apply { this[3].remove(MessageVisibility.KEY) })
        prefs.editMessage(context, chatId, 2, "Tell me about trains.")
        ConversationIndexChatChanges.changed(chatId)
        coordinator.chatChanged(chatId)
        runner.runAll()
        assertEquals(ids.toSet(), store.indexedMessages(chatId).keys)
        assertEquals(IndexFingerprint.of("Tell me about trains."), store.indexedMessages(chatId).getValue(ids[2]).textFingerprint)

        // Deleted through the app's delete path.
        prefs.deleteMessage(context, chatId, 1)
        ConversationIndexChatChanges.changed(chatId)
        coordinator.chatChanged(chatId)
        runner.runAll()
        assertEquals(setOf(ids[0], ids[2], ids[3]), store.indexedMessages(chatId).keys)
        assertNull(source.currentMessage(chatId, ids[1]))

        // The vector worker reads each message as stored now through the same reader.
        val vectors = Vectors()
        val outcome = PassageEmbeddingWorker(store, source, reader, vectors, Pause()).runPass()
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Finished)
        assertTrue(store.chatProgress(chatId).fullyIndexed)
        assertTrue(vectors.inputs.any { it.endsWith("Tell me about trains.") })
        assertFalse(vectors.inputs.any { it.contains("leeds1 ") })
    }

    @Test fun everySaveChangesTheChatVersionAndReadingChangesNothing() {
        val chatId = newChat()
        val messages = arrayListOf(message(UUID.randomUUID().toString(), "One."))
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, messages, synchronous = true))
        val before = source.chatVersion(chatId)
        val storedBefore = stored(chatId)
        source.read(chatId)
        assertEquals(before, source.chatVersion(chatId))
        assertEquals(storedBefore, stored(chatId))
        ConversationIndexChatChanges.changed(chatId)
        assertNotEquals(before, source.chatVersion(chatId))
    }

    @Test fun aConfirmedDeletionWhoseSaveHasNotLandedIsNotReadUntilItIs() {
        val chatId = newChat()
        val ids = List(2) { UUID.randomUUID().toString() }
        save(chatId, arrayListOf(message(ids[0], "Keep this."), message(ids[1], "Delete this.", bot = true)))
        runner.runAll()
        val order = org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.shared
        try {
            // A message deletion the chat screen has confirmed, whose save is refused for now.
            order.saveInBackground(
                chatId, order.claim(chatId), arrayListOf(message(ids[0], "Keep this.")), false
            ) { _, _, _ -> false }
            assertEquals(IndexChatSource.Read.Unavailable, source.read(chatId))
            coordinator.chatChanged(chatId)
            runner.runAll()
            assertEquals("the index is left as it was", ids.toSet(), store.indexedMessages(chatId).keys)
        } finally {
            order.discard(listOf(chatId))
        }
        // When the deletion is saved, its save reports the change and the index follows.
        save(chatId, arrayListOf(message(ids[0], "Keep this.")))
        runner.runAll()
        assertEquals(setOf(ids[0]), store.indexedMessages(chatId).keys)
    }

    @Test fun aChatWhoseMessagesHaveNoPermanentIdsIsNotIndexedAndNotChanged() {
        val chatId = newChat()
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, chatId, arrayListOf(message(null, "Saved before permanent IDs.")), synchronous = true))
        val before = stored(chatId)
        assertEquals(IndexChatSource.Read.WithoutPermanentIds, source.read(chatId))
        coordinator.chatChanged(chatId)
        runner.runAll()
        assertTrue(store.indexedMessages(chatId).isEmpty())
        assertNull(store.indexState(chatId))
        assertEquals(before, stored(chatId))
    }

    @Test fun deletingAChatRemovesOnlyVectorsNoOtherChatUses() {
        val first = newChat()
        val second = newChat()
        save(first, arrayListOf(message(UUID.randomUUID().toString(), "Shared words."), message(UUID.randomUUID().toString(), long("own"), bot = true)))
        save(second, arrayListOf(message(UUID.randomUUID().toString(), "Shared words.")))
        runner.runAll()
        PassageEmbeddingWorker(store, source, reader, Vectors(), Pause()).runPass()
        val sharedKey = IndexFingerprint.of(reader.modelInput(SpeakerTitlePolicy.HUMAN, "Shared words."))
        assertEquals(ConversationIndexStore.STATE_MADE, store.vectorState(sharedKey, "test-model-8", 8))
        val own = PassageSearchText.build(long("own"), SpeakerTitlePolicy.COMPANION, reader, 40, ConversationIndexSettings.OVERLAP_WORDS)
            .passages.map { it.searchFingerprint }
        own.forEach { assertEquals(ConversationIndexStore.STATE_MADE, store.vectorState(it, "test-model-8", 8)) }

        coordinator.chatsDeleted(listOf(first))
        runner.runAll()
        assertTrue(store.indexedMessages(first).isEmpty())
        assertNull(store.indexState(first))
        assertFalse(first in store.indexedChatIds())
        own.forEach { assertNull(store.vectorState(it, "test-model-8", 8)) }
        assertEquals(ConversationIndexStore.STATE_MADE, store.vectorState(sharedKey, "test-model-8", 8))
    }

    @Test fun aRenameMovesTheEntriesToTheNewIdInOneStep() {
        val oldId = newChat()
        val newId = newChat()
        val messages = arrayListOf(message(UUID.randomUUID().toString(), "Hello."), message(UUID.randomUUID().toString(), "Hi there.", bot = true))
        save(oldId, messages)
        runner.runAll()
        val stamps = store.indexedMessages(oldId)

        // The older-chat rename path stores the same history under the new ID.
        assertEquals(ChatStorageHealth.WriteOutcome.OK, prefs.saveChatHistory(context, newId, stored(oldId), synchronous = true))
        SecurePrefs.get(context, "chat_$oldId").edit().clear().commit()
        coordinator.chatMoved(oldId, newId)
        runner.runAll()

        assertTrue(store.indexedMessages(oldId).isEmpty())
        assertNull(store.indexState(oldId))
        assertEquals(stamps, store.indexedMessages(newId))
        assertFalse(store.indexState(newId)!!.needsRefresh)
    }

    @Test fun aRestoreMarksEveryChatForRefreshAndDeletingTheIndexRemovesEverything() {
        val chatId = newChat()
        save(chatId, arrayListOf(message(UUID.randomUUID().toString(), "Hello.")))
        runner.runAll()
        store.markAllForRefresh()
        assertTrue(store.indexState(chatId)!!.needsRefresh)

        store.deleteEverything()
        assertTrue(store.indexedChatIds().isEmpty())
        assertTrue(store.indexedMessages(chatId).isEmpty())
        assertNull(store.vectorState(IndexFingerprint.of(reader.modelInput(SpeakerTitlePolicy.HUMAN, "Hello.")), "test-model-8", 8))
        assertEquals("the chat is untouched", "Hello.", stored(chatId).single()["message"])
    }
}
