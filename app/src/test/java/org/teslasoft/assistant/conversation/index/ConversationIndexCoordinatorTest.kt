package org.teslasoft.assistant.conversation.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope

/**
 * Step 10 (memory development plan G.12; G.5a rules 3, 4, 7, 8, 9 and 10):
 * one test or more for each upkeep event, through the coordinator the
 * hookups call, with the real updater and vector worker against the
 * in-memory index that keeps the encrypted store's transaction contract.
 * The encrypted store and the real saved-chat reader are covered by
 * `ConversationIndexUpkeepInstrumentedTest`.
 */
class ConversationIndexCoordinatorTest {

    /**
     * Runs handed-in tasks only when asked, in the order they are due on a
     * clock that moves to each task's time, so a test can see that a call did
     * no work itself and that waits are kept.
     */
    private class ManualRunner : IndexTaskRunner {
        private class Due(val at: Long, val order: Long, val task: () -> Unit)
        var now = 0L
        private var order = 0L
        private val due = java.util.PriorityQueue<Due>(compareBy<Due>({ it.at }, { it.order }))
        val tasks: Collection<*> get() = due
        override fun run(delayMillis: Long, task: () -> Unit) { due.add(Due(now + delayMillis, order++, task)) }
        fun runAll() {
            var guard = 0
            while (due.isNotEmpty()) {
                check(++guard < 10_000) { "tasks never settle" }
                val next = due.poll()!!
                now = maxOf(now, next.at)
                next.task()
            }
        }
        /** Runs [task] at [atMillis] on this clock. */
        fun at(atMillis: Long, task: () -> Unit) { due.add(Due(atMillis, order++, task)) }
    }

    /** The stored chats, in memory. */
    private class Chats : IndexChatSource {
        val chats = LinkedHashMap<String, List<IndexSourceMessage>>()
        val versions = HashMap<String, Int>()
        /** Changes reported in this run of the app; a restart clears them. */
        val changes = HashMap<String, Long>()
        /** Chats saved before permanent IDs and not opened since: their messages have no ID. */
        val withoutIds = HashSet<String>()
        val unavailable = HashSet<String>()
        val reads = ArrayList<String>()
        /** Called after a read has taken the chat's messages, before the update writes. */
        var afterRead: ((String) -> Unit)? = null

        fun put(chatId: String, messages: List<IndexSourceMessage>) {
            chats.remove(chatId)
            chats[chatId] = messages // the most recently saved chat is the newest
            versions[chatId] = (versions[chatId] ?: 0) + 1
            changes[chatId] = (changes[chatId] ?: 0L) + 1
        }

        fun remove(chatId: String) {
            chats.remove(chatId)
            versions[chatId] = (versions[chatId] ?: 0) + 1
        }

        override fun read(chatId: String): IndexChatSource.Read {
            reads.add(chatId)
            if (chatId in unavailable) return IndexChatSource.Read.Unavailable
            val messages = chats[chatId].orEmpty().let { list ->
                if (chatId in withoutIds) list.map { it.copy(messageId = "") } else list
            }
            val read = IndexChatSource.Read.Messages(chatVersion(chatId), changeCount(chatId), messages)
            afterRead?.invoke(chatId)
            return read
        }

        override fun chatVersion(chatId: String) = (versions[chatId] ?: 0).toString()
        override fun changeCount(chatId: String) = changes[chatId] ?: 0L
        override fun chatIdsNewestFirst() = chats.keys.reversed()
        override fun currentMessage(chatId: String, messageId: String) =
            chats[chatId]?.firstOrNull { it.messageId == messageId }
    }

