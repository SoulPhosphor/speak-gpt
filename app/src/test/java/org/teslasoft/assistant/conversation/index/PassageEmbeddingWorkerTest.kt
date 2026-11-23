package org.teslasoft.assistant.conversation.index

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.COMPANION
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.HUMAN
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModel
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingQueue
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException

/**
 * Step 9 (memory development plan G.5a rules 2, 3, 4 and 6) for the worker's
 * decisions, against an in-memory index that keeps the encrypted store's
 * transaction contract: every write checks its conditions inside the write
 * and rolls back completely when the message changed before commit. The
 * encrypted store, the real model and these cases end to end are covered by
 * `PassageEmbeddingInstrumentedTest`; the real tokenizer by
 * `PassageEmbeddingGemmaTest`.
 */
class PassageEmbeddingWorkerTest {

    private class Pause : EmbeddingPauseState { override var paused = false }

    /** A model that records every exact input it reads. */
    private open class RecordingVectors(
        override val vectorModel: String = MODEL,
        override val tokenLimit: Int = 400
    ) : PassageVectorReader {
        override val vectorDimensions = DIMENSIONS
        val inputs: MutableList<String> = Collections.synchronizedList(ArrayList())
        var beforeRead: ((String) -> Unit)? = null
        var failNext = false
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            val tokens = countTokens(modelInput)
            if (tokens > tokenLimit) throw InputTooLongException(tokens, tokenLimit)
            beforeRead?.invoke(modelInput)
            if (failNext) { failNext = false; throw IllegalStateException("model read failed") }
            inputs.add(modelInput)
            return FloatArray(DIMENSIONS) { (modelInput.hashCode() + it).toFloat() }
        }
    }

    private fun reader(model: String = MODEL) = SearchTextReader(
        vectorModel = model,
        vectorDimensions = DIMENSIONS,
        hardLimit = 400,
        prefixFor = { "title: $it | text: " },
        countModelInput = ::words
    )

    private val index = MemoryIndex()
    private val chats = MemoryChats()
    private val pause = Pause()

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String, hidden: Boolean = false) =
        IndexSourceMessage(id, true, text, hidden, MessageCompletionState.DONE, 200L, null)

    private fun long(word: String) = (1..120).joinToString(" ") { "$word$it" }

    private fun index(chatId: String, messages: List<IndexSourceMessage>, searchReader: SearchTextReader = reader()) {
        chats.put(chatId, messages)
        val version = chats.version(chatId)
        val outcome = ConversationIndexUpdater(index, searchReader, targetTokens = 40, overlapWords = 5)
            .update(chatId, version, messages, { chats.version(chatId) })
        assertTrue("$outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
    }

    private fun worker(vectors: PassageVectorReader, searchReader: SearchTextReader = reader()) =
        PassageEmbeddingWorker(index, chats, searchReader, vectors, pause, pageSize = 2)

    private fun finished(outcome: PassageEmbeddingWorker.Outcome): PassageEmbeddingWorker.Outcome.Finished {
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Finished)
        return outcome as PassageEmbeddingWorker.Outcome.Finished
    }

    private fun chat() = listOf(
        user("u1", "I moved to Leeds."),
        reply("a1", long("leeds")),
        user("u2", "Tell me about bicycles.")
    )

    @Test fun waitingPassagesGetVectorsFromTheirCompleteRebuiltInputAndProgressCompletes() {
        index("chat", chat())
        val waitingBefore = index.chatProgress("chat")
        assertTrue(waitingBefore.passages > 3)
        assertEquals(waitingBefore.passages, waitingBefore.waiting)
        assertFalse(waitingBefore.fullyIndexed)

        val vectors = RecordingVectors()
        val outcome = finished(worker(vectors).runPass())

        val progress = index.chatProgress("chat")
        assertEquals(ChatIndexProgress(waitingBefore.passages, waitingBefore.passages, 0, 0, false, true), progress)
        assertEquals(PassageEmbeddingWorker.Counts(index.vectors.size, 0, 0), outcome.counts)
        assertEquals(0, index.states.getValue("chat").waitingVectors)
        // Each read is the whole model input: the recorded title and the full
        // search text, rebuilt from the message, with nothing cut off.
        val expected = chat().flatMap { message ->
            val title = if (message.isBot) COMPANION else HUMAN
            PassageSearchText.build(message.modelText, title, reader(), 40, 5).passages.map { it.modelInput }
        }
        assertEquals(expected.toSet(), vectors.inputs.toSet())
        assertEquals(expected.size, vectors.inputs.size)
        for (input in vectors.inputs) {
            assertEquals(ConversationIndexStore.STATE_MADE, index.state(VectorKey(IndexFingerprint.of(input), MODEL, DIMENSIONS)))
        }
        assertTrue(vectors.inputs.any { it.startsWith("title: Companion | text: ") && it.endsWith("leeds120") })

        // A second pass has nothing to read.
        finished(worker(vectors).runPass())
        assertEquals(expected.size, vectors.inputs.size)
    }

    @Test fun identicalInputIsReadOnceAndSharedAcrossChats() {
        index("one", listOf(user("u1", "The red kite circled the valley.")))
        index("two", listOf(user("x9", "The red kite circled the valley.")))
        assertEquals(1, index.vectors.size)
        val vectors = RecordingVectors()
        finished(worker(vectors).runPass())
        assertEquals(1, vectors.inputs.size)
        assertTrue(index.chatProgress("one").fullyIndexed)
        assertTrue(index.chatProgress("two").fullyIndexed)
    }

    @Test fun differentTitlesAndDifferentModelsNeverShareAVector() {
        index("chat", listOf(user("u1", "Same words here."), reply("a1", "Same words here.")))
        assertEquals(2, index.vectors.size)
        val vectors = RecordingVectors()
        finished(worker(vectors).runPass())
        assertEquals(
            setOf("title: Human | text: Same words here.", "title: Companion | text: Same words here."),
            vectors.inputs.toSet()
        )

        // The same text under another model is its own row and its own read.
        index("chat", listOf(user("u1", "Same words here."), reply("a1", "Same words here.")), reader(OTHER_MODEL))
        assertEquals(4, index.vectors.size)
        assertEquals(setOf(MODEL, OTHER_MODEL), index.vectors.keys.map { it.vectorModel }.toSet())
        val other = RecordingVectors(OTHER_MODEL)
        finished(worker(other, reader(OTHER_MODEL)).runPass())
        assertEquals(vectors.inputs.toSet(), other.inputs.toSet())
        assertEquals(2, other.inputs.size)
    }

    @Test fun anInputThatDoesNotFitIsRecordedAsFailedNeverReadAndNeverRetried() {
        index("chat", listOf(user("u1", "short one"), reply("a1", long("wide"))))
        // The model's own count says the long reply's passages do not fit.
        val vectors = object : RecordingVectors(tokenLimit = 20) {}
        val outcome = finished(worker(vectors).runPass())
        assertTrue(outcome.counts.failed > 0)
        assertEquals(listOf("title: Human | text: short one"), vectors.inputs)
        val progress = index.chatProgress("chat")
        assertEquals(outcome.counts.failed, progress.failed)
        assertEquals(0, progress.waiting)
        assertTrue(index.vectors.values.none { it.state == ConversationIndexStore.STATE_FAILED && it.blob != null })

        val again = finished(worker(vectors).runPass())
        assertEquals(PassageEmbeddingWorker.Counts(0, 0, 0), again.counts)
        assertEquals(1, vectors.inputs.size)
    }

    @Test fun aModelThatRefusesAnInputAsTooLongRecordsItFailedWithoutCuttingIt() {
        index("chat", listOf(user("u1", "four words right here")))
        val vectors = object : RecordingVectors() {
            override fun countTokens(modelInput: String) = 1
            override fun read(modelInput: String): FloatArray = throw InputTooLongException(9, 1)
        }
        val outcome = finished(worker(vectors).runPass())
        assertEquals(PassageEmbeddingWorker.Counts(0, 1, 0), outcome.counts)
        assertEquals(ConversationIndexStore.STATE_FAILED, index.vectors.values.single().state)
    }

    @Test fun anEditDuringTheReadThrowsTheVectorAwayAndMarksTheChatForRefresh() {
        index("chat", listOf(user("u1", "I adopted a cat named Pepper.")))
        val key = index.vectors.keys.single()
        val vectors = RecordingVectors()
        vectors.beforeRead = { chats.put("chat", listOf(user("u1", "I adopted a dog named Pepper."))) }
        val outcome = finished(worker(vectors).runPass())
        assertEquals(PassageEmbeddingWorker.Counts(0, 0, 1), outcome.counts)
        assertEquals(ConversationIndexStore.STATE_WAITING, index.state(key))
        assertTrue(index.chatProgress("chat").needsRefresh)
        assertFalse(index.chatProgress("chat").fullyIndexed)
    }

    @Test fun aDeletionDuringTheReadThrowsTheVectorAway() {
        assertChangeDuringReadIsDiscarded { chats.put("chat", emptyList()) }
    }

    @Test fun hidingTheMessageDuringTheReadThrowsTheVectorAway() {
        assertChangeDuringReadIsDiscarded {
            chats.put("chat", listOf(reply("a1", "Pepper sleeps on the piano.", hidden = true)))
        }
    }

    private fun assertChangeDuringReadIsDiscarded(change: () -> Unit) {
        index("chat", listOf(reply("a1", "Pepper sleeps on the piano.")))
        val vectors = RecordingVectors()
        vectors.beforeRead = { change() }
        val outcome = finished(worker(vectors).runPass())
        assertEquals(PassageEmbeddingWorker.Counts(0, 0, 1), outcome.counts)
        assertEquals(ConversationIndexStore.STATE_WAITING, index.vectors.values.single().state)
        assertNull(index.vectors.values.single().blob)
        assertTrue(index.chatProgress("chat").needsRefresh)
    }

    @Test fun aChangeJustBeforeCommitRollsTheVectorWriteBack() {
        index("chat", listOf(user("u1", "The lighthouse keeper paints shells.")))
        index.duringVectorWrite = { chats.put("chat", listOf(user("u1", "Edited while the vector was being saved."))) }
        val outcome = finished(worker(RecordingVectors()).runPass())
        assertEquals(0, outcome.counts.made)
        assertEquals(ConversationIndexStore.STATE_WAITING, index.vectors.values.single().state)
        assertEquals(1, index.states.getValue("chat").waitingVectors)
    }

    @Test fun aPassageReplacedDuringTheReadLeavesNoVectorForTheOldText() {
        index("chat", listOf(user("u1", "Original words about the harbour.")))
        val oldKey = index.vectors.keys.single()
        val vectors = RecordingVectors()
        vectors.beforeRead = {
            vectors.beforeRead = null
            index("chat", listOf(user("u1", "Replacement words about the meadow.")))
        }
        worker(vectors).runPass()
        // The old text was read, but its row went with its passage and nothing was stored for it.
        assertTrue(vectors.inputs.any { "harbour" in it })
        assertNull(index.state(oldKey))
        assertTrue(index.vectors.keys.all { key -> index.rows.any { it.key == key } })

        finished(worker(vectors).runPass())
        val newKey = index.vectors.keys.single()
        assertEquals(ConversationIndexStore.STATE_MADE, index.state(newKey))
        assertEquals(newKey, VectorKey(IndexFingerprint.of(vectors.inputs.last()), MODEL, DIMENSIONS))
        assertTrue(index.chatProgress("chat").fullyIndexed)
    }

    @Test fun aTemporaryFailureStopsThePassAndLeavesTheWorkWaiting() {
        index("chat", chat())
        val total = index.vectors.size
        val vectors = RecordingVectors()
        vectors.failNext = true
        val outcome = worker(vectors).runPass()
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Interrupted)
        assertTrue(index.vectors.values.all { it.state == ConversationIndexStore.STATE_WAITING })
        assertEquals(total, index.chatProgress("chat").waiting)

        finished(worker(vectors).runPass())
        assertEquals(total, vectors.inputs.size)
        assertEquals(0, index.chatProgress("chat").failed)
    }

    @Test fun pauseIsKeptAcrossANewWorkerAndResumingReadsOnlyWhatIsLeft() {
        index("chat", chat())
        val total = index.vectors.size
        val vectors = RecordingVectors()
        val first = worker(vectors)
        first.pause()
        assertTrue(first.runPass() is PassageEmbeddingWorker.Outcome.Paused)
        assertTrue(vectors.inputs.isEmpty())

        // As after the app is closed and reopened: a new worker sees the saved pause.
        first.resume()
        vectors.beforeRead = { if (vectors.inputs.size == 1) pause.paused = true }
        val paused = worker(vectors).runPass()
        assertTrue("$paused", paused is PassageEmbeddingWorker.Outcome.Paused)
        assertEquals(2, vectors.inputs.size)
        val reopened = worker(vectors)
        assertTrue(reopened.isPaused)
        assertTrue(reopened.runPass() is PassageEmbeddingWorker.Outcome.Paused)
        assertEquals(total - 2, index.chatProgress("chat").waiting)

        vectors.beforeRead = null
        reopened.resume()
        finished(reopened.runPass())
        assertEquals(total, vectors.inputs.size)
        assertEquals(total, vectors.inputs.toSet().size)
        assertTrue(index.chatProgress("chat").fullyIndexed)
    }

    @Test fun cancellingStopsBetweenVectors() {
        index("chat", chat())
        val vectors = RecordingVectors()
        val outcome = worker(vectors).runPass { vectors.inputs.size >= 1 }
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Cancelled)
        assertEquals(1, vectors.inputs.size)
    }

    @Test fun onePassRunsAtATime() {
        index("chat", listOf(user("u1", "First words."), user("u2", "Second words.")))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val vectors = RecordingVectors()
        vectors.beforeRead = { started.countDown(); release.await(10, TimeUnit.SECONDS) }
        var result: PassageEmbeddingWorker.Outcome? = null
        val running = thread { result = worker(vectors).runPass() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertEquals(PassageEmbeddingWorker.Outcome.AlreadyRunning, worker(vectors).runPass())
        assertFalse(worker(vectors).requestPass())
        release.countDown()
        running.join(10_000)
        finished(result!!)
    }

    @Test fun requestPassRunsOffTheCallingThread() {
        index("chat", listOf(user("u1", "Background words.")))
        val caller = Thread.currentThread()
        var readThread: Thread? = null
        val done = CountDownLatch(1)
        val vectors = RecordingVectors()
        vectors.beforeRead = { readThread = Thread.currentThread() }
        assertTrue(worker(vectors).requestPass { done.countDown() })
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNotNull(readThread)
        assertTrue(readThread !== caller)
        assertEquals("conversation-index-embedding", readThread!!.name)
    }

    @Test fun oldModelVectorsAreKeptUntilTheNewModelIsCompleteThenRemoved() {
        index("one", listOf(user("u1", "Alpha words.")))
        index("two", listOf(user("u2", "Beta words.")))
        finished(worker(RecordingVectors()).runPass())
        val oldKeys = index.vectors.keys.toSet()

        // Only one chat has been re-cut for the new model: the old vectors stay.
        index("one", listOf(user("u1", "Alpha words.")), reader(OTHER_MODEL))
        val other = RecordingVectors(OTHER_MODEL)
        assertEquals(0, finished(worker(other, reader(OTHER_MODEL)).runPass()).obsoleteRemoved)
        assertTrue(index.vectors.keys.containsAll(oldKeys))
        assertTrue(oldKeys.all { index.state(it) == ConversationIndexStore.STATE_MADE })

        // An interrupted re-cut keeps them too.
        index.markNeedsRefresh("two")
        assertEquals(0, finished(worker(other, reader(OTHER_MODEL)).runPass()).obsoleteRemoved)
        assertTrue(index.vectors.keys.containsAll(oldKeys))

        index("two", listOf(user("u2", "Beta words.")), reader(OTHER_MODEL))
        assertEquals(2, finished(worker(other, reader(OTHER_MODEL)).runPass()).obsoleteRemoved)
        assertTrue(index.vectors.keys.none { it in oldKeys })
        assertTrue(index.chatProgress("one").fullyIndexed && index.chatProgress("two").fullyIndexed)
    }

    @Test fun passagesCutForAnotherReaderAreNotReadButMarkedForRefresh() {
        index("chat", listOf(user("u1", "Cut with a different limit.")))
        val differentLimit = SearchTextReader(MODEL, DIMENSIONS, 300, { "title: $it | text: " }, ::words)
        val vectors = RecordingVectors()
        val outcome = finished(worker(vectors, differentLimit).runPass())
        assertEquals(PassageEmbeddingWorker.Counts(0, 0, 1), outcome.counts)
        assertTrue(vectors.inputs.isEmpty())
        assertTrue(index.chatProgress("chat").needsRefresh)
    }

    @Test fun searchSettingsRoundTrip() {
        val settings = SearchSettings(256, 0, "model|256|2048|abc")
        assertEquals(settings, SearchSettings.decode(settings.encoded))
        assertNull(SearchSettings.decode("cut-tx|o1|k"))
        assertNull(SearchSettings.decode("something else"))
    }

    /**
     * G.5a rule 6 through the app's real queue: a live read that arrives
     * while the worker's background read runs waits for that one read only,
     * not for the rest of the waiting passages.
     */
    @Test fun aLiveReadWaitsForAtMostOneRunningBackgroundRead() {
        index("chat", chat())
        val total = index.vectors.size
        assertTrue(total >= 3)
        val queue = EmbeddingQueue()
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val model = object : EmbeddingModel {
            override val tag = MODEL
            override val dimensions = DIMENSIONS
            override val messageTokenLimit = 400
            override val storedTextTokenLimit = 400
            override fun countTokens(text: String, isQuery: Boolean) = words(text)
            override fun countExactInputTokens(modelInput: String) = words(modelInput)
            override fun embed(text: String, isQuery: Boolean, tokenLimit: Int, control: EmbeddingQueue.ReadControl?): FloatArray {
                events.add("live")
                return FloatArray(DIMENSIONS)
            }
            override fun embedExactInput(modelInput: String, tokenLimit: Int, control: EmbeddingQueue.ReadControl?): FloatArray {
                // This model cannot stop a read, so the live read must wait for it.
                val first = events.none { it == "background" }
                events.add("background")
                if (first) { firstStarted.countDown(); releaseFirst.await(10, TimeUnit.SECONDS) }
                return FloatArray(DIMENSIONS) { 1f }
            }
            override fun close() {}
        }
        var result: PassageEmbeddingWorker.Outcome? = null
        val background = thread { result = worker(QueuedPassageVectorReader(queue, model)).runPass() }
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS))
        val live = thread { queue.run(EmbeddingQueue.Priority.LIVE) { model.embed("hello", true, 400, it) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (queue.waitingCount() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        releaseFirst.countDown()
        live.join(10_000)
        background.join(10_000)
        finished(result!!)
        assertEquals("background", events[0])
        assertEquals("live", events[1])
        assertEquals(total, events.count { it == "background" })
        assertTrue(index.chatProgress("chat").fullyIndexed)
    }

    private companion object {
        const val MODEL = "test-model-8"
        const val OTHER_MODEL = "other-model-8"
        const val DIMENSIONS = 8

        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
