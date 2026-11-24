package org.teslasoft.assistant.conversation.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.COMPANION
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.HUMAN
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope

/**
 * Memory development plan G.5a rules 1, 3, 4 and 7 for the updater's
 * decisions, against an in-memory storage that keeps the same transaction
 * contract as the encrypted store. The encrypted store itself, and these
 * cases end to end, are covered by `ConversationIndexStoreInstrumentedTest`.
 */
class ConversationIndexUpdaterTest {

    /** Each write applies completely or not at all, after checking the chat version and expected stamp. */
    private class MemoryStorage : ConversationIndexStorage {
        val messages = HashMap<String, HashMap<String, MessageEntries>>()
        val vectors = HashMap<Triple<String, String, Int>, String>()
        val states = HashMap<String, ChatIndexState>()
        var writes = 0
        var failOnWrite: Int? = null
        var beforeWrite: (() -> Unit)? = null

        override fun indexedMessages(chatId: String): Map<String, MessageStamp> =
            messages[chatId].orEmpty().mapValues { it.value.stamp }

        override fun indexState(chatId: String) = states[chatId]

        override fun beginUpdate(chatId: String) {
            states[chatId] = (states[chatId] ?: ChatIndexState(null, true, 0, false)).copy(needsRefresh = true, fullyIndexed = false)
        }

        override fun writeMessage(
            chatId: String, messageId: String, expected: MessageStamp?, replacement: MessageEntries?,
            chatUnchanged: () -> Boolean
        ): IndexWrite {
            beforeWrite?.invoke()
            if (++writes == failOnWrite) throw IllegalStateException("disk full")
            val chat = messages.getOrPut(chatId) { HashMap() }
            if (!chatUnchanged() || chat[messageId]?.stamp != expected) return IndexWrite.STALE
            val old = chat.remove(messageId)
            if (replacement != null) {
                chat[messageId] = replacement
                replacement.passages.forEach {
                    vectors.putIfAbsent(Triple(it.searchFingerprint, replacement.stamp.vectorModel, replacement.stamp.vectorDimensions), "waiting")
                }
            }
            old?.passages?.forEach { passage ->
                val key = Triple(passage.searchFingerprint, old.stamp.vectorModel, old.stamp.vectorDimensions)
                val used = messages.values.flatMap { it.values }.any { entries ->
                    entries.stamp.vectorModel == key.second && entries.passages.any { it.searchFingerprint == key.first }
                }
                if (!used) vectors.remove(key)
            }
            return IndexWrite.WRITTEN
        }

        override fun restampMessage(
            chatId: String, messageId: String, expected: MessageStamp, stamp: MessageStamp,
            chatUnchanged: () -> Boolean
        ): IndexWrite {
            beforeWrite?.invoke()
            if (++writes == failOnWrite) throw IllegalStateException("disk full")
            val chat = messages.getOrPut(chatId) { HashMap() }
            val current = chat[messageId]
            if (!chatUnchanged() || current?.stamp != expected) return IndexWrite.STALE
            chat[messageId] = current.copy(stamp = stamp)
            return IndexWrite.WRITTEN
        }

        override fun finishUpdate(
            chatId: String, chatVersion: String, expected: Map<String, MessageStamp>, unidentifiedMessages: Int,
            chatUnchanged: () -> Boolean
        ): IndexWrite {
            if (!chatUnchanged() || indexedMessages(chatId) != expected) return IndexWrite.STALE
            val waiting = messages[chatId].orEmpty().values.sumOf { entries ->
                entries.passages.count { vectors[Triple(it.searchFingerprint, entries.stamp.vectorModel, entries.stamp.vectorDimensions)] == "waiting" }
            }
            val unfinished = messages[chatId].orEmpty().values.sumOf { entries ->
                entries.passages.count { vectors[Triple(it.searchFingerprint, entries.stamp.vectorModel, entries.stamp.vectorDimensions)] != "made" }
            }
            states[chatId] = ChatIndexState(chatVersion, false, waiting, unfinished == 0, unidentifiedMessages)
            return IndexWrite.WRITTEN
        }
    }

    private val reader = SearchTextReader(
        vectorModel = "test-model-8",
        vectorDimensions = 8,
        hardLimit = 400,
        prefixFor = { "title: $it | text: " },
        countModelInput = { text -> text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2 }
    )

