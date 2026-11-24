package org.teslasoft.assistant.conversation.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException

/**
 * Step 12 (G.16a; Owner, 2026-11-24): the indexing progress behind the chat
 * header's icon and pop-up, through the real coordinator, updater, vector
 * worker and vector passes against the in-memory index that keeps the
 * encrypted store's contract. The store's SQL is covered by
 * `ConversationIndexProgressInstrumentedTest`, the icon and pop-up by
 * `ConversationIndexingIconInstrumentedTest`.
 */
class ConversationIndexProgressTest {

    /** Runs handed-in tasks in the order they are due on a clock that moves to each task's time. */
    private class Runner : IndexTaskRunner {
        private class Due(val at: Long, val order: Long, val task: () -> Unit)
        var now = 0L
        private var order = 0L
        private val due = java.util.PriorityQueue<Due>(compareBy<Due>({ it.at }, { it.order }))
        var afterTask: () -> Unit = {}
        val idle: Boolean get() = due.isEmpty()
        override fun run(delayMillis: Long, task: () -> Unit) { due.add(Due(now + delayMillis, order++, task)) }
        fun runOne(): Boolean {
            val next = due.poll() ?: return false
            now = maxOf(now, next.at)
            next.task()
            afterTask()
            return true
        }
        fun runAll() {
            var guard = 0
            while (runOne()) check(++guard < 10_000) { "tasks never settle" }
        }
        fun runUntil(condition: () -> Boolean) {
            var guard = 0
            while (!condition()) {
                check(runOne()) { "nothing left to run, and the condition never held" }
                check(++guard < 10_000) { "tasks never settle" }
            }
        }
    }

    /** The stored chats, in memory; [reads] lists every read, in order. */
    private class Chats : IndexChatSource {
        val chats = LinkedHashMap<String, List<IndexSourceMessage>>()
        val versions = HashMap<String, Int>()
        val withoutIds = HashSet<String>()
        val reads = ArrayList<String>()
        /** Chats whose reading and version both fail. */
        val failing = HashSet<String>()
        /** Called whenever the updater checks the chat's version before a write. */
        var onVersionCheck: (String) -> Unit = {}

        fun put(chatId: String, messages: List<IndexSourceMessage>) {
            chats.remove(chatId)
            chats[chatId] = messages
            versions[chatId] = (versions[chatId] ?: 0) + 1
        }

        override fun read(chatId: String): IndexChatSource.Read {
            reads.add(chatId)
            if (chatId in failing) throw IllegalStateException("cannot read $chatId")
            val stored = chats[chatId] ?: return IndexChatSource.Read.Unavailable
            val messages = if (chatId in withoutIds) stored.map { it.copy(messageId = "") } else stored
            return IndexChatSource.Read.Messages(chatVersion(chatId), 0L, messages)
        }

        override fun chatVersion(chatId: String): String {
            if (chatId in failing && reads.isNotEmpty()) throw IllegalStateException("cannot read $chatId")
            return (versions[chatId] ?: 0).toString()
        }
        override fun changeCount(chatId: String): Long {
            onVersionCheck(chatId)
            return 0L
        }
        override fun chatIdsNewestFirst() = chats.keys.reversed()
        override fun currentMessage(chatId: String, messageId: String) =
            chats[chatId]?.takeIf { chatId !in withoutIds }?.firstOrNull { it.messageId == messageId }
    }

