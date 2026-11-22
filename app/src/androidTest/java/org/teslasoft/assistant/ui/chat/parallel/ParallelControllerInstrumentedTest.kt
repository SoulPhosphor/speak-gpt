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

    private inner class Host(override val chatId: String) : ParallelChatController.Host {
        override val messages = arrayListOf<HashMap<String, Any>>()
        override var rawInput = "current message"
        override val includes = emptyList<ChatInclude>()
        override val manualMemoryIds = emptyList<String>()
        override val unavailable = false
        var preparations = 0
        var contextCommits = 0
        var failCommit = false
        var finished = CompletableDeferred<Unit>()
        override suspend fun prepareBase(raw: String, setup: ExperimentalSetup): ParallelTurnBase {
            preparations++
            return ParallelTurnBase(FrozenChatPayload("fixture-0", listOf(
                FrozenPayloadMessage("system", "shared instructions and memory"), FrozenPayloadMessage("user", raw)
            ), 0, null, null, null, null, null, null), emptyList(), emptyList(), 1, emptyMap()) { contextCommits++ }
        }
        override fun commitTurn(raw: String, group: ResponseGroup): Boolean {
            if (failCommit) return false
            messages.add(hashMapOf("message_id" to group.userMessageId, "isBot" to false, "message" to raw))
            messages.add(ResponseGroupEdits.install(mapOf("message_id" to group.assistantMessageId, "isBot" to true), group))
            if (!save()) return false
            rawInput = ""
            return true
        }
        override fun save() = ChatPreferences.getChatPreferences().saveChatHistory(context, chatId, messages, synchronous = true) == ChatStorageHealth.WriteOutcome.OK
        override fun changed(position: Int, canonical: Boolean) {}
        override fun busy(value: Boolean) {}
        override suspend fun adopt(position: Int, discardDescendants: Boolean) = false
        override suspend fun beforeRegenerate(position: Int, action: suspend () -> Unit) = action()
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