    /** A model that records every exact input it reads. */
    private class Vectors(override val vectorModel: String) : PassageVectorReader {
        override val vectorDimensions = DIMENSIONS
        override val tokenLimit = 400
        val inputs = ArrayList<String>()
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            if (countTokens(modelInput) > tokenLimit) throw InputTooLongException(countTokens(modelInput), tokenLimit)
            inputs.add(modelInput)
            return FloatArray(DIMENSIONS) { (modelInput.hashCode() + it).toFloat() }
        }
    }

    private class Pause : EmbeddingPauseState { override var paused = false }

    private fun reader(model: String) = SearchTextReader(
        vectorModel = model,
        vectorDimensions = DIMENSIONS,
        hardLimit = 400,
        prefixFor = { "title: $it | text: " },
        countModelInput = ::words
    )

    private val index = MemoryIndex()
    private val chats = Chats()
    private val runner = ManualRunner()
    private var model = MODEL_A
    private var targetTokens = 40
    private var on = true
    private var vectorRequests = 0
    private val vectors = HashMap<String, Vectors>()

    private fun vectorsFor(model: String) = vectors.getOrPut(model) { Vectors(model) }

    private val coordinator = ConversationIndexCoordinator(
        source = chats,
        storage = { index },
        maintenance = { index },
        updaterFor = { ConversationIndexUpdater(index, reader(model), targetTokens, OVERLAP) },
        requestVectors = { vectorRequests++ },
        runner = runner,
        isOn = { on },
        clock = { runner.now }
    )

    /** One vector pass for the current model, as the vector requests run it. */
    private fun makeVectors(): PassageEmbeddingWorker.Outcome =
        PassageEmbeddingWorker(index, chats, reader(model), vectorsFor(model), Pause()).runPass()

    private fun user(id: String, text: String, scope: ScanMessageScope? = null) =
        IndexSourceMessage(id, false, text, false, null, 100L, scope)

    private fun reply(
        id: String, text: String, state: String? = MessageCompletionState.DONE, hidden: Boolean = false,
        scope: ScanMessageScope? = null
    ) = IndexSourceMessage(id, true, text, hidden, state, 200L, scope)

    private fun long(word: String) = (1..120).joinToString(" ") { "$word$it" }

    private fun save(chatId: String, messages: List<IndexSourceMessage>) {
        chats.put(chatId, messages)
        coordinator.chatChanged(chatId)
    }

    private fun stamps(chatId: String) = index.indexedMessages(chatId)
    private fun keysOf(chatId: String, messageId: String? = null) =
        index.rows.filter { it.chatId == chatId && (messageId == null || it.messageId == messageId) }.map { it.key }.toSet()
    private fun waiting() = index.vectors.count { it.value.state == ConversationIndexStore.STATE_WAITING }

    private fun chat() = listOf(
        user("u1", "I moved to Leeds."),
        reply("a1", long("leeds")),
        user("u2", "Tell me about bicycles."),
        reply("a2", "Bicycles are fun.")
    )

    /** No passage row appears twice and every message has exactly one stamp. */
    private fun assertNoDuplicates() {
        val keys = index.rows.map { Triple(it.chatId, it.messageId, it.order) }
        assertEquals(keys.size, keys.toSet().size)
    }

    // --- New message; reply finishes streaming ----------------------------

    @Test fun aSavedChatIsIndexedInTheBackgroundAndNeverDuringTheCall() {
        save("chat", chat())
        // The hookup only recorded the change: nothing was read or written.
        assertTrue(chats.reads.isEmpty())
        assertTrue(index.rows.isEmpty())
        assertEquals(1, runner.tasks.size)

        runner.runAll()
        assertEquals(setOf("u1", "a1", "u2", "a2"), stamps("chat").keys)
        assertTrue(vectorRequests > 0)
        assertTrue(waiting() > 0)
        assertTrue(makeVectors() is PassageEmbeddingWorker.Outcome.Finished)
        assertTrue(index.chatProgress("chat").fullyIndexed)
    }

    @Test fun aNewMessageAddsOnlyItsEntries() {
        save("chat", chat())
        runner.runAll()
        makeVectors()
        val before = index.rows.toSet()
        save("chat", chat() + user("u3", "And trains?"))
        runner.runAll()
        assertEquals(setOf("u1", "a1", "u2", "a2", "u3"), stamps("chat").keys)
        assertTrue(index.rows.containsAll(before))
        assertEquals(keysOf("chat", "u3"), index.vectors.filterValues { it.state == ConversationIndexStore.STATE_WAITING }.keys)
    }

    @Test fun aReplyIsIndexedWhenItFinishesNotWhileStreamingAndAFailedReplyNever() {
        save("chat", listOf(user("u1", "Hello"), reply("a1", "Partial answer", MessageCompletionState.STREAMING)))
        runner.runAll()
        assertEquals(setOf("u1"), stamps("chat").keys)

        save("chat", listOf(user("u1", "Hello"), reply("a1", "Partial answer", MessageCompletionState.FAILED)))
        runner.runAll()
        assertEquals(setOf("u1"), stamps("chat").keys)

        save("chat", listOf(user("u1", "Hello"), reply("a1", "The whole answer", MessageCompletionState.DONE)))
        runner.runAll()
        assertEquals(setOf("u1", "a1"), stamps("chat").keys)
    }

    @Test fun aReplySavedEveryFewSecondsWhileStreamingIsComparedOnceAfterItFinishes() {
        val streaming = { text: String -> listOf(user("u1", "Hello"), reply("a1", text, MessageCompletionState.STREAMING)) }
        save("chat", streaming("The"))
        runner.at(2_000) { save("chat", streaming("The answer")) }
        runner.at(4_000) { save("chat", streaming("The answer is")) }
        runner.at(6_000) { save("chat", listOf(user("u1", "Hello"), reply("a1", "The answer is here."))) }
        runner.runAll()
        assertEquals(listOf("chat"), chats.reads)
        assertTrue(runner.now >= 6_000 + ConversationIndexCoordinator.CHANGE_WAIT_MILLIS)
        assertEquals(setOf("u1", "a1"), stamps("chat").keys)
    }

    @Test fun aBurstOfSavesIsOneUpdate() {
        save("chat", chat())
        save("chat", chat())
        save("chat", chat())
        assertEquals(1, runner.tasks.size)
        runner.runAll()
        assertEquals(listOf("chat"), chats.reads)
    }

    // --- Message edited ---------------------------------------------------

    @Test fun anEditReplacesThatMessagesPassagesAndUnchangedSearchTextKeepsItsVector() {
        save("chat", chat())
        runner.runAll()
        makeVectors()
        val oldA1 = keysOf("chat", "a1")
        val untouched = keysOf("chat", "u1") + keysOf("chat", "u2") + keysOf("chat", "a2")

        val edited = chat().map { if (it.messageId == "a1") it.copy(modelText = long("leeds") + " changed") else it }
        save("chat", edited)
        runner.runAll()

        val newA1 = keysOf("chat", "a1")
        val kept = oldA1.intersect(newA1)
        assertTrue("the early passages' search text is unchanged", kept.isNotEmpty())
        assertTrue(newA1 != oldA1)
        kept.forEach { assertEquals(ConversationIndexStore.STATE_MADE, index.state(it)) }
        untouched.forEach { assertEquals(ConversationIndexStore.STATE_MADE, index.state(it)) }
        (oldA1 - newA1).forEach { assertNull("an unused vector is removed", index.state(it)) }
        assertEquals((newA1 - oldA1).size, waiting())
    }

    // --- Reply regenerated, another version made canonical ----------------

    @Test fun entriesFollowTheCanonicalVersionAndASaveWithoutChangesWritesNothing() {
        save("chat", chat())
        runner.runAll()
        makeVectors()
        val rows = index.rows.toList()
        val vectorsBefore = index.vectors.toMap()

        // Browsing another version changes only display state: the AI-facing messages are the same.
        save("chat", chat())
        runner.runAll()
        assertEquals(rows, index.rows.toList())
        assertEquals(vectorsBefore.keys, index.vectors.keys)

        // Making another version canonical changes the reply's AI-facing text under the same ID.
        val regenerated = chat().map { if (it.messageId == "a2") it.copy(modelText = "Bicycles are wonderful.") else it }
        save("chat", regenerated)
        runner.runAll()
        assertEquals(IndexFingerprint.of("Bicycles are wonderful."), stamps("chat").getValue("a2").textFingerprint)
        assertEquals(1, index.rows.count { it.chatId == "chat" && it.messageId == "a2" })
        assertNoDuplicates()
    }

    // --- Message deleted --------------------------------------------------

    @Test fun aDeletedMessageLosesItsEntriesAndVectorsNothingElseUses() {
        save("chat", chat())
        save("other", listOf(user("o1", "Tell me about bicycles.")))
        runner.runAll()
        makeVectors()
        val shared = keysOf("chat", "u2")
        assertEquals(shared, keysOf("other", "o1"))
        val a1 = keysOf("chat", "a1")

        save("chat", chat().filter { it.messageId != "a1" && it.messageId != "u2" })
        runner.runAll()
        assertEquals(setOf("u1", "a2"), stamps("chat").keys)
        a1.forEach { assertNull(index.state(it)) }
        shared.forEach { assertEquals("another chat still uses it", ConversationIndexStore.STATE_MADE, index.state(it)) }
    }

    // --- Hidden from the AI, or made visible again ------------------------

    @Test fun hidingRemovesTheMessagesEntriesAndShowingAddsThemBack() {
        save("chat", chat())
        runner.runAll()
        val a2 = keysOf("chat", "a2")

        save("chat", chat().map { if (it.messageId == "a2") it.copy(hiddenFromAi = true) else it })
        runner.runAll()
        assertFalse("a2" in stamps("chat"))
        a2.forEach { assertNull("no vector is kept for hidden text", index.state(it)) }

        save("chat", chat())
        runner.runAll()
        assertTrue("a2" in stamps("chat"))
        assertEquals(a2, keysOf("chat", "a2"))
    }

    // --- Chat deleted -----------------------------------------------------

    @Test fun aDeletedChatLosesEverythingAndOnlyVectorsNoOtherChatUses() {
        save("chat", chat())
        save("other", listOf(user("o1", "Tell me about bicycles.")))
        runner.runAll()
        makeVectors()
        val shared = keysOf("other")
        val own = keysOf("chat") - shared

        chats.remove("chat")
        coordinator.chatsDeleted(listOf("chat"))
        assertTrue("nothing happens during the call", stamps("chat").isNotEmpty())
        runner.runAll()

        assertTrue(stamps("chat").isEmpty())
        assertNull(index.indexState("chat"))
        own.forEach { assertNull(index.state(it)) }
        shared.forEach { assertEquals(ConversationIndexStore.STATE_MADE, index.state(it)) }
        assertEquals(setOf("o1"), stamps("other").keys)
    }

    @Test fun aChatDeletedWhileWaitingForItsUpdateIsNotIndexedAgain() {
        save("chat", chat())
        chats.remove("chat")
        coordinator.chatsDeleted(listOf("chat"))
        runner.runAll()
        assertTrue(index.rows.isEmpty())
        assertTrue(index.vectors.isEmpty())
    }

    // --- Chat renamed (an older chat's ID changes) ------------------------

    @Test fun renamingAnOlderChatMovesItsEntriesToTheNewIdWithoutReadingAnythingAgain() {
        save("old-id", chat())
        runner.runAll()
        makeVectors()
        val readsBefore = vectorsFor(MODEL_A).inputs.size
        val keys = keysOf("old-id")

        chats.remove("old-id")
        chats.put("new-id", chat())
        coordinator.chatMoved("old-id", "new-id")
        runner.runAll()

        assertTrue(stamps("old-id").isEmpty())
        assertNull(index.indexState("old-id"))
        assertEquals(setOf("u1", "a1", "u2", "a2"), stamps("new-id").keys)
        assertEquals(keys, keysOf("new-id"))
        keys.forEach { assertEquals(ConversationIndexStore.STATE_MADE, index.state(it)) }
        assertTrue(makeVectors() is PassageEmbeddingWorker.Outcome.Finished)
        assertEquals("nothing is read twice", readsBefore, vectorsFor(MODEL_A).inputs.size)
        assertTrue(index.chatProgress("new-id").fullyIndexed)
        assertNoDuplicates()
    }

    // --- Cutting rules or target size changed -----------------------------

    @Test fun aNewTargetSizeReCutsInTheBackgroundAndUnchangedSearchTextKeepsItsVector() {
        save("chat", chat())
        runner.runAll()
        makeVectors()
        val short = keysOf("chat", "u1") + keysOf("chat", "u2") + keysOf("chat", "a2")
        val longBefore = keysOf("chat", "a1")

        targetTokens = 60
        coordinator.appStarted()
        runner.runAll()

        assertTrue(stamps("chat").values.all { it.searchSettings.startsWith("cut-t60|") })
        assertTrue(keysOf("chat", "a1") != longBefore)
        short.forEach { assertEquals("a short message's search text is the same", ConversationIndexStore.STATE_MADE, index.state(it)) }
    }

    // --- The user deletes the index ---------------------------------------

    @Test fun deletingTheIndexRemovesEverythingDerivedAndLeavesTheChats() {
        save("chat", chat())
        runner.runAll()
        makeVectors()
        coordinator.deleteIndex()
        runner.runAll()
        assertTrue(index.rows.isEmpty())
        assertTrue(index.vectors.isEmpty())
        assertTrue(index.states.isEmpty())
        assertEquals(chat(), chats.chats["chat"])
    }

    // --- Backup restored, or merged into the phone's chats ----------------

    @Test fun aRestoreChecksEveryChatMessageByMessageAndNothingIsIndexedTwice() {
        save("c1", chat())
        save("gone", listOf(user("g1", "Only on this phone before the restore.")))
        runner.runAll()
        makeVectors()
        val u1 = keysOf("c1", "u1")
        val readsBefore = vectorsFor(MODEL_A).inputs.size

        // The restored set: c1 with one message changed and one new from the other phone,
        // a chat from the other phone, and no "gone".
        chats.remove("gone")
        chats.chats.remove("gone")
        chats.put("c1", chat().map { if (it.messageId == "a2") it.copy(modelText = "Bicycles are fast.") else it } +
            user("u3", "Written on the other phone."))
        chats.put("c2", listOf(user("x1", "A chat from the other phone.")))
        coordinator.chatSetReplaced()
        runner.runAll()

        assertEquals(setOf("u1", "a1", "u2", "a2", "u3"), stamps("c1").keys)
        assertEquals(IndexFingerprint.of("Bicycles are fast."), stamps("c1").getValue("a2").textFingerprint)
        assertEquals(setOf("x1"), stamps("c2").keys)
        assertTrue(stamps("gone").isEmpty())
        assertNull(index.indexState("gone"))
        assertEquals(u1, keysOf("c1", "u1"))
        u1.forEach { assertEquals("an unchanged message keeps its vector", ConversationIndexStore.STATE_MADE, index.state(it)) }
        assertNoDuplicates()

        makeVectors()
        val readAfter = vectorsFor(MODEL_A).inputs.drop(readsBefore)
        assertEquals("each new input is read once", readAfter.size, readAfter.toSet().size)
        assertEquals(keysOf("c1", "a2") + keysOf("c1", "u3") + keysOf("c2"), readAfter.map { VectorKey(IndexFingerprint.of(it), MODEL_A, DIMENSIONS) }.toSet())
    }

    @Test fun mergingTwoPhonesHistoriesNeverIndexesAMessageTwiceOrLeavesEntriesFromBoth() {
        // This phone's copy of the chat.
        save("shared", listOf(user("m1", "Our trip to Rome."), reply("m2", "Rome was lovely."), user("m3", "Typed on this phone.")))
        runner.runAll()
        makeVectors()
        val readsBefore = vectorsFor(MODEL_A).inputs.size

        // The merged chat holds both phones' messages; m2 was edited on the other phone,
        // and a kept copy of the chat (another chat ID) holds the same messages.
        val merged = listOf(
            user("m1", "Our trip to Rome."), reply("m2", "Rome was lovely, and warm."),
            user("m3", "Typed on this phone."), user("m4", "Typed on the other phone.")
        )
        chats.put("shared", merged)
        chats.put("shared-copy", merged)
        coordinator.chatSetReplaced()
        runner.runAll()
        makeVectors()

        for (chatId in listOf("shared", "shared-copy")) {
            assertEquals(setOf("m1", "m2", "m3", "m4"), stamps(chatId).keys)
            assertEquals(IndexFingerprint.of("Rome was lovely, and warm."), stamps(chatId).getValue("m2").textFingerprint)
            assertEquals("one entry for m2, from the merged text only", 1, index.rows.count { it.chatId == chatId && it.messageId == "m2" })
        }
        assertNoDuplicates()
        assertEquals(keysOf("shared"), keysOf("shared-copy"))
        // Only m2's new text and m4 were read; the copy reuses every vector.
        val read = vectorsFor(MODEL_A).inputs.drop(readsBefore)
        assertEquals(2, read.size)
        assertTrue(index.chatProgress("shared-copy").fullyIndexed)
    }

    // --- Embedding model changed ------------------------------------------

    @Test fun aModelChangeReCutsInTheBackgroundAndKeepsTheOldVectorsUntilTheNewIndexIsComplete() {
        save("chat", chat())
        runner.runAll()
        makeVectors()
        val oldKeys = keysOf("chat")
        assertTrue(oldKeys.all { it.vectorModel == MODEL_A })

        model = MODEL_B
        coordinator.embeddingModelChanged()
        assertTrue("nothing during the call", stamps("chat").values.all { it.vectorModel == MODEL_A })
        runner.runAll()

        assertTrue(stamps("chat").values.all { it.vectorModel == MODEL_B })
        oldKeys.forEach { assertEquals("kept until the new index is complete", ConversationIndexStore.STATE_MADE, index.state(it)) }
        assertTrue(makeVectors() is PassageEmbeddingWorker.Outcome.Finished)
        oldKeys.forEach { assertNull("removed once complete", index.state(it)) }
        assertTrue(index.chatProgress("chat").fullyIndexed)
    }

    // --- Chat settings changed --------------------------------------------

    @Test fun laterMessagesCarryTheNewScopeAndEarlierOnesKeepTheirs() {
        val companion = ScanMessageScope(companionPersonaId = "companion-1")
        val project = ScanMessageScope(projectId = "project-1")
        save("chat", listOf(user("u1", "First.", companion), reply("a1", "Answer one.", scope = companion)))
        runner.runAll()
        save("chat", listOf(
            user("u1", "First.", companion), reply("a1", "Answer one.", scope = companion),
            user("u2", "Second.", project), reply("a2", "Answer two.", scope = project)
        ))
        runner.runAll()
        assertEquals(companion, stamps("chat").getValue("u1").scope)
        assertEquals(companion, stamps("chat").getValue("a1").scope)
        assertEquals(project, stamps("chat").getValue("u2").scope)
        assertEquals(project, stamps("chat").getValue("a2").scope)
    }

    @Test fun aMessageWithoutRecordedScopeIsIndexedAsUnknown() {
        save("chat", listOf(user("u1", "Written before scope was recorded.")))
        runner.runAll()
        assertNull(stamps("chat").getValue("u1").scope)
    }

    // --- Existing chats, first time; already indexed text -----------------

    @Test fun existingChatsAreIndexedNewestFirstAndChatsWithoutPermanentIdsWaitUntilOpened() {
        chats.put("oldest", listOf(user("a", "Oldest chat.")))
        chats.put("never-opened", listOf(user("b", "No permanent IDs yet.")))
        chats.withoutIds.add("never-opened")
        chats.put("newest", listOf(user("c", "Newest chat.")))

        coordinator.appStarted()
        runner.runAll()
        assertEquals(listOf("newest", "never-opened", "oldest"), chats.reads)
        assertEquals(setOf("c"), stamps("newest").keys)
        assertEquals(setOf("a"), stamps("oldest").keys)
        assertTrue(stamps("never-opened").isEmpty())
        assertTrue(index.rows.none { it.chatId == "never-opened" })

        // It is not read again until it changes.
        coordinator.appStarted()
        runner.runAll()
        assertEquals(listOf("newest", "never-opened", "oldest"), chats.reads)

        // Opening the chat gives its messages IDs and saves it; then it is indexed.
        chats.withoutIds.remove("never-opened")
        save("never-opened", listOf(user("b", "No permanent IDs yet.")))
        runner.runAll()
        assertEquals(setOf("b"), stamps("never-opened").keys)
    }

    @Test fun alreadyIndexedChatsAreNotTouchedAgainWhenNothingChanged() {
        save("one", chat())
        save("two", listOf(user("t1", "Another chat.")))
        runner.runAll()
        makeVectors()
        val rows = index.rows.toList()
        val states = index.states.toMap()
        val readsBefore = chats.reads.size

        coordinator.appStarted()
        runner.runAll()
        assertEquals(rows, index.rows.toList())
        assertEquals(states, index.states.toMap())
        assertEquals("an up-to-date chat's history is not read", readsBefore, chats.reads.size)

        // A chat saved while the app was closed is read and updated.
        chats.put("two", listOf(user("t1", "Another chat."), user("t2", "Added later.")))
        coordinator.appStarted()
        runner.runAll()
        assertEquals(listOf("two"), chats.reads.drop(readsBefore))
        assertEquals(setOf("t1", "t2"), stamps("two").keys)
    }

    @Test fun afterARestartChatsSavedInThePreviousRunAreNotReadAgain() {
        save("one", chat())
        save("two", listOf(user("t1", "Another chat.")))
        runner.runAll()
        val readsBefore = chats.reads.size

        chats.changes.clear() // the app closed and started again; the files are unchanged
        coordinator.appStarted()
        runner.runAll()
        assertEquals(readsBefore, chats.reads.size)
    }

    @Test fun aChangeReportedBeforeItReachesTheDiskMakesARunningUpdateStale() {
        save("chat", chat())
        var reported = false
        chats.afterRead = { chatId ->
            if (!reported) {
                reported = true
                // The save is reported; its file write has not landed, so the stored version is the same.
                chats.changes[chatId] = (chats.changes[chatId] ?: 0L) + 1
                chats.chats[chatId] = chat() + user("u3", "Saved during the update.")
                coordinator.chatChanged(chatId)
            }
        }
        runner.runAll()
        assertEquals(2, chats.reads.size)
        assertEquals(setOf("u1", "a1", "u2", "a2", "u3"), stamps("chat").keys)
    }

    @Test fun aChatThatCannotBeReadIsLeftAsItWasAndTriedAgainLater() {
        save("chat", chat())
        runner.runAll()
        val rows = index.rows.toList()
        chats.unavailable.add("chat")
        save("chat", chat().dropLast(1))
        runner.runAll()
        assertEquals(rows, index.rows.toList())

        chats.unavailable.remove("chat")
        coordinator.appStarted()
        runner.runAll()
        assertEquals(setOf("u1", "a1", "u2"), stamps("chat").keys)
    }

    // --- Changes during waits ---------------------------------------------

    @Test fun aChatChangedDuringItsUpdateIsUpdatedAgainToTheNewVersion() {
        save("chat", chat())
        var changedOnce = false
        chats.afterRead = { chatId ->
            if (!changedOnce) {
                changedOnce = true
                chats.put(chatId, chat() + user("u3", "Sent during the update."))
            }
        }
        runner.runAll()
        assertEquals(setOf("u1", "a1", "u2", "a2", "u3"), stamps("chat").keys)
        val state = index.indexState("chat")!!
        assertFalse(state.needsRefresh)
        assertEquals(
            ConversationIndexCoordinator.describedVersion(chats.chatVersion("chat"), ConversationIndexUpdater(index, reader(model), targetTokens, OVERLAP).settingsKey),
            state.describedVersion
        )
    }

    @Test fun aChatThatKeepsChangingIsLeftMarkedForRefreshAfterAFewTries() {
        save("chat", chat())
        chats.afterRead = { chatId -> chats.put(chatId, chat()) }
        runner.runAll()
        assertEquals(1 + ConversationIndexCoordinator.STALE_RETRIES, chats.reads.size)
        assertTrue(index.indexState("chat")?.needsRefresh ?: true)
    }

    // --- Off --------------------------------------------------------------

    @Test fun nothingIsDoneWhileIndexingIsOff() {
        on = false
        save("chat", chat())
        coordinator.appStarted()
        runner.runAll()
        assertTrue(chats.reads.isEmpty())
        assertTrue(index.rows.isEmpty())
        assertEquals(0, vectorRequests)
    }

    @Test fun nothingIsIndexedWithoutAnInstalledModel() {
        val withoutModel = ConversationIndexCoordinator(
            source = chats, storage = { index }, maintenance = { index }, updaterFor = { null },
            requestVectors = { vectorRequests++ }, runner = runner, clock = { runner.now }
        )
        chats.put("chat", chat())
        withoutModel.chatChanged("chat")
        withoutModel.appStarted()
        runner.runAll()
        assertTrue(index.rows.isEmpty())
    }

    // --- Vector passes ----------------------------------------------------

    @Test fun aVectorRequestDuringAPassRunsOneMorePassAfterIt() {
        val passRunner = ManualRunner()
        var passes = 0
        lateinit var passesRef: IndexVectorPasses
        passesRef = IndexVectorPasses(
            runPass = {
                passes++
                if (passes == 1) passesRef.request() // work arrives while the first pass runs
                PassageEmbeddingWorker.Outcome.Finished(PassageEmbeddingWorker.Counts(0, 0, 0), 0)
            },
            runner = passRunner
        )
        passesRef.request()
        passesRef.request()
        assertEquals("requests made before a pass starts share it", 1, passRunner.tasks.size)
        passRunner.runAll()
        assertEquals(2, passes)
    }

    @Test fun aPausedPassIsNotRepeated() {
        val passRunner = ManualRunner()
        var passes = 0
        val passesRef = IndexVectorPasses(
            runPass = { passes++; PassageEmbeddingWorker.Outcome.Paused(PassageEmbeddingWorker.Counts(0, 0, 0)) },
            runner = passRunner
        )
        passesRef.request()
        passRunner.runAll()
        assertEquals(1, passes)
    }

    private companion object {
        const val MODEL_A = "test-model-a"
        const val MODEL_B = "test-model-b"
        const val DIMENSIONS = 8
        const val OVERLAP = 5
        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
