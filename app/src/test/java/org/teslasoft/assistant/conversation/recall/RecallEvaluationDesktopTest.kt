/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.teslasoft.assistant.conversation.index.ChatIndexState
import org.teslasoft.assistant.conversation.index.ConversationIndexStorage
import org.teslasoft.assistant.conversation.index.ConversationIndexUpdater
import org.teslasoft.assistant.conversation.index.IndexFingerprint
import org.teslasoft.assistant.conversation.index.IndexSourceMessage
import org.teslasoft.assistant.conversation.index.IndexWrite
import org.teslasoft.assistant.conversation.index.MessageEntries
import org.teslasoft.assistant.conversation.index.MessageStamp
import org.teslasoft.assistant.conversation.index.SearchTextReader
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy
import org.teslasoft.assistant.conversation.recall.eval.RecallEvalBackend
import org.teslasoft.assistant.conversation.recall.eval.RecallEvaluation
import org.teslasoft.assistant.conversation.recall.eval.RecallFixtures
import org.teslasoft.assistant.conversation.recall.eval.RecallTuningCandidates
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.HfTokenizer

/**
 * Development run of the recall evaluation on a desktop, never on CI and never
 * the recorded result (the recorded result is the emulator run with Android's
 * ONNX Runtime, `RecallEvaluationInstrumentedTest`). The index is built by the
 * real updater, cutter and search text with the real tokenizer; vectors come
 * from a file made by desktop ONNX Runtime from the token IDs this test writes.
 *
 *     RECALL_EVAL_DIR=/dir/with/tokenizer.json ./gradlew testDebugUnitTest --tests '*RecallEvaluationDesktopTest'
 *
 * The first run writes `inputs.json` (token IDs); a desktop model run writes
 * `vectors.json`; the next run writes `report-desktop.json`.
 */
class RecallEvaluationDesktopTest {

    @Test fun evaluate() {
        val dir = System.getenv("RECALL_EVAL_DIR")?.let(::File)
        assumeTrue("RECALL_EVAL_DIR not set", dir != null && File(dir, "tokenizer.json").isFile)
        val tokenizer = HfTokenizer.load(File(dir!!, "tokenizer.json"))
        val model = EmbeddingModels.GEMMA_Q4
        val vectorsFile = File(dir, "vectors.json")
        val known = if (vectorsFile.isFile) JSONObject(vectorsFile.readText()) else JSONObject()
        val backend = MemoryBackend(model, tokenizer, known)
        val res = File("src/test/resources/recall_test_set")
        val fixtures = RecallFixtures.load(File(res, "conversations.json").readText(),
            "additions" to File(res, "step14_additions.json").readText(), "holdout" to File(res, "step14_holdout.json").readText())
        val evaluation = RecallEvaluation(fixtures, backend)
        evaluation.buildIndex()
        val questions = evaluation.questions()
        val followUps = evaluation.followUps()
        if (backend.missing.length() > 0) {
            File(dir, "inputs.json").writeText(backend.missing.toString())
            println("RECALL_EVAL: ${backend.missing.length()} inputs need vectors; wrote inputs.json")
            return
        }
        val report = JSONObject()
            .put("backend", backend.name)
            .put("totals", RecallEvaluation.totals(questions + followUps))
            .put("questions", JSONArray(questions.map { it.json() }))
            .put("follow_ups", JSONArray(followUps.map { it.json() }))
        File(dir, "report-desktop.json").writeText(report.toString(1))
        println("RECALL_EVAL: wrote report-desktop.json")

        // Candidate values compared side by side (development only; the recorded comparison is the emulator run).
        val sweep = JSONArray()
        for ((name, tuning) in RecallTuningCandidates.all) {
            val e = RecallEvaluation(fixtures, backend, tuning)
            val rows = e.questions().filter { it.variant == "A" } + e.followUps()
            sweep.put(JSONObject().put("tuning", name).put("totals", RecallEvaluation.totals(rows)))
        }
        File(dir, "sweep-desktop.json").writeText(sweep.toString(1))
    }

