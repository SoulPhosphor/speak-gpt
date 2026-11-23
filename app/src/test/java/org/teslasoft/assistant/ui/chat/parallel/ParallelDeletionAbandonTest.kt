package org.teslasoft.assistant.ui.chat.parallel

import android.app.Application
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.conversation.parallel.ParallelGroupPersistence
import org.teslasoft.assistant.conversation.parallel.ParallelInput
import org.teslasoft.assistant.conversation.parallel.ParallelInputPart
import org.teslasoft.assistant.conversation.parallel.ParallelRequestCoordinator
import org.teslasoft.assistant.conversation.parallel.ParallelSlotInput
import org.teslasoft.assistant.conversation.parallel.ParallelTransport
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroup
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupCodec
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupEdits
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupRead
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupTarget
import org.teslasoft.assistant.conversation.responsegroups.ResponseModelAssignment
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.usage.TokenPricingCatalog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Message deletion abandons Parallel Responses work without waiting: the
 * next turn can start at once, a deleted group's late result never returns to
 * the chat (its usage goes to the usage log), and the abandoned operation's
 * cleanup cannot reset a newer turn.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class ParallelDeletionAbandonTest {
    private fun id() = UUID.randomUUID().toString()
    private lateinit var activity: FragmentActivity
    private lateinit var host: Host
    private lateinit var controller: ParallelChatController
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** Counted down when a regeneration's setup reads its group (off the main thread). */
    @Volatile private var setupReading = CountDownLatch(1)

    private class Host(override val chatId: String) : ParallelChatController.Host {
        override val messages = arrayListOf<HashMap<String, Any>>()
        override val rawInput = ""
        override val includes = emptyList<ChatInclude>()
        override val manualMemoryIds = emptyList<String>()
        override val unavailable = false
        val deletedRows = mutableListOf<HashMap<String, Any>>()
        override suspend fun prepareBase(raw: String, setup: ExperimentalSetup): ParallelTurnBase = error("unused")
        override fun commitTurn(raw: String, base: ParallelTurnBase, group: ResponseGroup): Boolean = error("unused")
        override fun save() = true
        override fun changed(position: Int, canonical: Boolean) {}
        override fun busy(value: Boolean) {}
        override suspend fun adopt(position: Int, discardDescendants: Boolean) = false
        override suspend fun beforeRegenerate(position: Int, action: suspend () -> Unit) = action()
        override fun settled() {}
        override fun deletedGroupSettled(row: HashMap<String, Any>) { deletedRows += row }
    }

    /** One finished turn: its user row and its settled two-slot group row. */
    private fun turn(): Pair<HashMap<String, Any>, HashMap<String, Any>> {
        val userId = id()
        val assistantId = id()
        val current = ParallelInputPart.Text("question")
        val frozen = ParallelInput.freeze(
            userId, assistantId, "full_history", emptyList(), listOf(current),
            (0 until 2).map { index ->
                ParallelSlotInput(ResponseModelAssignment(
                    "model-$index", "Model $index", "endpoint-$index", "https://endpoint-$index.invalid/v1/",
                    "chat_completions", emptyMap(), emptyMap(), emptyMap(), mapOf("text" to "reported")
                ), listOf(current))
            }, 0, 100L
        )
        val group = ResponseGroupEdits.interrupted(frozen)
        val user = hashMapOf<String, Any>(MessageIdentity.KEY to userId, "isBot" to false, "message" to "question")
        val row = ResponseGroupEdits.install(mapOf(MessageIdentity.KEY to assistantId, "isBot" to true), group)
        return user to row
    }

    private fun startedFor(row: Map<String, Any>) =
        started.getOrPut(groupOf(row).assistantMessageId) { CompletableDeferred() }

    private fun groupOf(row: Map<String, Any>) = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host = Host(id())
        controller = ParallelChatController(activity, host) { scope, store ->
            val observed = object : ParallelGroupPersistence {
                override fun read(target: ResponseGroupTarget): ResponseGroup {
                    setupReading.countDown()
                    return store.read(target)
                }
                override fun update(target: ResponseGroupTarget, transform: (ResponseGroup?) -> ResponseGroup) =
                    store.update(target, transform)
            }
            ParallelRequestCoordinator(scope, observed, { _, _ -> null }, { request ->
                ParallelTransport { r, out ->
                    r.usage.beginObservation()
                    try {
                        r.usage.noteTypedUsage(10, 2, 12)
                        out.text("partial")
                        started.getOrPut(r.target.assistantMessageId) { CompletableDeferred() }.complete(Unit)
                        // Runs until stopped, like a reply still thinking.
                        awaitCancellation()
                    } finally { r.usage.finishObservation() }
                }
            }, { TokenPricingCatalog(it.assignment.modelId) }, worker = Dispatchers.IO)
        }
    }

    @After fun tearDown() {
        controller.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun deletionFreesTheNextTurnAtOnceAndTheLateResultOnlyReachesTheUsageLog() {
        val (user, row) = turn()
        host.messages += user
        host.messages += row
        assertTrue(controller.regenerate(1))
        waitUntil { startedFor(row).isCompleted }
        assertTrue(controller.busy)

        val removed = ArrayList(host.messages)
        controller.abandonForDeletion()
        controller.keepDeletedGroups(removed)
        host.messages.clear()
        // No waiting for the stopped request: a new message can be sent now.
        assertFalse(controller.busy)

        waitUntil { host.deletedRows.isNotEmpty() }
        assertTrue("a deleted group never returns to the chat", host.messages.isEmpty())
        val settled = groupOf(host.deletedRows.last())
        val attempts = settled.slots.flatMap { it.versions }.flatMap { it.attempts }
        assertTrue(attempts.none { it.state == MessageCompletionState.STREAMING })
        assertTrue("the stopped request's usage is kept", attempts.any { it.usageRecord != null })
        assertFalse(controller.busy)
    }

    @Test fun theAbandonedOperationsCleanupCannotResetANewerTurn() {
        val (earlierUser, earlierRow) = turn()
        val (laterUser, laterRow) = turn()
        host.messages += listOf(earlierUser, earlierRow, laterUser, laterRow)
        assertTrue(controller.regenerate(3))
        waitUntil { startedFor(laterRow).isCompleted }

        // Delete the later turn while its regeneration is still running (the
        // rows as the chat holds them now).
        val removed = host.messages.subList(2, 4).toList()
        controller.abandonForDeletion()
        controller.keepDeletedGroups(removed)
        host.messages.removeAll(removed)
        assertFalse(controller.busy)

        // A new operation starts immediately on the remaining turn.
        assertTrue(controller.regenerate(1))
        waitUntil { startedFor(earlierRow).isCompleted }
        // The abandoned operation finishes winding down meanwhile.
        waitUntil { host.deletedRows.isNotEmpty() }
        assertTrue("the newer turn still owns the controls", controller.busy)
        assertEquals(2, host.messages.size)
        assertEquals(groupOf(earlierRow).groupId, groupOf(host.messages[1]).groupId)
    }

    @Test fun deletionDuringRegenerationSetupStopsItsRequestBeforeItIsSent() {
        val (earlierUser, earlierRow) = turn()
        val (laterUser, laterRow) = turn()
        host.messages += listOf(earlierUser, earlierRow, laterUser, laterRow)
        // The regeneration is still being set up (its read waits on the main
        // thread) when the deletion arrives.
        setupReading = CountDownLatch(1)
        assertTrue(controller.regenerate(3))
        assertTrue(setupReading.await(10, TimeUnit.SECONDS))
        val removed = host.messages.subList(2, 4).toList()
        controller.abandonForDeletion()
        controller.keepDeletedGroups(removed)
        host.messages.removeAll(removed)
        assertFalse(controller.busy)
        // A new operation starts at once, before the abandoned one has ended.
        assertTrue(controller.regenerate(1))
        waitUntil { startedFor(earlierRow).isCompleted }
        waitUntil { host.deletedRows.isNotEmpty() }
        // The deleted turn's request was stopped before reaching a provider.
        assertFalse(started[groupOf(laterRow).assistantMessageId]?.isCompleted == true)
        val settled = groupOf(host.deletedRows.last()).slots.flatMap { it.versions }.flatMap { it.attempts }
        assertTrue(settled.none { it.state == MessageCompletionState.STREAMING })
        // The abandoned operation's cleanup did not reset the newer one.
        assertTrue("the newer turn still owns the controls", controller.busy)
        assertEquals(2, host.messages.size)
    }
}