    private val storage = MemoryStorage()
    private val updater = ConversationIndexUpdater(storage, reader, targetTokens = 40, overlapWords = 5)
    private var version = "v1"

    private fun user(id: String, text: String, scope: ScanMessageScope? = null) =
        IndexSourceMessage(id, false, text, false, null, 100L, scope)

    private fun reply(id: String, text: String, state: String? = MessageCompletionState.DONE, hidden: Boolean = false) =
        IndexSourceMessage(id, true, text, hidden, state, 200L, null)

    private fun long(word: String) = (1..120).joinToString(" ") { "$word$it" }

    private fun run(messages: List<IndexSourceMessage>, recorded: String = version) =
        updater.update("chat", recorded, messages, { version })

    private fun chat() = listOf(
        user("u1", "I moved to Leeds.", ScanMessageScope(companionPersonaId = "persona-1")),
        reply("a1", long("leeds")),
        user("u2", "Tell me about bicycles."),
        reply("a2", "Bicycles are fun.")
    )

    @Test fun newMessagesAreIndexedWithStampsAndWaitingVectors() {
        val outcome = run(chat())
        assertEquals(ConversationIndexUpdater.Outcome.Updated(4, 0, 0, 0), outcome)
        val indexed = storage.messages.getValue("chat")
        assertEquals(setOf("u1", "a1", "u2", "a2"), indexed.keys)
        assertTrue(indexed.getValue("a1").passages.size > 1)
        assertEquals("u1", indexed.getValue("a1").stamp.exchangeId)
        assertEquals("u2", indexed.getValue("a2").stamp.exchangeId)
        assertEquals("u1", indexed.getValue("u1").stamp.exchangeId)
        assertEquals(Speaker.AI, indexed.getValue("a1").stamp.speaker)
        assertEquals(1, indexed.getValue("a1").stamp.chatPosition)
        assertEquals(ScanMessageScope(companionPersonaId = "persona-1"), indexed.getValue("u1").stamp.scope)
        assertNull("unknown scope stays unknown", indexed.getValue("u2").stamp.scope)
        assertEquals(PassageCutter.CUTTER_VERSION, indexed.getValue("a1").stamp.cutterVersion)
        assertTrue(storage.vectors.values.all { it == "waiting" })
        val passages = indexed.values.sumOf { it.passages.size }
        assertEquals(ChatIndexState("v1", false, passages, false), storage.indexState("chat"))
    }

    @Test fun unchangedMessagesAreNotTouched() {
        run(chat())
        val writes = storage.writes
        version = "v2"
        assertEquals(ConversationIndexUpdater.Outcome.Updated(0, 0, 0, 4), run(chat()))
        assertEquals(writes, storage.writes)
        assertEquals("v2", storage.indexState("chat")!!.describedVersion)
    }

    @Test fun anEditedMessageIsReplacedAndItsOldVectorsRemoved() {
        run(chat())
        val oldVectors = storage.messages.getValue("chat").getValue("a2").passages.map { it.searchFingerprint }
        version = "v2"
        val edited = chat().toMutableList().also { it[3] = reply("a2", "Bicycles need oiling.") }
        assertEquals(ConversationIndexUpdater.Outcome.Updated(1, 0, 0, 3), run(edited))
        val a2 = storage.messages.getValue("chat").getValue("a2")
        assertEquals(IndexFingerprint.of("Bicycles need oiling."), a2.stamp.textFingerprint)
        oldVectors.forEach { fingerprint -> assertFalse(storage.vectors.keys.any { it.first == fingerprint }) }
    }

