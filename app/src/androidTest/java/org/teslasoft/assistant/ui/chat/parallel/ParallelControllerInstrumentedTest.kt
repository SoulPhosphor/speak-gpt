package org.teslasoft.assistant.ui.chat.parallel

import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.conversation.parallel.*
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.conversation.setup.*
import org.teslasoft.assistant.preferences.*
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.preferences.dto.*
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.ui.activities.TokenPricingDetailsActivity
import org.teslasoft.assistant.usage.*
import org.teslasoft.assistant.util.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Production screen controller and turn preparation, guarded storage and fake owned transports. */
@RunWith(AndroidJUnit4::class)
class ParallelControllerInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun id() = UUID.randomUUID().toString()

    @Test fun outOfOrderFailureStopBrowsingAndOneSlotRegenerationKeepTheirOwners() = fixture { activity, host ->
        val gates = List(4) { CompletableDeferred<Unit>() }
        val started = Channel<ParallelRequest>(Channel.UNLIMITED)
        val seen = CopyOnWriteArrayList<ParallelRequest>()
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> error("No binary fixture") }, { request -> ParallelTransport { r, out ->
                    val index = r.assignment.modelId.substringAfterLast('-').toInt()
                    val original = seen.none { it.slotId == r.slotId }
                    seen += r
                    val durable = host.stored()
                    assertTrue(durable.slots.flatMap { it.versions }.flatMap { it.attempts }.any { it.requestId == r.requestId })
                    r.usage.beginObservation()
                    try {
                        r.usage.noteTypedUsage(10 + index, 2, 12 + index)
                        out.text(if (original) "answer-$index" else "regenerated-$index")
                        out.reasoning("reasoning-$index")
                        started.send(r)
                        if (original) gates[index].await()
                        if (original && index == 1) throw ParallelTransportFailure("fixture_failure")
                    } finally { r.usage.finishObservation() }
                } }, { TokenPricingCatalog(it.assignment.modelId) })
            }
        }
        try {
            withContext(Dispatchers.Main.immediate) { assertTrue(controller.send("current message")) }
            repeat(4) { started.receive() }
            val initial = withContext(Dispatchers.Main.immediate) { host.group() }
            withContext(Dispatchers.Main.immediate) {
                controller.browse(1, initial.slots[3].slotId, initial.slots[3].foregroundVersionId)
                controller.stop(1, initial.slots[2].slotId)
            }
            for (index in listOf(3, 1, 0)) gates[index].complete(Unit)
            host.finished.await()
            val settled = withContext(Dispatchers.Main.immediate) { host.group() }
            assertEquals(listOf("done", "failed", "stopped", "done"), settled.slots.map { it.versions.single().fields["state"] })
            assertEquals("answer-0", host.storedRow()["message"])
            assertEquals("answer-3", ParallelResponseSelection.display(host.storedRow())["message"])
            assertEquals(4, ResponseGroupEdits.usage(settled).map { it.requestId }.distinct().size)
            assertEquals(1, host.preparations)
            assertEquals(1, host.contextCommits)
            assertSame(host.committedUserRow, withContext(Dispatchers.Main.immediate) { host.messages.first() })
            withContext(Dispatchers.Main.immediate) {
                host.finished = CompletableDeferred()
                assertTrue(controller.regenerate(1))
            }
            val retry = started.receive()
            host.finished.await()
            val after = host.stored()
            assertEquals(5, seen.size)
            val original = seen.first { it.slotId == retry.slotId }
            assertNotEquals(original.requestId, retry.requestId)
            assertEquals(original.assignment, retry.assignment)
            assertEquals(original.prompt, retry.prompt)
            assertEquals(settled.slots.take(3), after.slots.take(3))
            assertEquals(2, after.slots[3].versions.size)
            assertEquals(5, ResponseGroupEdits.usage(after).size)
            assertSame(host.committedUserRow, withContext(Dispatchers.Main.immediate) { host.messages.first() })
            assertEquals(1, host.preparations)
            assertEquals(1, host.contextCommits)
        } finally { withContext(Dispatchers.Main.immediate) { controller.close() } }
    }

    @Test fun screenTeardownReconcilesOwnedPartialsWithoutResendingAndKeepsTheBrowsedTab() = fixture { activity, host ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        val requests = CopyOnWriteArrayList<ParallelRequest>()
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> null }, { ParallelTransport { request, out ->
                    requests += request
                    out.text("retained partial ${request.assignment.modelId}")
                    started.send(Unit)
                    awaitCancellation()
                } }, { TokenPricingCatalog(it.assignment.modelId) })
            }.also { it.send("current message") }
        }
        repeat(4) { started.receive() }
        val before = host.stored()
        withContext(Dispatchers.Main.immediate) {
            controller.browse(1, before.slots[3].slotId, before.slots[3].foregroundVersionId)
            controller.close()
        }
        var reopened = host.stored()
        while (ParallelResponseSelection.running(reopened)) { delay(20); reopened = host.stored() }
        assertEquals(4, requests.size)
        assertEquals(before.slots[3].slotId, reopened.foregroundSlotId)
        assertEquals(before.slots.flatMap { it.versions }.flatMap { it.attempts }.map { it.requestId },
            reopened.slots.flatMap { it.versions }.flatMap { it.attempts }.map { it.requestId })
        assertTrue(reopened.slots.all { it.versions.single().fields["state"] == "interrupted" })
        assertTrue(reopened.slots.all { it.versions.single().fields["message"]!!.startsWith("retained partial") })
        val row = host.storedRow()
        assertFalse(ResponseGroupRecovery.reconcile(row))
        assertEquals(reopened.foregroundSlotId, (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group.foregroundSlotId)
    }

    @Test fun deletingOnlyTheVisibleUserKeepsRegenerationAndTheNextTurnUsable() = fixture { activity, host ->
        val requests = CopyOnWriteArrayList<ParallelRequest>()
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> null }, { ParallelTransport { request, out ->
                    requests += request
                    out.text("owned answer")
                } }, { TokenPricingCatalog(it.assignment.modelId) })
            }.also { it.send("current message") }
        }
        try {
            host.finished.await()
            val original = host.stored()
            withContext(Dispatchers.Main.immediate) {
                assertTrue(controller.withRequestsStopped {
                    host.messages.removeAt(0)
                    assertTrue(host.save())
                })
                host.finished = CompletableDeferred()
                assertTrue(controller.regenerate(0))
            }
            host.finished.await()
            val regenerated = host.stored()
            assertEquals(original.userMessageId, regenerated.userMessageId)
            assertEquals(original.input, regenerated.input)
            assertEquals(5, requests.size)
            assertEquals(requests.first().prompt, requests.last().prompt)
            withContext(Dispatchers.Main.immediate) {
                host.finished = CompletableDeferred()
                host.rawInput = "next message"
                assertTrue(controller.send("next message"))
            }
            host.finished.await()
            assertEquals(9, requests.size)
            assertEquals(3, withContext(Dispatchers.Main.immediate) { host.messages.size })
        } finally { withContext(Dispatchers.Main.immediate) { controller.close() } }
    }

    @Test fun ordinaryRegenerationCannotFallThroughAndTruncateLiveParallelRequests() = fixture { activity, host ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        var calls = 0
        val ordinary = hashMapOf<String, Any>("message_id" to id(), "isBot" to true, "message" to "ordinary reply", "state" to "done")
        withContext(Dispatchers.Main.immediate) {
            host.messages += hashMapOf<String, Any>("message_id" to id(), "isBot" to false, "message" to "earlier user")
            host.messages += ordinary
            assertTrue(host.save())
        }
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> null }, { ParallelTransport { _, out ->
                    synchronized(host) { calls++ }
                    out.text("live parallel")
                    started.send(Unit)
                    awaitCancellation()
                } }, { TokenPricingCatalog(it.assignment.modelId) })
            }.also { it.send("current message") }
        }
        try {
            repeat(4) { started.receive() }
            withContext(Dispatchers.Main.immediate) {
                assertTrue(controller.regenerate(1))
                assertEquals(4, host.messages.size)
                assertSame(ordinary, host.messages[1])
                assertTrue(ParallelResponseSelection.running(host.group()))
                controller.stop(3, null)
            }
            host.finished.await()
            assertEquals(4, calls)
            assertFalse(ParallelResponseSelection.running(host.stored()))
            withContext(Dispatchers.Main.immediate) { assertFalse(controller.regenerate(1)) }
        } finally { withContext(Dispatchers.Main.immediate) { controller.close() } }
    }

    @Test fun failedInitialCommitLeavesTheDraftAndLaunchesNoProviderRequests() = fixture { activity, host ->
        host.failCommit = true
        var calls = 0
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> null }, { ParallelTransport { _, _ -> calls++ } }, { TokenPricingCatalog(it.assignment.modelId) })
            }.also { it.send("current message") }
        }
        try {
            host.finished.await()
            assertEquals(0, calls)
            assertEquals("current message", host.rawInput)
            assertTrue(host.messages.isEmpty())
            assertEquals(0, host.contextCommits)
        } finally { withContext(Dispatchers.Main.immediate) { controller.close() } }
    }

    @Test fun deletingAnActiveGroupJoinsFinalUsageAndAllowsTheNextTurn() = fixture { activity, host ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        val requests = CopyOnWriteArrayList<ParallelRequest>()
        var hold = true
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> null }, { ParallelTransport { request, out ->
                    requests += request
                    request.usage.beginObservation()
                    try {
                        out.text("owned partial")
                        started.send(Unit)
                        if (hold) awaitCancellation()
                    } finally {
                        request.usage.noteTypedUsage(13, 5, 18)
                        request.usage.finishObservation()
                    }
                } }, { TokenPricingCatalog(it.assignment.modelId) })
            }.also { it.send("current message") }
        }
        try {
            repeat(4) { started.receive() }
            val ids = host.stored().slots.flatMap { it.versions }.flatMap { it.attempts }.map { it.requestId }.toSet()
            withContext(Dispatchers.Main.immediate) {
                assertTrue(controller.withRequestsStopped {
                    val settledGroup = host.group()
                    assertFalse(ParallelResponseSelection.running(settledGroup))
                    val receipts = ResponseGroupEdits.usage(settledGroup)
                    assertEquals(ids, receipts.map { it.requestId }.toSet())
                    assertTrue(receipts.all { it.inputTokens == 13 && it.outputTokens == 5 })
                    assertTrue(settledGroup.slots.all { it.versions.single().fields["state"] == "stopped" })
                    host.messages.clear()
                    assertTrue(host.save())
                })
                assertFalse(controller.busy)
                assertFalse(controller.unsaved(1))
                hold = false
                host.rawInput = "next message"
                assertTrue(controller.send("next message"))
            }
            repeat(4) { started.receive() }
            host.finished.await()
            assertEquals(8, requests.size)
            assertTrue(host.stored().slots.flatMap { it.versions }.flatMap { it.attempts }.none { it.requestId in ids })
            assertFalse(withContext(Dispatchers.Main.immediate) { controller.busy })
        } finally { withContext(Dispatchers.Main.immediate) { controller.close() } }
    }

    @Test fun deletionWaitsForSaveRetryWithoutRemovingRowsOrResendingRequests() = fixture { activity, host ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        var calls = 0
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> null }, { ParallelTransport { _, out ->
                    synchronized(host) { calls++ }
                    out.text("retained evidence")
                    started.send(Unit)
                    awaitCancellation()
                } }, { TokenPricingCatalog(it.assignment.modelId) })
            }.also { it.send("current message") }
        }
        try {
            repeat(4) { started.receive() }
            val original = host.stored()
            var removed = false
            withContext(Dispatchers.Main.immediate) {
                host.failSave = true
                assertFalse(controller.withRequestsStopped { removed = true; host.messages.clear() })
                assertFalse(removed)
                assertEquals(2, host.messages.size)
                assertTrue(controller.busy)
                assertTrue(controller.unsaved(1))
                host.failSave = false
                controller.retrySave()
            }
            host.finished.await()
            assertFalse(withContext(Dispatchers.Main.immediate) { controller.busy })
            assertEquals(4, calls)
            val saved = host.stored()
            assertFalse(ParallelResponseSelection.running(saved))
            assertEquals(original.slots.flatMap { it.versions }.flatMap { it.attempts }.map { it.requestId },
                saved.slots.flatMap { it.versions }.flatMap { it.attempts }.map { it.requestId })
            assertEquals(4, ResponseGroupEdits.usage(saved).size)
        } finally { withContext(Dispatchers.Main.immediate) { controller.close() } }
    }

    @Test fun editingAnActiveAlternativeStopsOnlyThatVersionAndKeepsItsDialogTarget() = fixture { activity, host ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        val gates = List(4) { CompletableDeferred<Unit>() }
        val controller = withContext(Dispatchers.Main.immediate) {
            ParallelChatController(activity, host) { owner, store ->
                ParallelRequestCoordinator(owner, store, { _, _ -> null }, { ParallelTransport { request, out ->
                    val index = request.assignment.modelId.substringAfterLast('-').toInt()
                    out.text("provider-$index")
                    started.send(Unit)
                    gates[index].await()
                } }, { TokenPricingCatalog(it.assignment.modelId) })
            }.also { it.send("current message") }
        }
        try {
            repeat(4) { started.receive() }
            val original = host.stored()
            withContext(Dispatchers.Main.immediate) {
                val selected = original.slots[3]
                controller.browse(1, selected.slotId, selected.foregroundVersionId)
                val target = ParallelReplyEditing.target(host.group())
                controller.browse(1, original.slots[1].slotId, original.slots[1].foregroundVersionId)
                controller.editReply(target, "edited alternative")
            }
            while (withContext(Dispatchers.Main.immediate) { host.group().slots[3].versions.single().fields["message"] != "edited alternative" }) delay(20)
            val edited = host.stored()
            assertEquals(ResponseGroupEdits.canonicalFields(original)["message"], host.storedRow()["message"])
            assertEquals(original.slots[1].slotId, edited.foregroundSlotId)
            assertEquals("stopped", edited.slots[3].versions.single().fields["state"])
            assertEquals("provider-3", edited.slots[3].versions.single().attempts.single().partialFields["message"])
            assertEquals(0, host.canonicalEdits)
            assertTrue(edited.slots.take(3).all { it.versions.single().fields["state"] == "streaming" })
            (0 until 3).forEach { gates[it].complete(Unit) }
            host.finished.await()
            assertEquals("provider-0", host.storedRow()["message"])
            withContext(Dispatchers.Main.immediate) {
                val settledGroup = host.group()
                val primary = settledGroup.slots.single { it.slotId == settledGroup.primarySlotId }
                controller.browse(1, primary.slotId, primary.canonicalVersionId)
                controller.editReply(ParallelReplyEditing.target(host.group()), "edited canonical")
            }
            while (withContext(Dispatchers.Main.immediate) { host.canonicalEdits == 0 }) delay(20)
            assertEquals(1, host.canonicalEdits)
            assertEquals("edited canonical", host.storedRow()["message"])
            assertTrue(ResponseGroupCodec.read(host.storedRow()) is ResponseGroupRead.Valid)
            assertEquals(4, ResponseGroupEdits.usage(host.stored()).size)
        } finally { withContext(Dispatchers.Main.immediate) { controller.close() } }
    }

    private inner class Host(override val chatId: String) : ParallelChatController.Host {
        override val messages = arrayListOf<HashMap<String, Any>>()
        override var rawInput = "current message"
        override val includes = emptyList<ChatInclude>()
        override val manualMemoryIds = emptyList<String>()
        override val unavailable = false
        var preparations = 0
        var contextCommits = 0
        var failCommit = false
        var canonicalEdits = 0
        var committedUserRow: HashMap<String, Any>? = null
        var failSave = false
        var finished = CompletableDeferred<Unit>()
        override suspend fun prepareBase(raw: String, setup: ExperimentalSetup): ParallelTurnBase {
            preparations++
            return ParallelTurnBase(FrozenChatPayload("fixture-0", listOf(
                FrozenPayloadMessage("system", "shared instructions and memory"), FrozenPayloadMessage("user", raw)
            ), 0, null, null, null, null, null, null), emptyList(), emptyList(), 1, emptyMap()) { contextCommits++ }
        }
        override fun commitTurn(raw: String, base: ParallelTurnBase, group: ResponseGroup): Boolean {
            if (failCommit) return false
            val user = hashMapOf<String, Any>("message_id" to group.userMessageId, "isBot" to false, "message" to (base.storedMessage ?: raw))
            committedUserRow = user
            messages.add(user)
            messages.add(ResponseGroupEdits.install(mapOf("message_id" to group.assistantMessageId, "isBot" to true), group))
            if (!save()) return false
            rawInput = ""
            return true
        }
        override fun save() = !failSave && ChatPreferences.getChatPreferences().saveChatHistory(context, chatId, messages, synchronous = true) == ChatStorageHealth.WriteOutcome.OK
        override fun changed(position: Int, canonical: Boolean) {}
        override fun busy(value: Boolean) {}
        override suspend fun adopt(position: Int, discardDescendants: Boolean) = false
        override suspend fun beforeRegenerate(position: Int, action: suspend () -> Unit) = action()
        override fun canonicalEdited() { canonicalEdits++ }
        override fun settled() { finished.complete(Unit) }
        fun group() = (ResponseGroupCodec.read(messages.last()) as ResponseGroupRead.Valid).group
        fun storedRow() = ChatPreferences.getChatPreferences().getChatByIdResult(context, chatId, DamagedChatOperation.OPENING_A_CHAT).messages.last()
        fun stored() = (ResponseGroupCodec.read(storedRow()) as ResponseGroupRead.Valid).group
    }

    private fun fixture(action: suspend (FragmentActivity, Host) -> Unit) = runBlocking {
        withTimeout(60_000) {
            val host = Host(id())
            val endpoints = ApiEndpointPreferences.getApiEndpointPreferences(context)
            val models = (0 until 4).map { index ->
                val endpoint = ApiEndpointObject("Fixture $index", "https://fixture-$index.invalid/v1", "synthetic-not-a-credential", id = "stage4-${id()}")
                endpoints.setApiEndpoint(context, endpoint)
                SetupModel(id(), endpoint.id, "fixture-$index", endpoint.host, endpoint.chatEndpoint, endpoint.authType, endpoint.identity,
                    Gson().toJson(FavoriteModelObject("fixture-$index", endpoint.id, streaming = false)))
            }
            val prefs = Preferences.getPreferences(context, host.chatId)
            prefs.setChatApplyModelRules(false)
            assertTrue(prefs.saveExperimentalSetup(ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, false, models, models.first().id, false)))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activity = instrumentation.startActivitySync(Intent(context, TokenPricingDetailsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as FragmentActivity
            try { action(activity, host) }
            finally {
                withContext(Dispatchers.Main.immediate) { activity.finish() }
                models.forEach { endpoints.deleteApiEndpoint(context, it.endpointId) }
                SecurePrefs.get(context, "chat_${host.chatId}").edit().clear().commit()
                SecurePrefs.get(context, "settings.${host.chatId}").edit().clear().commit()
            }
        }
    }
}
