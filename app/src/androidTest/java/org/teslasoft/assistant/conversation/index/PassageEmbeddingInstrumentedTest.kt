package org.teslasoft.assistant.conversation.index

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingQueue
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException
import org.teslasoft.assistant.preferences.memory.librarian.OnnxEmbeddingModel
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/**
 * Step 9 on a device (memory development plan G.5a rules 2, 3, 4 and 6):
 * background embedding against the encrypted index store, and with the real
 * EmbeddingGemma model through the app's embedding queue. The model files
 * are provisioned by Android Checks in `files/step2-model`; a missing file
 * fails the test rather than skipping it.
 */
@RunWith(AndroidJUnit4::class)
class PassageEmbeddingInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "passage_embedding_test_${System.nanoTime()}.db"
    private val key = "passage-embedding-test-key".toByteArray()
    private lateinit var store: ConversationIndexStore
    private val chats = HashMap<String, MutableMap<String, IndexSourceMessage>>()
    private val versions = HashMap<String, Int>()
    private val source = IndexMessageSource { chat, message -> chats[chat]?.get(message) }
    private val pause = object : EmbeddingPauseState { override var paused = false }

    private fun reader(model: String = MODEL) = SearchTextReader(
        vectorModel = model,
        vectorDimensions = DIMENSIONS,
        hardLimit = 400,
        prefixFor = { "title: $it | text: " },
        countModelInput = ::words
    )

    /** Records every exact input; [beforeRead] runs inside the read. */
    private open inner class Vectors(
        override val vectorModel: String = MODEL,
        override val tokenLimit: Int = 400
    ) : PassageVectorReader {
        override val vectorDimensions = DIMENSIONS
        val inputs = ArrayList<String>()
        var beforeRead: (() -> Unit)? = null
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            val tokens = countTokens(modelInput)
            if (tokens > tokenLimit) throw InputTooLongException(tokens, tokenLimit)
            beforeRead?.invoke()
            inputs.add(modelInput)
            return FloatArray(DIMENSIONS) { (it + inputs.size).toFloat() }
        }
    }

    @Before fun open() {
        store = ConversationIndexStore.openForTest(context, name, key)
    }

    @After fun close() {
        store.close()
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
    }

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String) =
        IndexSourceMessage(id, true, text, false, MessageCompletionState.DONE, 200L, null)

    private fun save(chatId: String, messages: List<IndexSourceMessage>, searchReader: SearchTextReader = reader()) {
        chats[chatId] = messages.associateBy { it.messageId }.toMutableMap()
        versions[chatId] = (versions[chatId] ?: 0) + 1
        val version = versions[chatId].toString()
        val outcome = ConversationIndexUpdater(store, searchReader, 40, 5)
            .update(chatId, version, messages, { versions[chatId].toString() })
        assertTrue("$outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
    }

    private fun worker(vectors: PassageVectorReader, searchReader: SearchTextReader = reader()) =
        PassageEmbeddingWorker(store, source, searchReader, vectors, pause, pageSize = 2)

    private fun finished(outcome: PassageEmbeddingWorker.Outcome): PassageEmbeddingWorker.Outcome.Finished {
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Finished)
        return outcome as PassageEmbeddingWorker.Outcome.Finished
    }

    private fun chat() = listOf(
        user("u1", "I moved to Leeds."),
        reply("a1", (1..150).joinToString(" ") { "harbour$it" }),
        user("u2", "Tell me about bicycles.")
    )

    private fun vectorRows(): Map<VectorKey, Pair<String, ByteArray?>> =
        store.readableDatabase.rawQuery(
            "SELECT search_fingerprint, vector_model, vector_dimensions, state, vector FROM passage_vectors", emptyArray()
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(
                    VectorKey(cursor.getString(0), cursor.getString(1), cursor.getInt(2)),
                    cursor.getString(3) to (if (cursor.isNull(4)) null else cursor.getBlob(4))
                )
            }
        }

    @Test fun waitingPassagesGetStoredVectorsAndTheChatBecomesFullyIndexed() {
        save("chat", chat())
        val passages = store.chatProgress("chat").passages
        assertEquals(passages, store.chatProgress("chat").waiting)
        assertEquals(passages, store.indexState("chat")!!.waitingVectors)

        val vectors = Vectors()
        val outcome = finished(worker(vectors).runPass())
        assertEquals(vectorRows().size, outcome.counts.made)
        assertEquals(ChatIndexProgress(passages, passages, 0, 0, false, true), store.chatProgress("chat"))
        val state = store.indexState("chat")!!
        assertEquals(0, state.waitingVectors)
        assertTrue(state.fullyIndexed)
        for (input in vectors.inputs) {
            val row = vectorRows().getValue(VectorKey(IndexFingerprint.of(input), MODEL, DIMENSIONS))
            assertEquals(ConversationIndexStore.STATE_MADE, row.first)
            assertEquals(DIMENSIONS * 4, row.second!!.size)
        }
    }

    @Test fun identicalInputIsReadOnceAndItsVectorGoesWithTheLastPassageUsingIt() {
        save("one", listOf(user("u1", "The red kite circled the valley.")))
        save("two", listOf(user("x1", "The red kite circled the valley.")))
        val vectors = Vectors()
        finished(worker(vectors).runPass())
        assertEquals(1, vectors.inputs.size)
        assertTrue(store.chatProgress("one").fullyIndexed && store.chatProgress("two").fullyIndexed)
        val shared = vectorRows().keys.single()

        save("one", listOf(user("u1", "Something else entirely.")))
        assertEquals(ConversationIndexStore.STATE_MADE, store.vectorState(shared.searchFingerprint, MODEL, DIMENSIONS))
        save("two", emptyList())
        assertNull(store.vectorState(shared.searchFingerprint, MODEL, DIMENSIONS))
    }

    @Test fun anInputThatDoesNotFitIsRecordedAsFailedWithNoVector() {
        save("chat", listOf(user("u1", "short one"), reply("a1", (1..120).joinToString(" ") { "wide$it" })))
        val vectors = Vectors(tokenLimit = 20)
        val outcome = finished(worker(vectors).runPass())
        assertTrue(outcome.counts.failed > 0)
        assertEquals(1, vectors.inputs.size)
        val progress = store.chatProgress("chat")
        assertEquals(outcome.counts.failed, progress.failed)
        assertEquals(0, progress.waiting)
        assertTrue(vectorRows().values.filter { it.first == ConversationIndexStore.STATE_FAILED }.all { it.second == null })
        assertEquals(PassageEmbeddingWorker.Counts(0, 0, 0), finished(worker(vectors).runPass()).counts)
    }

    @Test fun anEditJustBeforeCommitRollsTheVectorWriteBack() {
        save("chat", listOf(user("u1", "The lighthouse keeper paints shells.")))
        val row = vectorRows().keys.single()
        store.afterVectorWritten = { chats.getValue("chat")["u1"] = user("u1", "Edited while it was saved.") }
        val outcome = finished(worker(Vectors()).runPass())
        store.afterVectorWritten = null
        assertEquals(0, outcome.counts.made)
        assertEquals(ConversationIndexStore.STATE_WAITING to null, vectorRows().getValue(row).let { it.first to it.second })
        assertEquals(1, store.indexState("chat")!!.waitingVectors)
        assertTrue(store.chatProgress("chat").needsRefresh)
    }

    @Test fun anEditDuringTheReadKeepsTheVectorOutOfTheIndex() {
        save("chat", listOf(user("u1", "I adopted a cat named Pepper.")))
        val vectors = Vectors()
        vectors.beforeRead = { chats.getValue("chat")["u1"] = user("u1", "I adopted a dog named Pepper.") }
        finished(worker(vectors).runPass())
        assertEquals(ConversationIndexStore.STATE_WAITING, vectorRows().values.single().first)
        assertTrue(store.chatProgress("chat").needsRefresh)
    }

    @Test fun aWriteThatFailsPartWayLeavesTheWorkWaitingAndALaterPassFinishesIt() {
        save("chat", chat())
        val total = vectorRows().size
        store.afterVectorWritten = { throw IllegalStateException("power lost") }
        val outcome = worker(Vectors()).runPass()
        store.afterVectorWritten = null
        assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Interrupted)
        assertTrue(vectorRows().values.all { it.first == ConversationIndexStore.STATE_WAITING && it.second == null })
        assertEquals(store.chatProgress("chat").passages, store.indexState("chat")!!.waitingVectors)

        val vectors = Vectors()
        finished(worker(vectors).runPass())
        assertEquals(total, vectors.inputs.size)
        assertTrue(store.chatProgress("chat").fullyIndexed)
    }

    @Test fun closingAndReopeningKeepsFinishedVectorsAndResumesWithoutReadingThemAgain() {
        save("chat", chat())
        val total = vectorRows().size
        assertTrue(total >= 3)
        val first = Vectors()
        first.beforeRead = { if (first.inputs.size == 1) pause.paused = true }
        assertTrue(worker(first).runPass() is PassageEmbeddingWorker.Outcome.Paused)
        assertEquals(2, first.inputs.size)

        store.close()
        store = ConversationIndexStore.openForTest(context, name, key)
        assertEquals(2, vectorRows().values.count { it.first == ConversationIndexStore.STATE_MADE })
        assertEquals(total - 2, vectorRows().values.count { it.first == ConversationIndexStore.STATE_WAITING })
        assertTrue(worker(Vectors()).runPass() is PassageEmbeddingWorker.Outcome.Paused)

        pause.paused = false
        val second = Vectors()
        finished(worker(second).runPass())
        assertEquals(total - 2, second.inputs.size)
        assertTrue(second.inputs.none { it in first.inputs })
        assertTrue(store.chatProgress("chat").fullyIndexed)
    }

    @Test fun anUnfinishedModelChangeKeepsTheOldVectorsAndAFinishedOneRemovesThem() {
        save("one", listOf(user("u1", "Alpha words.")))
        save("two", listOf(user("u2", "Beta words.")))
        finished(worker(Vectors()).runPass())
        val old = vectorRows().filterValues { it.first == ConversationIndexStore.STATE_MADE }
        assertEquals(2, old.size)

        // One chat re-cut for the new model; the other still on the old one.
        save("one", listOf(user("u1", "Alpha words.")), reader(OTHER_MODEL))
        val other = Vectors(OTHER_MODEL)
        assertEquals(0, finished(worker(other, reader(OTHER_MODEL)).runPass()).obsoleteRemoved)
        for ((key, row) in old) assertArrayEquals(row.second, vectorRows().getValue(key).second)

        // A re-cut interrupted part-way keeps them as well.
        store.afterOldRowsRemoved = { throw IllegalStateException("power lost") }
        val interrupted = ConversationIndexUpdater(store, reader(OTHER_MODEL), 40, 5)
            .update("two", versions["two"].toString(), chats.getValue("two").values.toList(), { versions["two"].toString() })
        store.afterOldRowsRemoved = null
        assertTrue(interrupted is ConversationIndexUpdater.Outcome.Failed)
        assertEquals(0, finished(worker(other, reader(OTHER_MODEL)).runPass()).obsoleteRemoved)
        for ((key, row) in old) assertArrayEquals(row.second, vectorRows().getValue(key).second)

        // Switching back before the change finished reuses the kept vector.
        save("one", listOf(user("u1", "Alpha words.")))
        val back = Vectors()
        finished(worker(back).runPass())
        assertTrue(back.inputs.isEmpty())
        assertTrue(store.chatProgress("one").fullyIndexed)

        save("one", listOf(user("u1", "Alpha words.")), reader(OTHER_MODEL))
        save("two", listOf(user("u2", "Beta words.")), reader(OTHER_MODEL))
        val done = finished(worker(other, reader(OTHER_MODEL)).runPass())
        assertEquals(2, done.obsoleteRemoved)
        assertTrue(vectorRows().keys.all { it.vectorModel == OTHER_MODEL })
        assertTrue(store.chatProgress("one").fullyIndexed && store.chatProgress("two").fullyIndexed)
    }

    @Test fun theRealModelMakesTheVectorsThroughTheQueueAndNeverCutsAnInput() {
        val runtimeFiles = File(context.filesDir, "passage-embedding-${System.nanoTime()}").apply { mkdirs() }
        val runtime = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = runtimeFiles
        }
        val catalog = EmbeddingModels.GEMMA_Q4
        val installed = EmbeddingModelStorage.modelDir(runtime, catalog).apply { mkdirs() }
        val supplied = File(context.filesDir, "step2-model")
        for (file in listOf("model.onnx", "model_q4.onnx_data", "tokenizer.json")) {
            val fixture = File(supplied, file)
            assertTrue("Missing model fixture $fixture", fixture.isFile && fixture.canRead())
            fixture.copyTo(File(installed, file), overwrite = true)
        }
        val model = OnnxEmbeddingModel.create(runtime, catalog)
        try {
            val searchReader = SearchTextReader.forEmbeddingModel(catalog, EmbeddingTokenCounter.tokenizer(runtime, catalog))
            val vectors = QueuedPassageVectorReader(EmbeddingQueue(), model)
            val messages = listOf(user("u1", "I moved to Leeds last spring."), reply("a1", "Leeds suits you."))
            chats["chat"] = messages.associateBy { it.messageId }.toMutableMap()
            versions["chat"] = 1
            assertTrue(ConversationIndexUpdater(store, searchReader, 256, 0)
                .update("chat", "1", messages, { "1" }) is ConversationIndexUpdater.Outcome.Updated)
            val outcome = finished(PassageEmbeddingWorker(store, source, searchReader, vectors, pause).runPass())
            assertEquals(2, outcome.counts.made)
            assertTrue(store.chatProgress("chat").fullyIndexed)

            val input = searchReader.modelInput(SpeakerTitlePolicy.HUMAN, "I moved to Leeds last spring.")
            val stored = vectorRows().getValue(VectorKey(IndexFingerprint.of(input), catalog.embeddingTag, catalog.dimensions))
            assertArrayEquals(model.embedExactInput(input, catalog.storedTextMaxTokens), VectorMath.fromBlob(stored.second!!), 1e-6f)

            // The exact-input read is the saved-memory read when given the same input.
            val text = "My favourite morning drink is coffee."
            assertArrayEquals(
                model.embed(text, isQuery = false, tokenLimit = catalog.storedTextMaxTokens),
                model.embedExactInput(catalog.documentPrefix + text, catalog.storedTextMaxTokens),
                1e-6f
            )

            val tooLong = catalog.documentPrefix + "word ".repeat(3_000)
            assertTrue(model.countExactInputTokens(tooLong) > catalog.storedTextMaxTokens)
            try {
                vectors.read(tooLong)
                fail("an input over the limit was read")
            } catch (e: InputTooLongException) {
                assertEquals(catalog.storedTextMaxTokens, e.limit)
            }
            assertFalse(model.countExactInputTokens(input) > catalog.storedTextMaxTokens)
        } finally {
            model.close()
            runtimeFiles.deleteRecursively()
        }
    }

    private companion object {
        const val MODEL = "test-model-8"
        const val OTHER_MODEL = "other-model-8"
        const val DIMENSIONS = 8

        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