    /** A model that records every exact input it reads and refuses inputs over [tokenLimit]. */
    private class Vectors : PassageVectorReader {
        override val vectorModel = MODEL
        override val vectorDimensions = DIMENSIONS
        override var tokenLimit = 400
        val inputs = ArrayList<String>()
        var afterRead: () -> Unit = {}
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            if (countTokens(modelInput) > tokenLimit) throw InputTooLongException(countTokens(modelInput), tokenLimit)
            inputs.add(modelInput)
            afterRead()
            return FloatArray(DIMENSIONS) { (modelInput.hashCode() + it).toFloat() }
        }
    }

    private class Pause : EmbeddingPauseState { override var paused = false }

    private class Saved : SavedIndexingProgress {
        var value: IndexingNumbers? = null
        override fun read() = value
        override fun write(numbers: IndexingNumbers?) { value = numbers }
    }

    private val index = MemoryIndex()
    private val chats = Chats()
    private val runner = Runner()
    private val pause = Pause()
    private val saved = Saved()
    private val vectors = Vectors()
    private var settingOn = true
    private var modelUnavailable = false
    private val reader = SearchTextReader(
        vectorModel = MODEL, vectorDimensions = DIMENSIONS, hardLimit = 400,
        prefixFor = { "title: $it | text: " }, countModelInput = ::words
    )
    private val updater = ConversationIndexUpdater(index, reader, 40, OVERLAP)

    /** Every distinct display published, in order. */
    private val shown = ArrayList<IndexingDisplay>()

    private val source = object : IndexProgressSource {
        var settingsKey: String? = null
        override fun work(chats: List<String>, settingsKey: String): IndexWork {
            this.settingsKey = settingsKey
            return IndexWorkFinder.work(
                chats, index.chatSummaries(MODEL, DIMENSIONS), index.waitingPassages(MODEL, DIMENSIONS),
                this@ConversationIndexProgressTest.chats::chatVersion, settingsKey
            )
        }
        override fun numbers(counted: Map<String, CountedChat>): IndexingNumbers? {
            val key = settingsKey ?: return null
            return IndexingProgressMath.numbers(
                chats.chatIdsNewestFirst(), index.chatSummaries(MODEL, DIMENSIONS),
                index.messageProgress(MODEL, DIMENSIONS), counted, chats::chatVersion, key
            )
        }
        override fun conditions() = IndexingConditions(settingOn, false, false, modelUnavailable)
    }

    private lateinit var tracker: IndexProgressTracker
    private lateinit var coordinator: ConversationIndexCoordinator

    /** Starts the app's indexing as the runtime builds it; called again for a restart. */
    private fun start() {
        tracker = IndexProgressTracker(source, pause, saved, runner)
        val passes = IndexVectorPasses(
            runPass = {
                val catchUp = tracker.vectorPassStarting()
                PassageEmbeddingWorker(index, chats, reader, vectors, pause).runPass { !settingOn }.also {
                    if (it is PassageEmbeddingWorker.Outcome.Finished) tracker.vectorPassFinished(catchUp)
                }
            },
            runner = runner,
            isOn = { settingOn && !tracker.isPaused }
        )
        coordinator = ConversationIndexCoordinator(
            source = chats,
            storage = { index },
            maintenance = { index },
            updaterFor = { updater },
            requestVectors = passes::request,
            runner = runner,
            isOn = { settingOn },
            isPaused = { tracker.isPaused },
            progress = tracker,
            clock = { runner.now }
        )
        tracker.publish()
        record()
        runner.afterTask = { record() }
    }

    init {
        start()
    }

    private fun record() {
        val now = tracker.display.value
        if (shown.lastOrNull() != now) shown.add(now)
    }

    private fun publish() {
        tracker.publish()
        record()
    }

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String, hidden: Boolean = false) =
        IndexSourceMessage(id, true, text, hidden, MessageCompletionState.DONE, 200L, null)

    /** Three short messages, each read as one vector. */
    private fun shortChat(prefix: String) = listOf(
        user("$prefix-u1", "Hello $prefix"),
        reply("$prefix-a1", "Hi $prefix"),
        user("$prefix-u2", "Bye $prefix")
    )

    private val running get() = tracker.display.value as? IndexingDisplay.Running
    private val paused get() = tracker.display.value as? IndexingDisplay.Paused

    /** The messages whose every passage's vector is made or failed, worked out from the rows directly. */
    private fun finishedMessages(): Int = index.rows.groupBy { it.chatId to it.messageId }.count { (_, rows) ->
        rows.all { index.state(it.key) in setOf(ConversationIndexStore.STATE_MADE, ConversationIndexStore.STATE_FAILED) }
    }

    // --- When the icon shows -------------------------------------------------

    @Test fun turningIndexingOnShowsCountingThenTheRealPercentageAndEndsWhenEverythingIsDone() {
        chats.put("a", shortChat("a"))
        chats.put("b", shortChat("b"))
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)

        coordinator.turnedOn()
        runner.runOne()
        assertEquals("the catch-up found work and counts first", IndexingDisplay.Running(null), tracker.display.value)

        runner.runUntil { running?.numbers != null }
        assertEquals(IndexingNumbers(percent = 0, indexedChats = 0, totalChats = 2), running?.numbers)

        runner.runAll()
        assertEquals(6, vectors.inputs.size)
        assertEquals(
            listOf(IndexingDisplay.Hidden, IndexingDisplay.Running(null), IndexingDisplay.Running(IndexingNumbers(0, 0, 2)),
                IndexingDisplay.Hidden),
            shown
        )
    }

    @Test fun theIconNeverAppearsWhenEverythingIsAlreadyIndexedAtStart() {
        chats.put("a", shortChat("a"))
        coordinator.turnedOn()
        runner.runAll()
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)

        // A restart with nothing left to do: no chat is read and the icon never shows.
        shown.clear()
        chats.reads.clear()
        start()
        coordinator.appStarted()
        runner.runAll()
        assertEquals(listOf<IndexingDisplay>(IndexingDisplay.Hidden), shown)
        assertTrue(chats.reads.isEmpty())
    }

    @Test fun anOrdinaryNewMessageIsIndexedWithoutShowingTheIcon() {
        chats.put("a", shortChat("a"))
        coordinator.turnedOn()
        runner.runAll()
        shown.clear()
        record()

        chats.put("a", shortChat("a") + user("a-u3", "One more"))
        coordinator.chatChanged("a")
        runner.runAll()
        assertEquals(listOf<IndexingDisplay>(IndexingDisplay.Hidden), shown)
        assertTrue(index.chatProgress("a").fullyIndexed)
        assertTrue(vectors.inputs.any { "One more" in it })
    }

    // --- The percentage ------------------------------------------------------

    @Test fun thePercentageIsMessagesActuallyDoneWithAFailedVectorProcessedAndANeverOpenedChatLeftOut() {
        vectors.tokenLimit = 10
        // "b" has a message too long for the model: its vector fails for good.
        chats.put("a", shortChat("a"))
        chats.put("b", listOf(user("b-u1", "Hi b"), reply("b-a1", (1..12).joinToString(" ") { "word$it" })))
        chats.put("never", shortChat("never"))
        chats.withoutIds.add("never")
        chats.put("empty", listOf(reply("empty-a1", "Hidden from the AI", hidden = true)))

        coordinator.turnedOn()
        runner.runUntil { running?.numbers != null }
        // Five messages in two chats; the never-opened and empty chats are in neither number.
        assertEquals(IndexingNumbers(0, 0, 2), running?.numbers)
        assertEquals("each chat is read once to count it", listOf("empty", "never", "b", "a"), chats.reads)

        // Stop after the first vector is read: the percentage is exactly the messages finished.
        vectors.afterRead = { tracker.pause() }
        runner.runAll()
        val afterOne = paused?.numbers
        assertNotNull(afterOne)
        val finished = finishedMessages()
        assertTrue(finished in 1..4)
        assertEquals(finished * 100 / 5, afterOne!!.percent)

        vectors.afterRead = {}
        tracker.resume()
        coordinator.resumed()
        runner.runUntil { running?.numbers != null }
        runner.runAll()
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
        assertEquals(1, index.vectors.count { it.value.state == ConversationIndexStore.STATE_FAILED })

        // At the end every message is processed, the failed one included, but "b" is not indexed.
        val described = index.chatSummaries(MODEL, DIMENSIONS)
        val end = IndexingProgressMath.numbers(
            chats.chatIdsNewestFirst(), described, index.messageProgress(MODEL, DIMENSIONS), emptyMap(),
            chats::chatVersion, updater.settingsKey
        )
        assertEquals(IndexingNumbers(percent = 100, indexedChats = 1, totalChats = 2), end)
        assertEquals(
            "the same chats as the settings status counts",
            ConversationIndexCounting.count(chats.chatIdsNewestFirst(), described, chats::chatVersion, updater.settingsKey).indexed,
            end.indexedChats
        )
    }

    @Test fun theNumbersFollowTheIndexAndTheCountWithTheSameDefinitionAsTheSettingsStatus() {
        val key = updater.settingsKey
        val summary = { version: String, unidentified: Int, passages: Int, made: Int ->
            ChatIndexSummary("$version|$key", false, unidentified, passages, made, 0)
        }
        val summaries = mapOf(
            "done" to summary("1", 0, 2, 2),
            "failed" to summary("1", 0, 2, 1),
            "waiting" to summary("1", 0, 3, 1),
            "opening" to summary("1", 2, 0, 0),
            "stale" to summary("0", 0, 2, 2)
        )
        val messages = mapOf(
            "done" to MessageProgress(2, 2),
            "failed" to MessageProgress(2, 2),
            "waiting" to MessageProgress(3, 1),
            "stale" to MessageProgress(2, 2)
        )
        val counted = mapOf(
            "stale" to CountedChat("1", indexable = 4, unidentified = 0),
            "new" to CountedChat("1", indexable = 0, unidentified = 0)
        )
        val live = listOf("done", "failed", "waiting", "opening", "stale", "new", "uncounted")
        val numbers = IndexingProgressMath.numbers(live, summaries, messages, counted, { "1" }, key)
        // Messages: 2 + 2 + 3 + 4 (the stale chat as counted now) = 11; done: 2 + 2 + 1 = 5.
        assertEquals(IndexingNumbers(percent = 45, indexedChats = 1, totalChats = 4), numbers)
        assertEquals(ConversationIndexCounting.count(live, summaries, { "1" }, key).indexed, numbers.indexedChats)
    }

    @Test fun aChatThatCannotBeReadWhileCountingIsLeftOutAndTheCountStillFinishes() {
        chats.put("a", shortChat("a"))
        chats.put("broken", shortChat("broken"))
        chats.failing.add("broken")
        coordinator.turnedOn()
        runner.runUntil { running?.numbers != null }
        assertEquals(IndexingNumbers(percent = 0, indexedChats = 0, totalChats = 1), running?.numbers)
        assertTrue("broken" in chats.reads)
    }

    // --- Pause and resume ----------------------------------------------------

    @Test fun pausingWhileCountingStopsAtTheNextChatAndResumingCountsOnlyTheRest() {
        chats.put("a", shortChat("a"))
        chats.put("b", shortChat("b"))
        chats.put("c", shortChat("c"))
        coordinator.turnedOn()
        runner.runUntil { chats.reads.size == 1 }
        tracker.pause()
        assertEquals("paused before the count finished: no numbers", IndexingDisplay.Paused(null), tracker.display.value)
        assertNull(saved.value)

        runner.runAll()
        assertEquals("nothing more is read while paused", listOf("c"), chats.reads)
        assertTrue(index.rows.isEmpty())
        assertTrue(vectors.inputs.isEmpty())

        tracker.resume()
        coordinator.resumed()
        runner.runAll()
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
        // Counted once each ("c" before the pause) and compared once each.
        assertEquals(mapOf("a" to 2, "b" to 2, "c" to 2), chats.reads.groupingBy { it }.eachCount())
        assertEquals(9, vectors.inputs.size)
    }

    @Test fun pausingWhileComparingKeepsTheMessagesWrittenAndResumingDoesNotDoThemAgain() {
        chats.put("a", shortChat("a"))
        coordinator.turnedOn()
        runner.runUntil { running?.numbers != null }
        // Pause after the first message of "a" is written, between messages.
        var checks = 0
        chats.onVersionCheck = { if (++checks == 2) tracker.pause() }
        runner.runAll()
        chats.onVersionCheck = {}
        assertEquals(setOf("a-u1"), index.indexedMessages("a").keys)
        assertFalse(index.chatProgress("a").fullyIndexed)
        assertTrue(vectors.inputs.isEmpty())
        assertTrue(paused != null)

        val written = index.rows.toSet()
        tracker.resume()
        coordinator.resumed()
        runner.runAll()
        assertTrue("the message written before the pause is kept as it was", index.rows.containsAll(written))
        assertEquals(setOf("a-u1", "a-a1", "a-u2"), index.indexedMessages("a").keys)
        assertEquals(3, vectors.inputs.size)
        assertEquals(vectors.inputs.size, vectors.inputs.toSet().size)
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    @Test fun pausingDuringVectorsKeepsTheFinishedOnesAndResumingNeverReadsOneTwice() {
        chats.put("a", shortChat("a"))
        chats.put("b", shortChat("b"))
        coordinator.turnedOn()
        vectors.afterRead = { if (vectors.inputs.size == 2) tracker.pause() }
        runner.runAll()
        vectors.afterRead = {}
        assertEquals(2, vectors.inputs.size)
        assertEquals(2, index.vectors.count { it.value.state == ConversationIndexStore.STATE_MADE })
        assertEquals(IndexingDisplay.Paused(IndexingNumbers(percent = 33, indexedChats = 0, totalChats = 2)), tracker.display.value)

        tracker.resume()
        coordinator.resumed()
        runner.runAll()
        assertEquals(6, vectors.inputs.size)
        assertEquals(6, vectors.inputs.toSet().size)
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    @Test fun thePauseAndItsNumbersSurviveARestartAndNothingRunsUntilResume() {
        chats.put("a", shortChat("a"))
        chats.put("b", shortChat("b"))
        coordinator.turnedOn()
        vectors.afterRead = { if (vectors.inputs.size == 3) tracker.pause() }
        runner.runAll()
        vectors.afterRead = {}
        val kept = saved.value
        assertNotNull(kept)
        assertEquals(50, kept!!.percent)
        assertEquals(2, kept.totalChats)
        assertEquals(
            ConversationIndexCounting.count(chats.chatIdsNewestFirst(), index.chatSummaries(MODEL, DIMENSIONS),
                chats::chatVersion, updater.settingsKey).indexed,
            kept.indexedChats
        )
        assertEquals(IndexingDisplay.Paused(kept), tracker.display.value)

        // The app is closed and opened again: the same saved pause and numbers.
        chats.reads.clear()
        start()
        assertEquals(IndexingDisplay.Paused(kept), tracker.display.value)
        coordinator.appStarted()
        runner.runAll()
        assertTrue("nothing is read while paused", chats.reads.isEmpty())
        assertEquals(3, vectors.inputs.size)
        assertEquals(IndexingDisplay.Paused(kept), tracker.display.value)

        tracker.resume()
        assertNull(saved.value)
        coordinator.resumed()
        runner.runAll()
        assertEquals(6, vectors.inputs.size)
        assertEquals(6, vectors.inputs.toSet().size)
        assertTrue("chats already compared are not read again", chats.reads.isEmpty())
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    @Test fun pausedBeforeTheCountFinishedShowsNoNumbersAfterARestart() {
        chats.put("a", shortChat("a"))
        chats.put("b", shortChat("b"))
        coordinator.turnedOn()
        runner.runUntil { chats.reads.size == 1 }
        tracker.pause()
        start()
        assertEquals(IndexingDisplay.Paused(null), tracker.display.value)
    }

    @Test fun aChatDeletedOrRemovedByARestoreWhilePausedLeavesTheIndexAtOnce() {
        chats.put("a", shortChat("a"))
        chats.put("b", shortChat("b"))
        coordinator.turnedOn()
        runner.runAll()
        assertTrue(index.rows.any { it.chatId == "a" } && index.rows.any { it.chatId == "b" })

        chats.put("b", shortChat("b") + user("b-u3", "Later"))
        coordinator.chatChanged("b")
        tracker.pause()
        chats.chats.remove("a")
        coordinator.chatsDeleted(listOf("a"))
        runner.runAll()
        assertFalse(index.rows.any { it.chatId == "a" })
        assertFalse("the new message waits for Resume", index.indexedMessages("b").containsKey("b-u3"))

        chats.chats.remove("b")
        coordinator.chatSetReplaced()
        runner.runAll()
        assertTrue(index.rows.isEmpty())
        assertTrue(pause.paused)
    }

    // --- Conditions ----------------------------------------------------------

    @Test fun theModelBeingUnavailableOrIndexingOffHidesThePausedIconWithoutClearingThePause() {
        tracker.pause()
        assertEquals(IndexingDisplay.Paused(null), tracker.display.value)

        modelUnavailable = true
        publish()
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
        modelUnavailable = false
        publish()
        assertEquals("the model coming back keeps the user's pause", IndexingDisplay.Paused(null), tracker.display.value)

        settingOn = false
        publish()
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
        assertTrue(pause.paused)

        // Turning indexing on again, or deleting the index, clears the pause.
        settingOn = true
        tracker.reset()
        assertFalse(pause.paused)
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    @Test fun theDisplayRuleShowsTheIconOnlyInTheApprovedStates() {
        val numbers = IndexingNumbers(42, 128, 310)
        val on = IndexingConditions(settingOn = true, rebuilding = false, recoveryFailed = false, modelUnavailable = false)
        assertEquals(IndexingDisplay.Running(numbers), IndexingDisplayRule.display(on, false, true, numbers))
        assertEquals(IndexingDisplay.Paused(numbers), IndexingDisplayRule.display(on, true, true, numbers))
        assertEquals(IndexingDisplay.Paused(null), IndexingDisplayRule.display(on, true, false, null))
        assertEquals(IndexingDisplay.Hidden, IndexingDisplayRule.display(on, false, false, numbers))
        for (hiding in listOf(
            on.copy(settingOn = false), on.copy(rebuilding = true), on.copy(recoveryFailed = true), on.copy(modelUnavailable = true)
        )) {
            assertEquals(IndexingDisplay.Hidden, IndexingDisplayRule.display(hiding, false, true, numbers))
            assertEquals(IndexingDisplay.Hidden, IndexingDisplayRule.display(hiding, true, true, numbers))
        }
    }

    // --- Changes during waits ------------------------------------------------

    @Test fun numbersReadForAnEarlierCatchUpAreThrownAway() {
        val own = Runner()
        var version = "1"
        var duringRead: () -> Unit = {}
        lateinit var progress: IndexProgressTracker
        val fake = object : IndexProgressSource {
            override fun work(chats: List<String>, settingsKey: String) = IndexWork(mapOf("x" to version), 0)
            override fun numbers(counted: Map<String, CountedChat>): IndexingNumbers {
                duringRead()
                return IndexingNumbers(10, 0, 1)
            }
            override fun conditions() = IndexingConditions(true, false, false, false)
        }
        progress = IndexProgressTracker(fake, Pause(), Saved(), own)
        assertEquals(listOf("x"), progress.catchUpStarted(listOf("x"), "key"))
        // The chat changes and a new catch-up starts while the numbers are being read.
        duringRead = {
            duringRead = {}
            version = "2"
            assertEquals(listOf("x"), progress.catchUpStarted(listOf("x"), "key"))
        }
        progress.chatRead("x", "1", listOf(user("x-u1", "Hello")))
        own.runAll()
        assertEquals("the new catch-up counts first; the older numbers are not shown",
            IndexingDisplay.Running(null), progress.display.value)

        progress.chatRead("x", "2", listOf(user("x-u1", "Hello")))
        own.runAll()
        assertEquals(IndexingDisplay.Running(IndexingNumbers(10, 0, 1)), progress.display.value)
    }

    @Test fun aVectorPassThatStartedBeforeEveryChatWasHandledDoesNotEndTheCatchUp() {
        chats.put("a", shortChat("a"))
        coordinator.turnedOn()
        runner.runUntil { running?.numbers != null }
        val early = tracker.vectorPassStarting()
        assertNull(early)
        tracker.vectorPassFinished(early)
        assertNotNull(running)
        runner.runAll()
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    private companion object {
        const val MODEL = "progress-model"
        const val DIMENSIONS = 4
        const val OVERLAP = 5
        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
