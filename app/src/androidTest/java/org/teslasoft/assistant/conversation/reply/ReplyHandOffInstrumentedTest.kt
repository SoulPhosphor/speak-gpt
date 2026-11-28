/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.conversation.reply

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.chatstore.ChatStorageAuthority
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.ui.chat.ConversationTurnFence
import java.util.UUID
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.usage.TokenPricingCatalog
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.usage.TurnUsageRecord
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

/**
 * A reply already sent keeps going when its chat screen closes: it is
 * received to the end and saved into the chat by its own permanent ID, with
 * no screen. Stop keeps the text received so far; deleting the chat writes
 * nothing more.
 */
@RunWith(AndroidJUnit4::class)
class ReplyHandOffInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val chats get() = ChatPreferences.getChatPreferences()
    private val ids = ArrayList<String>()
    private val label = DamagedChatOperation.OPENING_A_CHAT

    @Before fun setup() {
        PortableRestoreProcessGate.clear(context)
        ChatStorageAuthority.clearForTest(context)
    }

    @After fun cleanup() {
        ReplyRunRegistry.markChatsDeleted(ids.toSet())
        org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.shared.discard(ids)
        ids.forEach { id ->
            SecurePrefs.get(context, "chat_$id").edit().clear().commit()
            SecurePrefs.get(context, "settings.$id").edit().clear().commit()
        }
    }

    private fun newChat() = UUID.randomUUID().toString().also(ids::add)

    private fun row(text: String, bot: Boolean, state: String? = null) = hashMapOf<String, Any>(
        "message" to text, "isBot" to bot, MessageIdentity.KEY to UUID.randomUUID().toString()
    ).also { if (state != null) it[MessageCompletionState.KEY_STATE] = state }

    private fun stored(chatId: String) = chats.getChatByIdResult(context, chatId, label).messages

    private fun piece(text: String) = ReplyPiece(
        content = text, contentLength = text.length, finishReason = null, id = null,
        promptTokens = null, completionTokens = null, totalTokens = null
    )

    /** The chat screen's part before it closes: rows it shows and saves. */
    private open class ScreenHost(override val model: String, val rows: ArrayList<HashMap<String, Any>>) : ReplyRunHost {
        override val storageUnavailable: Boolean get() = false
        override fun conversationSnapshot(): List<Map<String, Any>> = rows
        override fun lastRow(): HashMap<String, Any>? = rows.lastOrNull()
        override fun containsRow(row: Map<String, Any>?): Boolean = row != null && rows.any { it === row }
        override fun saveInBackground() {}
        override fun usageCarrier(): HashMap<String, Any>? = rows.lastOrNull()
        override fun lifecycleTurnId(): String = "T-test"
        override fun replyUpdated(text: String) {}
        override fun replyFinished() {}
        override fun saveRow(row: HashMap<String, Any>) {}
        override fun removeRow(row: HashMap<String, Any>) {}
        override fun reasoningObserved(
            observation: ReasoningObservationContext,
            learnedCapability: org.teslasoft.assistant.reasoning.ReasoningCapability?
        ) {}
    }

    private class Fixture(val chatId: String, val rows: ArrayList<HashMap<String, Any>>, val run: ReplyRun)

    private fun fixture(): Fixture {
        val chatId = newChat()
        val rows = arrayListOf(row("Hello?", bot = false), row("", bot = true, state = MessageCompletionState.STREAMING))
        assertEquals(ChatStorageHealth.WriteOutcome.OK, chats.saveChatHistory(context, chatId, rows, synchronous = true))
        val state = ChatRequestState().apply {
            this.chatId = chatId
            chatPreferences = Preferences.getPreferences(context, chatId)
        }
        val run = ReplyRun(context, state, ChatRequestHooks(context, state), ConversationTurnFence(), ScreenHost("model-x", rows))
        run.toolTurnRequest = com.aallam.openai.api.chat.ChatCompletionRequest(
            model = com.aallam.openai.api.model.ModelId("model-x"), messages = emptyList())
        run.dispatched = true
        return Fixture(chatId, rows, run)
    }

    /** Reads the relay as a screen would, then closes: hands off, stops reading. */
    private suspend fun readThenClose(f: Fixture, source: Flow<String>, until: String) {
        val received = CompletableDeferred<Unit>()
        val reader = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            f.run.relay(source) { piece(it).also { received.complete(Unit) } }.collect { }
        }
        withTimeout(10_000) { received.await(); while (f.run.text != until) delay(10) }
        withContext(Dispatchers.Main) {
            f.run.handOff(DetachedReplyHost(context, { f.chatId }, "model-x", ArrayList(f.rows), f.rows.last(), "T-test", label, f.run::ownsRow))
            reader.cancel()
        }
    }

    private suspend fun awaitEnded(run: ReplyRun) = withTimeout(20_000) { while (!run.ended) delay(20) }

    @Test fun theRowWriterChangesOnlyItsOwnRowAndStopsForAGoneRowOrChat() {
        val chatId = newChat()
        val user = row("Question", bot = false)
        val reply = row("partial", bot = true, state = MessageCompletionState.STREAMING)
        chats.saveChatHistory(context, chatId, arrayListOf(user, reply), synchronous = true)
        val changed = HashMap(reply).apply { put("message", "partial and more") }
        assertEquals(ReplyRowWriter.Outcome.SAVED, ReplyRowWriter.write(context, chatId, changed, label))
        val after = stored(chatId)
        assertEquals("Question", after[0]["message"])
        assertEquals("partial and more", after[1]["message"])
        assertEquals(2, after.size)

        val unknown = row("x", bot = true)
        assertEquals(ReplyRowWriter.Outcome.ROW_GONE, ReplyRowWriter.write(context, chatId, unknown, label))
        assertEquals(2, stored(chatId).size)

        val deletedChat = newChat()
        chats.saveChatHistory(context, deletedChat, arrayListOf(row("q", false), reply), synchronous = true)
        ReplyRunRegistry.markChatsDeleted(setOf(deletedChat))
        assertEquals(ReplyRowWriter.Outcome.CHAT_GONE, ReplyRowWriter.write(context, deletedChat, changed, label))
    }

    @Test fun aHandedOffReplyIsReceivedToTheEndAndSavedWithNoScreen() = runBlocking {
        val f = fixture()
        val gate = CompletableDeferred<Unit>()
        readThenClose(f, flow { emit("Hel"); gate.await(); emit("lo "); emit("there") }, until = "Hel")
        assertTrue(f.run.handedOff)
        gate.complete(Unit)
        awaitEnded(f.run)
        val reply = stored(f.chatId).last()
        assertEquals("Hello there\n", reply["message"])
        assertEquals(MessageCompletionState.DONE, reply[MessageCompletionState.KEY_STATE])
        assertEquals(2, stored(f.chatId).size)
        assertFalse(ReplyRunRegistry.active(f.chatId) === f.run)
    }

    @Test fun stopOnAHandedOffReplyKeepsTheTextReceivedSoFarAsStopped() = runBlocking {
        val f = fixture()
        readThenClose(f, flow { emit("Part"); awaitCancellation() }, until = "Part")
        withContext(Dispatchers.Main) { f.run.stopByUser() }
        awaitEnded(f.run)
        val reply = stored(f.chatId).last()
        assertEquals("Part", reply["message"])
        assertEquals(MessageCompletionState.STOPPED, reply[MessageCompletionState.KEY_STATE])
    }

    @Test fun deletingTheChatWhileTheReplyRunsWritesNothingMore() = runBlocking {
        val f = fixture()
        val gate = CompletableDeferred<Unit>()
        readThenClose(f, flow { emit("Hel"); gate.await(); emit("lo") }, until = "Hel")
        withContext(Dispatchers.Main) { ReplyRunRegistry.markChatsDeleted(setOf(f.chatId)) }
        gate.complete(Unit)
        awaitEnded(f.run)
        assertTrue(f.run.retired)
        val reply = stored(f.chatId).last()
        assertFalse(reply["message"].toString().startsWith("Hello"))
    }
    private class PricingGate(private val gate: CompletableDeferred<TokenPricingCatalog> = CompletableDeferred()) :
        Deferred<TokenPricingCatalog> by gate {
        val waiting = CompletableDeferred<Unit>()
        override suspend fun await(): TokenPricingCatalog {
            waiting.complete(Unit)
            return gate.await()
        }
        fun release() { gate.complete(TokenPricingCatalog("model-x")) }
    }

    private fun usage(f: Fixture): PricingGate {
        f.run.state.currentUsageAttempt = ProviderUsageAttempt("model-x", "fixture-provider", "https://fixture.invalid")
            .also { it.noteTypedUsage(11, 2, 13) }
        return PricingGate().also { f.run.pricingCatalog = it }
    }

    /** Each reopening loads separate objects, but only observes the same owner. */
    private fun observe(f: Fixture, loaded: ArrayList<HashMap<String, Any>>) =
        object : ScreenHost("model-x", loaded) {
            override fun replyUpdated(text: String) { f.run.reconcileInto(loaded) }
            override fun replyFinished() { f.run.reconcileInto(loaded) }
        }

    private fun receipt(message: Map<String, Any>) = TokenUsageAccounting.decodeRecords(
        message[ChatAdapter.KEY_TOKEN_USAGE_RECORDS]?.toString()
    )

    @Test fun reopeningAndClosingAgainDuringCompletedAccountingKeepsOneOwnerAndAllUsage() = runBlocking {
        val f = fixture()
        val price = usage(f)
        val finish = CompletableDeferred<Unit>()
        readThenClose(f, flow { emit("Answer"); finish.await() }, "Answer")
        val owner = f.run.host
        val first = stored(f.chatId)
        finish.complete(Unit)
        withTimeout(5000) { price.waiting.await() }
        val second = stored(f.chatId)
        withContext(Dispatchers.Main) {
            f.run.attach(observe(f, first))
            f.run.detachScreen()
            f.run.attach(observe(f, second))
            assertTrue(owner === f.run.host)
            assertFalse(f.run.ended)
            price.release()
        }
        awaitEnded(f.run)
        assertEquals(null, f.run.toolTurnRequest)
        for (reply in listOf(stored(f.chatId).last(), second.last())) {
            assertEquals(MessageCompletionState.DONE, reply[MessageCompletionState.KEY_STATE])
            assertEquals("Answer\n", reply["message"])
            assertEquals(13, receipt(reply).single().totalTokens)
        }
    }

    @Test fun anOlderScreensCleanupCannotDetachTheReplacementDisplay() = runBlocking {
        val f = fixture()
        readThenClose(f, flow { emit("Partial"); awaitCancellation() }, "Partial")
        val older = stored(f.chatId)
        val replacement = stored(f.chatId)
        withContext(Dispatchers.Main) {
            val closeOlder = f.run.attach(observe(f, older))
            f.run.attach(observe(f, replacement))
            closeOlder()
            f.run.stopByUser()
        }
        awaitEnded(f.run)
        assertEquals(MessageCompletionState.STOPPED, replacement.last()[MessageCompletionState.KEY_STATE])
        assertEquals(MessageCompletionState.STREAMING, older.last()[MessageCompletionState.KEY_STATE])
    }

    @Test fun reopeningDuringFailedAccountingKeepsFailureDetailsAndReportedUsage() = runBlocking {
        val f = fixture()
        val price = usage(f)
        val finish = CompletableDeferred<Unit>()
        readThenClose(f, flow { emit("Partial"); finish.await(); throw IllegalStateException("fixture request failed") }, "Partial")
        val loaded = stored(f.chatId)
        finish.complete(Unit)
        withTimeout(5000) { price.waiting.await() }
        withContext(Dispatchers.Main) { f.run.attach(observe(f, loaded)); price.release() }
        awaitEnded(f.run)
        for (reply in listOf(stored(f.chatId).last(), loaded.last())) {
            assertEquals(MessageCompletionState.FAILED, reply[MessageCompletionState.KEY_STATE])
            assertTrue(reply[MessageCompletionState.KEY_ERROR_TEXT].toString().isNotBlank())
            assertEquals(13, receipt(reply).single().totalTokens)
        }
    }

    @Test fun reopeningDuringStoppedAccountingKeepsThePartialReplyAndUsage() = runBlocking {
        val f = fixture()
        val price = usage(f)
        readThenClose(f, flow { emit("Partial"); awaitCancellation() }, "Partial")
        val loaded = stored(f.chatId)
        withContext(Dispatchers.Main) { f.run.stopByUser() }
        withTimeout(5000) { price.waiting.await() }
        withContext(Dispatchers.Main) { f.run.attach(observe(f, loaded)); price.release() }
        awaitEnded(f.run)
        assertEquals(MessageCompletionState.STOPPED, stored(f.chatId).last()[MessageCompletionState.KEY_STATE])
        assertEquals(13, receipt(loaded.last()).single().totalTokens)
    }

    @Test fun toolOnlyAccountingUpdatesItsCarrierAndRemovesItsReplyAsOneFinishedTransaction() = runBlocking {
        val prefs = Preferences.getPreferences(context, "")
        val previous = prefs.getAskBeforeAiImages()
        prefs.setAskBeforeAiImages(true)
        try {
            val f = fixture()
            val price = usage(f)
            val finish = CompletableDeferred<Unit>()
            readThenClose(f, flow {
                emit(""); finish.await()
                f.run.toolCalls.accept(0, "fixture-call", "create_image", "{\"prompt\":\"a lighthouse\"}")
            }, "")
            val before = ReplyRunRegistry.capture(f.chatId)
            val loaded = stored(f.chatId)
            finish.complete(Unit)
            withTimeout(5000) { price.waiting.await() }
            withContext(Dispatchers.Main) { f.run.attach(observe(f, loaded)); price.release() }
            awaitEnded(f.run)
            assertFalse(ReplyRunRegistry.isCurrent(f.chatId, before))
            for (history in listOf(stored(f.chatId), loaded)) {
                assertEquals(1, history.size)
                assertEquals(false, history.single()["isBot"])
                assertEquals(13, receipt(history.single()).single().totalTokens)
            }
            val approval = org.teslasoft.assistant.imagegen.PendingImageApproval.decode(
                f.run.state.chatPreferences!!.getPendingImageApproval())!!
            assertEquals("model-x", PendingToolTurns.take(f.chatId, approval.id)!!.request.model.id)
            assertEquals(null, f.run.toolTurnRequest)
        } finally { prefs.setAskBeforeAiImages(previous) }
    }

    @Test fun closingDuringOriginalFinalBookkeepingWaitsForItsReceiptBeforeFinishing() = runBlocking {
        val f = fixture()
        val sourceDone = CompletableDeferred<Unit>()
        val releaseOriginal = CompletableDeferred<Unit>()
        val providerGate = CompletableDeferred<Unit>()
        val received = CompletableDeferred<Unit>()
        val reader = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            f.run.relay(flow {
                emit("Answer"); providerGate.await(); sourceDone.complete(Unit)
            }) { piece(it).also { received.complete(Unit) } }.collect { }
        }
        withTimeout(5000) { received.await() }
        withContext(Dispatchers.Main) {
            f.run.handOff(DetachedReplyHost(context, { f.chatId }, "model-x", f.rows, f.rows.last(), "T-test", label), releaseOriginal)
            reader.cancel()
        }
        providerGate.complete(Unit)
        withTimeout(5000) { sourceDone.await() }
        withContext(Dispatchers.Main) {
            assertFalse(f.run.ended)
            f.run.attachUsageRecords(f.rows.last(), listOf(TurnUsageRecord("model-x", "fixture-provider", totalTokens = 13, requestId = "original-receipt")))
            f.rows.last()[MessageCompletionState.KEY_STATE] = MessageCompletionState.DONE
            releaseOriginal.complete(Unit)
        }
        awaitEnded(f.run)
        assertEquals("original-receipt", receipt(stored(f.chatId).last()).single().requestId)
    }

    @Test fun restoringDuringToolAccountingCannotWriteOldUsageOrAnApprovalIntoTheRestoredChat() = runBlocking {
        val f = fixture()
        val price = usage(f)
        val finish = CompletableDeferred<Unit>()
        readThenClose(f, flow {
            emit(""); finish.await()
            f.run.toolCalls.accept(0, "fixture-call", "create_image", "{\"prompt\":\"a lighthouse\"}")
        }, "")
        finish.complete(Unit)
        withTimeout(5000) { price.waiting.await() }
        val restored = arrayListOf(row("restored question", false), row("restored answer", true))
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            ReplyRunRegistry.discardAll()
            chats.saveChatHistory(context, f.chatId, restored, synchronous = true)
            f.run.state.chatPreferences!!.commitUsageLog("restored usage sentinel")
        }
        withContext(Dispatchers.Main) { price.release() }
        awaitEnded(f.run)
        assertEquals("restored answer", stored(f.chatId).last()["message"])
        assertEquals("restored usage sentinel", f.run.state.chatPreferences!!.getUsageLog())
        assertEquals("", f.run.state.chatPreferences!!.getPendingImageApproval())
        assertEquals(null, org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.activeJob(f.chatId))
    }

    @Test fun stoppingDuringToolAccountingKeepsItsUsageWithoutStartingAnImageOrApproval() = runBlocking {
        val f = fixture()
        val price = usage(f)
        val finish = CompletableDeferred<Unit>()
        readThenClose(f, flow {
            emit(""); finish.await()
            f.run.toolCalls.accept(0, "fixture-call", "create_image", "{\"prompt\":\"a lighthouse\"}")
        }, "")
        finish.complete(Unit)
        withTimeout(5000) { price.waiting.await() }
        withContext(Dispatchers.Main) { f.run.stopByUser(); price.release() }
        awaitEnded(f.run)
        assertEquals(13, receipt(stored(f.chatId).single()).single().totalTokens)
        assertEquals("", f.run.state.chatPreferences!!.getPendingImageApproval())
        assertEquals(null, org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.activeJob(f.chatId))
    }

    @Test fun aRetiredHostCannotApplyAQueuedSnapshotToTheNewerConversation() = runBlocking {
        val f = fixture()
        val host = DetachedReplyHost(context, { f.chatId }, "model-x", f.rows, f.rows.last(), "T-test", label)
        withContext(Dispatchers.Main) { host.saveInBackground(); host.released() }
        val newer = stored(f.chatId)
        newer.last()["message"] = "newer saved answer"
        chats.saveChatHistory(context, f.chatId, newer, synchronous = true)
        host.awaitSaved()
        assertEquals("newer saved answer", stored(f.chatId).last()["message"])
    }

    @Test fun reasoningAlreadyDeliveredBeforeReopeningIsTransferredWithTheSameOwnedRow() = runBlocking {
        val f = fixture()
        readThenClose(f, flow { emit("Partial"); awaitCancellation() }, "Partial")
        val loaded = stored(f.chatId)
        val acc = org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator().apply {
            acceptLine("data: {\"choices\":[{\"delta\":{\"reasoning\":\"Preserved reasoning\"}}]}")
        }
        withContext(Dispatchers.Main) {
            val observation = ReasoningObservationContext(acc, "fixture", "model-x", true,
                MessageIdentity.idOf(f.rows.last()), null, { true }, { _, _ -> })
            f.run.host.reasoningObserved(observation, null)
            f.run.attach(observe(f, loaded))
            assertEquals("Preserved reasoning", loaded.last()[ChatAdapter.KEY_MESSAGE_REASONING])
            f.run.stopByUser()
        }
        awaitEnded(f.run)
        assertEquals("Preserved reasoning", stored(f.chatId).last()[ChatAdapter.KEY_MESSAGE_REASONING])
    }

    @Test fun replyTransactionsPreserveConcurrentUserEditsVisibilityAndBookmarks() {
        val f = fixture()
        val carrier = HashMap(f.rows.first()).apply {
            put(ChatAdapter.KEY_TOKEN_USAGE_RECORDS, TokenUsageAccounting.encodeRecords(listOf(
                TurnUsageRecord("model-x", "fixture", totalTokens = 13, requestId = "tool-receipt"))))
        }
        val reply = HashMap(f.rows.last()).apply { put("message", "finished answer") }
        val newer = stored(f.chatId)
        newer.first()["message"] = "user edited the question"
        newer.first()[ChatAdapter.KEY_TOKEN_USAGE_RECORDS] = TokenUsageAccounting.encodeRecords(listOf(
            TurnUsageRecord("model-x", "fixture", totalTokens = 7, requestId = "newer-tool-receipt")))
        newer.first()["bookmark"] = true
        newer.last()["hiddenFromAi"] = true
        newer.last()["bookmark"] = true
        chats.saveChatHistory(context, f.chatId, newer, synchronous = true)
        assertEquals(ReplyRowWriter.Outcome.SAVED, ReplyRowWriter.apply(
            context, f.chatId, ReplyEdits(listOf(carrier, reply)), label))
        val after = stored(f.chatId)
        assertEquals("user edited the question", after.first()["message"])
        assertEquals(setOf("newer-tool-receipt", "tool-receipt"), receipt(after.first()).map { it.requestId }.toSet())
        assertEquals(20, receipt(after.first()).sumOf { it.totalTokens ?: 0 })
        assertEquals("finished answer", after.last()["message"])
        assertEquals(true, after.first()["bookmark"])
        assertEquals(true, after.last()["bookmark"])
        assertEquals(true, after.last()["hiddenFromAi"])
        // Reapplying the same reply transaction is idempotent, including receipts.
        assertEquals(ReplyRowWriter.Outcome.SAVED, ReplyRowWriter.apply(
            context, f.chatId, ReplyEdits(listOf(carrier, reply)), label))
        assertEquals(2, receipt(stored(f.chatId).first()).size)
    }

    @Test fun aKeptWholeHistoryDeletionIsSavedBeforeTheReplyTransformsStorage() = runBlocking {
        val f = fixture()
        val deleted = row("older message being deleted", bot = false)
        chats.saveChatHistory(context, f.chatId, arrayListOf(deleted, *f.rows.toTypedArray()), synchronous = true)
        val order = org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.shared
        try {
            order.saveInBackground(f.chatId, order.claim(f.chatId),
                org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.snapshotOf(f.rows), false,
                org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.Writer { _, _, _ -> false })
            val reply = HashMap(f.rows.last()).apply { put("message", "finished answer") }
            assertEquals(ReplyRowWriter.Outcome.NOT_SAVED, ReplyRowWriter.write(context, f.chatId, reply, label))
            order.saveInBackground(f.chatId, order.claim(f.chatId),
                org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.snapshotOf(f.rows), false,
                org.teslasoft.assistant.preferences.ChatHistoryWriteOrder.historyWriter(context))
            withTimeout(10_000) { order.awaitSaved(f.chatId) }
            assertEquals(ReplyRowWriter.Outcome.SAVED, ReplyRowWriter.write(context, f.chatId, reply, label))
            val after = stored(f.chatId)
            assertEquals(2, after.size)
            assertFalse(after.any { MessageIdentity.idOf(it) == MessageIdentity.idOf(deleted) })
            assertEquals("finished answer", after.last()["message"])
        } finally { order.discard(listOf(f.chatId)) }
    }

    @Test fun aNewGenerationOfTheSamePermanentRowFencesTheOldWriterEvenAfterItFinishes() = runBlocking {
        val f = fixture()
        val replyId = MessageIdentity.idOf(f.rows.last())
        f.run.claimRow(replyId)
        val oldHost = DetachedReplyHost(context, { f.chatId }, "model-x", f.rows, f.rows.last(), "T-old", label, f.run::ownsRow)
        f.run.host = oldHost
        val updated = stored(f.chatId).apply { last()["message"] = "new generation" }
        val state = ChatRequestState().apply { chatId = f.chatId }
        val newer = ReplyRun(context, state, ChatRequestHooks(context, state), ConversationTurnFence(), ScreenHost("model-x", updated))
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            oldHost.saveInBackground()
            newer.claimRow(replyId)
            ReplyRunRegistry.register(f.chatId, newer)
            chats.saveChatHistory(context, f.chatId, updated, synchronous = true)
            ReplyRunRegistry.unregister(f.chatId, newer)
            // An old sender cannot regain the lease by closing late.
            f.run.claimRow(replyId)
        }
        withTimeout(5000) { oldHost.awaitSaved() }
        assertFalse(f.run.ownsRow())
        assertTrue(newer.ownsRow())
        assertEquals("new generation", stored(f.chatId).last()["message"])
    }

}
