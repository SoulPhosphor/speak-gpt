package org.teslasoft.assistant.conversation.parallel

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.*
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.chatstore.ChatStorageAuthority
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.ui.activities.TokenPricingDetailsActivity
import org.teslasoft.assistant.usage.*
import java.util.UUID

/** Real guarded encrypted saves, persisted usage-log reconciliation, and the existing rendered screen.
 * Every generation and price lookup is a deterministic fake; no provider credentials or calls.
 */
@RunWith(AndroidJUnit4::class)
class ParallelUsageScreenInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var chatId: String
    private val userId = UUID.randomUUID().toString()
    private val assistantId = UUID.randomUUID().toString()

    @Before fun setup() {
        PortableRestoreProcessGate.clear(context)
        ChatStorageAuthority.clearForTest(context)
        chatId = UUID.randomUUID().toString()
        assertTrue(SecurePrefs.get(context, "chat_list").edit().putString("data", JSONArray().put(JSONObject()
            .put("id", chatId).put("name", "Synthetic parallel accounting")).toString()).commit())
        assertEquals(ChatStorageHealth.WriteOutcome.OK, ChatPreferences.getChatPreferences().saveChatHistory(context, chatId,
            listOf(hashMapOf<String, Any>("message_id" to userId, "isBot" to false, "message" to "fixture input"),
                hashMapOf<String, Any>("message_id" to assistantId, "isBot" to true, "message" to "", "state" to "streaming")),
            synchronous = true))
    }

    @After fun cleanup() {
        SecurePrefs.get(context, "chat_$chatId").edit().clear().commit()
        SecurePrefs.get(context, "settings.$chatId").edit().clear().commit()
        SecurePrefs.get(context, "chat_list").edit().clear().commit()
        PortableRestoreProcessGate.clear(context)
    }

    @Test fun fourOwnedRequestsIncludingFailedAndStoppedChargesRenderOnce() = verify(unknownLast = false)
    @Test fun unknownSiblingChargeRendersNotReportedRatherThanKnownSubset() = verify(unknownLast = true)

    @Test fun ownedImageRegistryFailuresKeepFourDistinctJobsAndDoNotAppendSiblingRows() = runBlocking {
        withTimeout(60_000) {
            val before = ChatPreferences.getChatPreferences().getChatByIdResult(context, chatId,
                DamagedChatOperation.OPENING_A_CHAT).messages
            val received = java.util.concurrent.ConcurrentHashMap<String, String>()
            val jobs = withContext(Dispatchers.Main.immediate) {
                (0 until 4).map { index ->
                    val imageId = UUID.randomUUID().toString()
                    val ownerKey = "fixture-owned-$index-$chatId"
                    val request = org.teslasoft.assistant.imagegen.ImageGenerationRequest("fixture image",
                        org.teslasoft.assistant.imagegen.ImageShape.AUTOMATIC,
                        org.teslasoft.assistant.imagegen.ImageQuality.AUTOMATIC, "", "", "fixture description")
                    val job = org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.start(context, chatId, null,
                        request, org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.Origin.TOOL,
                        ownerKey = ownerKey, imageId = imageId, ownedTerminal = { terminal ->
                            received[imageId] = terminal.metadata.toJson()
                        })
                    assertSame(job, org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.start(context, chatId, null,
                        request, org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.Origin.TOOL,
                        ownerKey = ownerKey, imageId = imageId, ownedTerminal = { fail("Duplicate delivery") }))
                    job
                }
            }
            assertEquals(4, jobs.map { it.imageId }.distinct().size)
            jobs.forEach { assertTrue(it.await() is org.teslasoft.assistant.imagegen.ImageGenerationJobRegistry.Terminal.Failed) }
            assertEquals(4, received.size)
            assertEquals(before, ChatPreferences.getChatPreferences().getChatByIdResult(context, chatId,
                DamagedChatOperation.OPENING_A_CHAT).messages)
        }
    }

    private fun verify(unknownLast: Boolean) = runBlocking {
        withTimeout(60_000) {
            val input = ParallelInputPart.Text("fixture input")
            val group = ParallelInput.freeze(userId, assistantId, "full_history", emptyList(), listOf(input),
                (0 until 4).map { i -> ParallelSlotInput(ResponseModelAssignment("fixture-model", "Identical label",
                    "fixture-endpoint-$i", "https://endpoint-$i.invalid/v1/", "chat_completions", emptyMap(),
                    emptyMap(), emptyMap(), emptyMap()), listOf(input)) }, 0, 100L)
            val gates = List(4) { CompletableDeferred<Unit>() }
            val arrived = Channel<Int>(Channel.UNLIMITED)
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = ParallelRequestCoordinator(owner, StoredParallelGroups(context, ParallelChatScope(chatId)) {
                    // This fresh synthetic chat has no transcript/summary sources to invalidate.
                    assertEquals(assistantId, it.after["message_id"])
                }, { _, _ -> error("This fixture has no binary Includes") }, { request -> ParallelTransport { r, out ->
                    assertEquals(request.requestId, r.requestId)
                    val index = group.slots.indexOfFirst { it.slotId == r.slotId }
                    if (!(unknownLast && index == 3)) r.usage.noteRawObservation(RawStreamObservation(
                        model = "fixture-model", actualServingProvider = "fixture-provider-${index % 2}",
                        promptTokens = 10 + index, completionTokens = 2, totalTokens = 12 + index,
                        cachedInputTokens = 0, cacheWriteInputTokens = 0,
                        // Fictional API receipts check arithmetic only.
                        totalCost = (index + 1) * 0.01))
                    out.text("answer-$index")
                    out.reasoning("reasoning-$index")
                    arrived.send(index)
                    gates[index].await()
                    if (index == 1) throw ParallelTransportFailure("fixture_failure")
                } }, { TokenPricingCatalog(it.assignment.modelId) })
                val run = engine.dispatch(chatId, group) {}
                repeat(4) { arrived.receive() }
                engine.cancel(run.requestIds[2])
                for (index in listOf(3, 1, 0)) gates[index].complete(Unit)
                run.await()
                assertTrue(engine.unsavedRequestIds().isEmpty())
                val rows = ChatPreferences.getChatPreferences().getChatByIdResult(context, chatId,
                    DamagedChatOperation.OPENING_A_CHAT).messages
                val saved = (ResponseGroupCodec.read(rows.last()) as ResponseGroupRead.Valid).group
                assertEquals(listOf("done", "failed", "stopped", "done"), saved.slots.map { it.versions.single().fields["state"] })
                assertEquals(run.requestIds, ResponseGroupEdits.usage(saved).map { it.requestId })
                val prefs = Preferences.getPreferences(context, chatId)
                val log = UsageLogStore.seed(prefs, rows)
                assertEquals(log, UsageLogStore.seed(prefs, rows))
                assertEquals(4, UsageLogStore.read(prefs).entries.size)
                val summary = log.summarize(rows, { error("No legacy estimate is applicable") })
                assertEquals(4, summary.groups.sumOf { it.recordCount })
                assertEquals(unknownLast, summary.hasUnknownCost)
                assertEquals(if (unknownLast) 0.06 else 0.10, summary.totalCost, 0.000001)
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val activity = instrumentation.startActivitySync(Intent(context, TokenPricingDetailsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(TokenPricingDetailsActivity.EXTRA_USAGE_SUMMARY, TokenUsageAccounting.encodeSummary(summary))
                    .putExtra(TokenPricingDetailsActivity.EXTRA_USAGE_SECTIONS,
                        UsageLog.encodeSections(log.sections(rows) { error("No legacy estimate") })))
                try {
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertEquals(if (unknownLast) UsageValueFormatter.NOT_REPORTED else "\$0.1000",
                            activity.findViewById<TextView>(R.id.conversation_total_cost).text.toString())
                        val providers = if (unknownLast) 3 else 2
                        assertEquals(activity.getString(R.string.usage_conversation_meta, 4, 1, providers),
                            activity.findViewById<TextView>(R.id.conversation_usage_meta).text.toString())
                        val sections = activity.findViewById<LinearLayout>(R.id.pricing_sections)
                        assertEquals(2, sections.childCount) // Existing section pill + one model card.
                        val card = sections.getChildAt(1)
                        assertEquals("fixture-model", card.findViewById<TextView>(R.id.model_name).text.toString())
                        assertEquals(providers, card.findViewById<LinearLayout>(R.id.provider_cards).childCount)
                        assertEquals(if (unknownLast) UsageValueFormatter.NOT_REPORTED else "\$0.1000",
                            card.findViewById<TextView>(R.id.model_total_cost).text.toString())
                    }
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            } finally { owner.cancel() }
        }
    }
}