    @Test fun deletedHiddenUnfinishedAndFailedMessagesLoseTheirPassages() {
        run(chat())
        version = "v2"
        val changed = listOf(
            user("u1", "I moved to Leeds.", ScanMessageScope(companionPersonaId = "persona-1")),
            reply("a1", long("leeds"), hidden = true),
            user("u2", "Tell me about bicycles."),
            reply("a2", "Bicycles are fun.", state = MessageCompletionState.FAILED)
        )
        assertEquals(ConversationIndexUpdater.Outcome.Updated(0, 0, 2, 2), run(changed))
        assertEquals(setOf("u1", "u2"), storage.messages.getValue("chat").keys)
        version = "v3"
        val deleted = listOf(changed[0], reply("a3", "Streaming…", state = MessageCompletionState.STREAMING))
        assertEquals(ConversationIndexUpdater.Outcome.Updated(0, 0, 1, 1), run(deleted))
        assertEquals(setOf("u1"), storage.messages.getValue("chat").keys)
        // Only u1's passages keep vectors.
        val u1 = storage.messages.getValue("chat").getValue("u1").passages.map { it.searchFingerprint }.toSet()
        assertEquals(u1, storage.vectors.keys.map { it.first }.toSet())
    }

    @Test fun messagesWithoutAPermanentIdAreNotIndexed() {
        assertEquals(
            ConversationIndexUpdater.Outcome.Updated(1, 0, 0, 0),
            run(listOf(user("", "Old message without an ID."), reply("a1", "A reply with one.")))
        )
        val a1 = storage.messages.getValue("chat").getValue("a1")
        assertNull("an exchange opened by a message without an ID has no exchange ID", a1.stamp.exchangeId)
    }

    @Test fun aMovedMessageIsOnlyRestamped() {
        run(chat())
        val before = storage.messages.getValue("chat").getValue("a2").passages
        version = "v2"
        // Deleting the first exchange moves the second one up.
        assertEquals(ConversationIndexUpdater.Outcome.Updated(0, 2, 2, 0), run(chat().drop(2)))
        val a2 = storage.messages.getValue("chat").getValue("a2")
        assertEquals(1, a2.stamp.chatPosition)
        assertEquals(before, a2.passages)
    }

    @Test fun aResultForAnOlderChatVersionIsDiscarded() {
        run(chat())
        val before = storage.messages.getValue("chat").toMap()
        version = "v3"
        // The update was prepared for v2, but the chat is already at v3.
        val edited = chat().toMutableList().also { it[1] = reply("a1", "Short now.") }
        assertEquals(ConversationIndexUpdater.Outcome.Stale, run(edited, recorded = "v2"))
        assertEquals(before, storage.messages.getValue("chat"))
    }

    @Test fun aChatThatChangesDuringTheUpdateKeepsThePreviousStateForUnwrittenMessages() {
        run(chat())
        val before = storage.messages.getValue("chat").toMap()
        version = "v2"
        val edited = chat().map { if (it.isBot) it.copy(modelText = it.modelText + " Edited.") else it }
        var calls = 0
        // The chat is saved again just before the second write.
        storage.beforeWrite = { if (++calls == 2) version = "v3" }
        assertEquals(ConversationIndexUpdater.Outcome.Stale, run(edited, recorded = "v2"))
        val after = storage.messages.getValue("chat")
        assertEquals(before.getValue("a2"), after.getValue("a2"))
        assertEquals(IndexFingerprint.of(edited[1].modelText), after.getValue("a1").stamp.textFingerprint)
        val state = storage.indexState("chat")!!
        assertTrue(state.needsRefresh)
        assertEquals("v1", state.describedVersion)
    }

    @Test fun aFailureHalfwayLeavesEveryMessageWholeAndTheChatMarkedForRefresh() {
        run(chat())
        val before = storage.messages.getValue("chat").toMap()
        version = "v2"
        val edited = chat().map { if (it.isBot) it.copy(modelText = it.modelText + " Edited.") else it }
        storage.failOnWrite = storage.writes + 2
        val outcome = run(edited)
        assertTrue(outcome is ConversationIndexUpdater.Outcome.Failed)
        val after = storage.messages.getValue("chat")
        assertEquals(IndexFingerprint.of(edited[1].modelText), after.getValue("a1").stamp.textFingerprint)
        assertEquals(before.getValue("a2"), after.getValue("a2"))
        assertTrue(storage.indexState("chat")!!.needsRefresh)
        assertEquals("v1", storage.indexState("chat")!!.describedVersion)
        // The next update completes the work.
        storage.failOnWrite = null
        assertEquals(ConversationIndexUpdater.Outcome.Updated(1, 0, 0, 3), run(edited))
        assertFalse(storage.indexState("chat")!!.needsRefresh)
    }

