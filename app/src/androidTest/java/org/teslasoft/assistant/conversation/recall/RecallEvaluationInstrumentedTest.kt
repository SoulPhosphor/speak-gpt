/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.conversation.index.ConversationIndexStore
import org.teslasoft.assistant.conversation.index.ConversationIndexUpdater
import org.teslasoft.assistant.conversation.index.EmbeddingPauseState
import org.teslasoft.assistant.conversation.index.IndexMessageSource
import org.teslasoft.assistant.conversation.index.IndexSourceMessage
import org.teslasoft.assistant.conversation.index.PassageEmbeddingWorker
import org.teslasoft.assistant.conversation.index.QueuedPassageVectorReader
import org.teslasoft.assistant.conversation.index.SearchTextReader
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy
import org.teslasoft.assistant.conversation.index.StoreRecallIndex
import org.teslasoft.assistant.conversation.recall.eval.RecallEvalBackend
import org.teslasoft.assistant.conversation.recall.eval.RecallEvaluation
import org.teslasoft.assistant.conversation.recall.eval.RecallFixtures
import org.teslasoft.assistant.conversation.recall.eval.RecallTuningCandidates
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModel
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingQueue
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import org.teslasoft.assistant.preferences.memory.librarian.OnnxEmbeddingModel

/**
 * Step 14's evaluation on Android (memory development plan G.16 Step 14):
 * the chats in [CHATS] are indexed through the real updater, the real
 * encrypted index and the real EmbeddingGemma model with Android's ONNX
 * Runtime, and each of their questions is scored on its own, under every
 * speaker-title variant and follow-up mode. The emulator takes seconds for each
 * text, so every chat (about 730 texts) does not fit in a CI run;
 * the full evaluation is recorded from the same model files and tokenizer on a
 * desktop (`RecallEvaluationDesktopTest`), and this subset shows whether
 * Android's runtime changes any decision. The report is written to
 * `recall-step14-report.json` in the app's external files folder, which
 * Android Checks collects. Quality is measured, not asserted (G.15: no pass
 * mark is invented in advance); only the rules that must always hold are
 * asserted.
 *
 * Emulator timings are not phone timings.
 */