    /** In-memory index stores written by the real updater; vectors looked up by exact model input. */
    private class MemoryBackend(
        private val model: EmbeddingModels.Model,
        private val tokenizer: HfTokenizer,
        private val known: JSONObject
    ) : RecallEvalBackend, ConversationIndexStorage {
        override val name = "desktop ONNX Runtime (development only)"
        override val vectorModel = model.embeddingTag
        override val vectorDimensions = model.dimensions
        val missing = JSONObject()
        private val reader = SearchTextReader.forEmbeddingModel(model, tokenizer)
        private val chats = HashMap<String, List<IndexSourceMessage>>()
        private val stored = HashMap<String, MutableMap<String, Pair<MessageStamp, MessageEntries?>>>()
        private val rows = HashMap<String, MutableList<Pair<RecallPassage, Pair<String, Set<String>>>>>()

        private fun vectorOf(kind: String, input: String, limit: Int): FloatArray? {
            val key = IndexFingerprint.of("$kind|$input")
            known.optJSONArray(key)?.let { a -> return FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            missing.put(key, JSONArray(tokenizer.encode(input, limit).toList()))
            return null
        }

        override fun index(indexChatId: String, messages: List<IndexSourceMessage>, titles: SpeakerTitlePolicy) {
            chats[indexChatId] = messages
            val outcome = ConversationIndexUpdater(this, reader, 256, 20, titles).update(indexChatId, "1", messages, { "1" })
            check(outcome is ConversationIndexUpdater.Outcome.Updated) { "indexing $indexChatId: $outcome" }
            var row = rows.values.sumOf { it.size }.toLong()
            val list = ArrayList<Pair<RecallPassage, Pair<String, Set<String>>>>()
            for ((id, entry) in stored[indexChatId].orEmpty()) {
                val e = entry.second ?: continue
                val s = entry.first
                for (p in e.passages) {
                    val identities = s.scope?.let { sc -> setOfNotNull(sc.companionPersonaId, sc.companionId, sc.roleplayCharacterId, sc.userPersonaId) }.orEmpty()
                    list.add(RecallPassage(++row, id, p.order, p.start, p.end, s.exchangeId, s.speaker, s.speakerTitle, s.timeMillis,
                        s.chatPosition, s.textFingerprint, s.vectorModel, s.vectorDimensions, identities) to (p.modelInput to p.keywords.split(' ').toSet()))
                }
            }
            rows[indexChatId] = list
        }

        override fun makeVectors() {
            for (list in rows.values) for ((_, data) in list) vectorOf("doc", data.first, model.storedTextMaxTokens)
        }

        override val recallIndex = object : RecallIndex {
            override fun passages(chatId: String) = rows[chatId].orEmpty().map { it.first }
            override fun rowsWithTerm(chatId: String, term: String) =
                rows[chatId].orEmpty().filter { term in it.second.second }.map { it.first.rowId }.toSet()
            override fun vectors(chatId: String, vectorModel: String, vectorDimensions: Int, afterRowId: Long, limit: Int) =
                rows[chatId].orEmpty().filter { it.first.rowId > afterRowId }.sortedBy { it.first.rowId }.mapNotNull { (p, d) ->
                    known.optJSONArray(IndexFingerprint.of("doc|${d.first}"))?.let { a ->
                        RecallIndex.RowVector(p.rowId, FloatArray(a.length()) { a.getDouble(it).toFloat() })
                    }
                }.take(limit)
        }

        override val messages = object : RecallMessageSource {
            override fun read(chatId: String) = chats[chatId]?.let { ChatSnapshot(it, "1") }
            override fun changedSince(chatId: String, snapshot: ChatSnapshot) = false
        }

        override fun queryVector(text: String): Pair<FloatArray?, Long> =
            vectorOf("query", model.queryPrefix + text, model.maxTokens) to 0L

        // ConversationIndexStorage for the real updater.
        override fun indexedMessages(chatId: String) =
            stored[chatId].orEmpty().filterValues { it.second != null }.mapValues { it.value.first }
        override fun indexState(chatId: String): ChatIndexState? = null
        override fun beginUpdate(chatId: String) = Unit
        override fun writeMessage(chatId: String, messageId: String, expected: MessageStamp?, replacement: MessageEntries?, chatUnchanged: () -> Boolean): IndexWrite {
            val map = stored.getOrPut(chatId) { LinkedHashMap() }
            if (replacement == null) map.remove(messageId) else map[messageId] = replacement.stamp to replacement
            return IndexWrite.WRITTEN
        }
        override fun restampMessage(chatId: String, messageId: String, expected: MessageStamp, stamp: MessageStamp, chatUnchanged: () -> Boolean): IndexWrite {
            val map = stored.getOrPut(chatId) { LinkedHashMap() }
            map[messageId] = stamp to map[messageId]?.second
            return IndexWrite.WRITTEN
        }
        override fun finishUpdate(chatId: String, chatVersion: String, expected: Map<String, MessageStamp>, unidentifiedMessages: Int, chatUnchanged: () -> Boolean) =
            IndexWrite.WRITTEN
    }
}
