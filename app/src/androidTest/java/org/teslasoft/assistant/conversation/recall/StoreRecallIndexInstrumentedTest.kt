/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.conversation.index.ConversationIndexAccess
import org.teslasoft.assistant.conversation.index.ConversationIndexStore
import org.teslasoft.assistant.conversation.index.ConversationIndexUpdater
import org.teslasoft.assistant.conversation.index.EmbeddingPauseState
import org.teslasoft.assistant.conversation.index.IndexMessageSource
import org.teslasoft.assistant.conversation.index.IndexSourceMessage
import org.teslasoft.assistant.conversation.index.PassageEmbeddingWorker
import org.teslasoft.assistant.conversation.index.PassageVectorReader
import org.teslasoft.assistant.conversation.index.SearchTextReader
import org.teslasoft.assistant.conversation.index.StoreRecallIndex
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/**
 * Recall reading the real encrypted index while Stage 9 changes it
 * (memory development plan G.8a point 1; CLAUDE.md "Changes during waits",
 * Step 14): edits, deletions, a restore's refresh and the index's deletion
 * that land during a search never let recall hand on outdated words.
 */
@RunWith(AndroidJUnit4::class)
class StoreRecallIndexInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "store_recall_index_${System.nanoTime()}.db"
    private lateinit var store: ConversationIndexStore
    private val chats = HashMap<String, MutableList<IndexSourceMessage>>()
    private var version = 0

    private val reader = SearchTextReader(MODEL, DIMS, 400, { "title: $it | text: " }, { it.split(Regex("\\s+")).size + 2 })

    /** Text about cedar points one way; everything else another, varied a little by its length. */
    private val vectors = object : PassageVectorReader {
        override val vectorModel = MODEL
        override val vectorDimensions = DIMS
        override val tokenLimit = 400
        override fun countTokens(modelInput: String) = modelInput.split(Regex("\\s+")).size + 2
        override fun read(modelInput: String): FloatArray =
            VectorMath.normalize(if ("cedar" in modelInput) floatArrayOf(1f, 0.1f, 0f, 0f)
            else floatArrayOf(0.05f * (modelInput.length % 5), 0f, 1f, 0.02f * (modelInput.length % 7)))
    }

    private val source = object : RecallMessageSource {
        override fun read(chatId: String) = chats[chatId]?.let { ChatSnapshot(it.toList(), it.hashCode().toString()) }
        override fun changedSince(chatId: String, snapshot: ChatSnapshot) = chats[chatId]?.hashCode()?.toString() != snapshot.version
    }

    @Before fun open() {
        store = ConversationIndexStore.openForTest(context, name, "store-recall-index-key".toByteArray())
    }

    @After fun close() {
        store.close()
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
    }

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String) = IndexSourceMessage(id, true, text, false, MessageCompletionState.DONE, 200L, null)

    private fun save(chatId: String, messages: List<IndexSourceMessage>) {
        chats[chatId] = messages.toMutableList()
        val v = (++version).toString()
        assertTrue(ConversationIndexUpdater(store, reader, 40, 5).update(chatId, v, messages, { v }) is ConversationIndexUpdater.Outcome.Updated)
    }

    private fun makeVectors() {
        val pause = object : EmbeddingPauseState { override var paused = false }
        val src = IndexMessageSource { c, id -> chats[c]?.firstOrNull { it.messageId == id } }
        assertTrue(PassageEmbeddingWorker(store, src, reader, vectors, pause).runPass() is PassageEmbeddingWorker.Outcome.Finished)
    }

    private fun garden(): List<IndexSourceMessage> {
        val list = ArrayList<IndexSourceMessage>()
        for (i in 0 until 10) { list += user("u$i", "a note about watering number $i"); list += reply("a$i", "watering reply $i") }
        list += user("cedar", "we will build the raised beds from untreated cedar")
        list += reply("cedar-a", "cedar resists rot without chemicals")
        return list
    }

    private fun request(text: String) = RecallRequest("garden", text, null, 0, ZoneOffset.UTC, MODEL, DIMS,
        SentInFull.Messages(emptySet()), messageVector = VectorMath.normalize(floatArrayOf(1f, 0.1f, 0f, 0f)))

    private fun engine(index: RecallIndex = StoreRecallIndex(store)) = RecallEngine(index, source, null)

    @Test fun theIndexIsReadWithoutTextAndOnlyMadeVectorsOfTheModelAreCompared() {
        save("garden", garden())
        save("other", listOf(user("o", "cedar in another chat")))
        val index = StoreRecallIndex(store)
        assertEquals(22, index.passages("garden").size)
        assertTrue(index.vectors("garden", MODEL, DIMS, Long.MIN_VALUE, 100).isEmpty())
        val cedarRows = index.rowsWithTerm("garden", "cedar")
        assertEquals(setOf("cedar", "cedar-a"), index.passages("garden").filter { it.rowId in cedarRows }.map { it.messageId }.toSet())
        assertEquals(1, index.rowsWithTerm("other", "cedar").size)

        // Waiting vectors: keywords alone can find it, meaning cannot.
        val waiting = engine().search(request("cedar"), RecallBudget.unlimited()) as RecallResult.Found
        assertEquals(22, waiting.report.passagesWithoutVector)

        makeVectors()
        assertEquals(22, index.vectors("garden", MODEL, DIMS, Long.MIN_VALUE, 100).size)
        assertTrue(index.vectors("garden", "other-model", DIMS, Long.MIN_VALUE, 100).isEmpty())
        assertEquals(3, index.vectors("garden", MODEL, DIMS, Long.MIN_VALUE, 3).size)
        val r = engine().search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found
        assertEquals(listOf("cedar"), r.exchanges.map { it.exchangeId })
        assertEquals(listOf("cedar", "cedar-a"), r.exchanges.single().messages.map { it.messageId })
    }

    /** Runs [change] once, when recall first reads the chat's vectors: in the middle of the search. */
    private fun duringSearch(change: () -> Unit): RecallIndex {
        val real = StoreRecallIndex(store)
        var done = false
        return object : RecallIndex by real {
            override fun vectors(chatId: String, vectorModel: String, vectorDimensions: Int, afterRowId: Long, limit: Int): List<RecallIndex.RowVector> {
                if (!done) { done = true; change() }
                return real.vectors(chatId, vectorModel, vectorDimensions, afterRowId, limit)
            }
        }
    }

    @Test fun anEditIndexedDuringTheSearchIsNeverRecalledInItsOldWords() {
        save("garden", garden())
        makeVectors()
        val index = duringSearch {
            save("garden", garden().map { if (it.messageId == "cedar-a") it.copy(modelText = "galvanized steel instead") else it })
        }
        val r = engine(index).search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found
        assertTrue(r.exchanges.isEmpty())
    }

    @Test fun aMessageDeletedOrHiddenDuringTheSearchIsLeftOut() {
        save("garden", garden())
        makeVectors()
        val deleted = duringSearch { save("garden", garden().filterNot { it.messageId == "cedar-a" }) }
        assertTrue((engine(deleted).search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found).exchanges.isEmpty())

        save("garden", garden())
        makeVectors()
        val hidden = duringSearch { save("garden", garden().map { if (it.messageId == "cedar") it.copy(hiddenFromAi = true) else it }) }
        assertTrue((engine(hidden).search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found).exchanges.isEmpty())
    }

    @Test fun theChatDeletedOrTheIndexDeletedDuringTheSearchGivesNothingAndNoError() {
        save("garden", garden())
        makeVectors()
        val chatGone = duringSearch { store.deleteChat("garden"); chats.remove("garden") }
        assertTrue((engine(chatGone).search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found).exchanges.isEmpty())

        save("garden", garden())
        makeVectors()
        val indexGone = duringSearch { store.deleteEverything() }
        val r = engine(indexGone).search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found
        assertTrue(r.exchanges.isEmpty())
    }

    @Test fun theIndexClosedByARecoveryDuringTheSearchNeverFailsTheSearch() {
        save("garden", garden())
        makeVectors()
        // A closed index either cannot be read (nothing is handed on) or is opened again with the
        // same rows; either way the search ends normally and hands on nothing that is not true.
        val closed = duringSearch { store.close() }
        when (val r = engine(closed).search(request("which wood did we pick"), RecallBudget.unlimited())) {
            is RecallResult.Skipped -> assertEquals(SkipReason.INDEX_UNAVAILABLE, r.reason)
            is RecallResult.Found -> assertTrue(r.exchanges.all { it.exchangeId == "cedar" })
        }
    }

    @Test fun aRecoveryHoldingTheIndexIsNotWaitedForAndRecallHandsOnNothing() {
        save("garden", garden())
        makeVectors()
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val recovery = Thread { ConversationIndexAccess.exclusive { holding.countDown(); release.await() } }
        recovery.start()
        try {
            assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val started = System.nanoTime()
            val r = engine().search(request("which wood did we pick"), RecallBudget.unlimited())
            assertEquals(SkipReason.INDEX_UNAVAILABLE, (r as RecallResult.Skipped).reason)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        } finally {
            release.countDown()
            recovery.join()
        }
        // After the recovery the same search reads the index again.
        val after = engine().search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found
        assertEquals(listOf("cedar"), after.exchanges.map { it.exchangeId })
    }

    @Test fun aRestoreMarkingEveryChatForRefreshDuringTheSearchChangesNothingThatIsStillTrue() {
        save("garden", garden())
        makeVectors()
        val refreshed = duringSearch { store.markAllForRefresh() }
        val r = engine(refreshed).search(request("which wood did we pick"), RecallBudget.unlimited()) as RecallResult.Found
        assertEquals(listOf("cedar"), r.exchanges.map { it.exchangeId })
    }

    private companion object {
        const val MODEL = "test-model-4"
        const val DIMS = 4
    }
}