@RunWith(AndroidJUnit4::class)
class RecallEvaluationInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "recall_evaluation_${System.nanoTime()}.db"
    private lateinit var store: ConversationIndexStore
    private lateinit var runtimeFiles: File
    private lateinit var model: EmbeddingModel

    @Before fun open() {
        store = ConversationIndexStore.openForTest(context, name, "recall-evaluation-key".toByteArray())
    }

    @After fun close() {
        if (::model.isInitialized) model.close()
        store.close()
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
        if (::runtimeFiles.isInitialized) runtimeFiles.deleteRecursively()
    }

    private fun asset(path: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(path).bufferedReader().use { it.readText() }

    @Test fun everyTestQuestionIsScoredWithTheRealModelAndTheRulesThatMustHoldDo() {
        runtimeFiles = File(context.filesDir, "recall-evaluation-${System.nanoTime()}").apply { mkdirs() }
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
        model = OnnxEmbeddingModel.create(runtime, catalog)
        val queue = EmbeddingQueue()
        val searchReader = SearchTextReader.forEmbeddingModel(catalog, EmbeddingTokenCounter.tokenizer(runtime, catalog))
        val backend = DeviceBackend(catalog, searchReader, queue)

        val fixtures = RecallFixtures.load(asset("recall_test_set/conversations.json"),
            "additions" to asset("recall_test_set/step14_additions.json"), "holdout" to asset("recall_test_set/step14_holdout.json"))
        val evaluation = RecallEvaluation(fixtures, backend, onlyChats = CHATS)
        val indexStarted = System.nanoTime()
        evaluation.buildIndex()
        val indexMillis = (System.nanoTime() - indexStarted) / 1_000_000
        val questions = evaluation.questions()
        val followUps = evaluation.followUps()
        val sweep = JSONArray()
        for ((label, tuning) in RecallTuningCandidates.all) {
            val e = RecallEvaluation(fixtures, backend, tuning, CHATS)
            val rows = e.questions().filter { it.variant == "A" } + e.followUps()
            sweep.put(JSONObject().put("tuning", label).put("totals", RecallEvaluation.totals(rows))
                .put("rows", JSONArray(rows.map { it.json() })))
        }
        val timing = evaluation.timing { text -> backend.timedRead(text) }

        val report = JSONObject()
            .put("backend", "Android ONNX Runtime on the CI emulator (not phone timings)")
            .put("chats", JSONArray(CHATS.sorted()))
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
            .put("index_and_vectors_ms", indexMillis)
            .put("passages_made", store.madeVectorCount())
            .put("totals", RecallEvaluation.totals(questions + followUps))
            .put("questions", JSONArray(questions.map { it.json() }))
            .put("follow_ups", JSONArray(followUps.map { it.json() }))
            .put("tunings", sweep)
            .put("timing", timing)
        File(context.getExternalFilesDir(null), "recall-step14-report.json").writeText(report.toString())

        // Rules that must hold, whatever the scores: a hidden message, an edited message's old
        // words and a failed or unfinished reply are never recalled (G.15 correctness; these
        // chats have none, and StoreRecallIndexInstrumentedTest covers them on the real index).
        assertTrue(questions.isNotEmpty())
        for (row in questions) {
            val q = fixtures.questions.first { it.id == row.id }
            if (q.situation in setOf("hidden_best_match", "edited_old_words", "failed_or_unfinished_reply")) {
                assertEquals("${row.variant} ${row.id}", emptyList<String>(), row.mustNotAdded)
            }
        }
        // Every passage got its vector, so nothing was scored while still waiting.
        assertTrue(questions.all { it.extra.optInt("passages_without_vector", 0) == 0 })
    }

    private inner class DeviceBackend(
        private val catalog: EmbeddingModels.Model,
        private val searchReader: SearchTextReader,
        private val queue: EmbeddingQueue
    ) : RecallEvalBackend {
        override val name = "android"
        override val vectorModel = catalog.embeddingTag
        override val vectorDimensions = catalog.dimensions
        private val chats = HashMap<String, List<IndexSourceMessage>>()
        private val cache = HashMap<String, FloatArray>()

        override fun index(indexChatId: String, messages: List<IndexSourceMessage>, titles: SpeakerTitlePolicy) {
            chats[indexChatId] = messages
            val outcome = ConversationIndexUpdater(store, searchReader, 256, 20, titles).update(indexChatId, "1", messages, { "1" })
            assertTrue("$indexChatId: $outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
        }

        override fun makeVectors() {
            val source = IndexMessageSource { chat, id -> chats[chat]?.firstOrNull { it.messageId == id } }
            val pause = object : EmbeddingPauseState { override var paused = false }
            val outcome = PassageEmbeddingWorker(store, source, searchReader, QueuedPassageVectorReader(queue, model), pause).runPass()
            assertTrue("$outcome", outcome is PassageEmbeddingWorker.Outcome.Finished)
        }

        override val recallIndex = StoreRecallIndex(store)

        override val messages = object : RecallMessageSource {
            override fun read(chatId: String) = chats[chatId]?.let { ChatSnapshot(it, "1") }
            override fun changedSince(chatId: String, snapshot: ChatSnapshot) = false
        }

        override fun queryVector(text: String): Pair<FloatArray?, Long> {
            cache[text]?.let { return it to 0L }
            val started = System.nanoTime()
            val v = queue.run(EmbeddingQueue.Priority.LIVE) { control -> model.embed(text, true, model.messageTokenLimit, control) }
            cache[text] = v
            return v to (System.nanoTime() - started) / 1_000_000
        }

        /** A fresh read of [text] through the live queue, in milliseconds. */
        fun timedRead(text: String): Long {
            val started = System.nanoTime()
            queue.run(EmbeddingQueue.Priority.LIVE) { control -> model.embed(text, true, model.messageTokenLimit, control) }
            return (System.nanoTime() - started) / 1_000_000
        }
    }

    private companion object {
        /**
         * Three short chats of about 870 words in all: the independent-check bakery
         * chat (details, unusual wording, changed decisions, a common word, nothing
         * relevant and its follow-ups) and two roleplay chats with two identities of
         * the same name. The emulator takes seconds for each text, so larger chats do
         * not fit in the class's time limit.
         */
        val CHATS = setOf("chat-holdout-bakery", "chat-holdout-ashes", "chat-two-finns")
    }
}