    @Test fun cancellingBetweenMessagesWritesNothingMore() {
        var checks = 0
        val outcome = updater.update("chat", "v1", chat(), { version }, isCancelled = { ++checks > 2 })
        assertEquals(ConversationIndexUpdater.Outcome.Cancelled, outcome)
        assertEquals(setOf("u1", "a1"), storage.messages.getValue("chat").keys)
        assertTrue(storage.indexState("chat")!!.needsRefresh)
    }

    @Test fun indexedMessagesAreExactlyTheMessagesTheAiMaySee() {
        val messages = listOf(
            user("u1", "Visible."),
            reply("a1", "Hidden reply.", hidden = true),
            user("u2", "Hidden question.").copy(hiddenFromAi = true),
            reply("a2", "Interrupted.", state = MessageCompletionState.INTERRUPTED),
            reply("a3", "Stopped.", state = MessageCompletionState.STOPPED),
            reply("a4", "Legacy reply with no state.", state = null),
            reply("a5", "")
        )
        run(messages)
        assertEquals(
            messages.filter(IndexEligibility::isEligible).map { it.messageId }.toSet(),
            storage.messages.getValue("chat").keys
        )
        assertEquals(setOf("u1", "a4"), storage.messages.getValue("chat").keys)
    }

    @Test fun defaultTitlesAreHumanAndCompanion() {
        run(chat())
        val indexed = storage.messages.getValue("chat")
        assertEquals("Human", indexed.getValue("u1").stamp.speakerTitle)
        assertEquals("Companion", indexed.getValue("a1").stamp.speakerTitle)
        assertTrue(indexed.getValue("a1").passages.all { it.modelInput.startsWith("title: Companion | text: ") })
        assertTrue(indexed.getValue("u1").passages.all { it.modelInput.startsWith("title: Human | text: ") })
    }

    @Test fun eachMessageCanHaveItsOwnTitleFromItsRecordedName() {
        // A test policy: the name recorded on the message, else the default label.
        val named = SpeakerTitlePolicy { it.recordedSpeakerName ?: SpeakerTitlePolicy.DEFAULT.titleFor(it) }
        val updater = ConversationIndexUpdater(storage, reader, 40, 5, named)
        val messages = listOf(
            user("u1", "Hello both of you.").copy(recordedSpeakerName = "Sam"),
            reply("a1", "Hello Sam.").copy(recordedSpeakerName = "Ash"),
            reply("a2", "Hello Sam.").copy(recordedSpeakerName = "Rowan"),
            reply("a3", "Hello Sam.")
        )
        updater.update("chat", "v1", messages, { version })
        val indexed = storage.messages.getValue("chat")
        assertEquals("Sam", indexed.getValue("u1").stamp.speakerTitle)
        assertEquals("Ash", indexed.getValue("a1").stamp.speakerTitle)
        assertEquals("Rowan", indexed.getValue("a2").stamp.speakerTitle)
        assertEquals("Companion", indexed.getValue("a3").stamp.speakerTitle)
        // The same words under different titles are different search texts.
        val fingerprints = listOf("a1", "a2", "a3").map { indexed.getValue(it).passages.single().searchFingerprint }
        assertEquals(3, fingerprints.toSet().size)

        // The recorded name stays with each message, so nothing is cut again
        // until a message's own title changes.
        val writes = storage.writes
        assertEquals(ConversationIndexUpdater.Outcome.Updated(0, 0, 0, 4), updater.update("chat", "v1", messages, { version }))
        assertEquals(writes, storage.writes)
        val retitled = messages.toMutableList().also { it[1] = it[1].copy(recordedSpeakerName = "Ash Grey") }
        assertEquals(ConversationIndexUpdater.Outcome.Updated(1, 0, 0, 3), updater.update("chat", "v1", retitled, { version }))
    }

    @Test fun aTitleThatIsNotOnePlainLineIsRefused() {
        val broken = ConversationIndexUpdater(storage, reader, 40, 5) { "Ash | admin" }
        assertTrue(broken.update("chat", "v1", chat(), { version }) is ConversationIndexUpdater.Outcome.Failed)
        assertTrue(storage.messages["chat"].isNullOrEmpty())
    }
}
