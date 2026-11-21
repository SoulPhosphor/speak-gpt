package org.teslasoft.assistant.conversation.parallel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.imagegen.*
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.usage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Loopback HTTP only. No provider credentials, remote model calls or billable images. */
class ParallelChatTransportTest {
    private fun id() = UUID.randomUUID().toString()
    private class Store(val group: ResponseGroup) : ParallelGroupPersistence {
        @Volatile var rows: List<Map<String, Any>> = listOf(
            mapOf("message_id" to group.userMessageId, "isBot" to false, "message" to "fixture input"),
            mapOf("message_id" to group.assistantMessageId, "isBot" to true, "message" to ""))
        override fun read(target: ResponseGroupTarget) =
            (ResponseGroupCodec.read(rows.last()) as ResponseGroupRead.Valid).group
        override fun update(target: ResponseGroupTarget, transform: (ResponseGroup?) -> ResponseGroup): ResponseGroup = synchronized(this) {
            val old = (ResponseGroupCodec.read(rows.last()) as? ResponseGroupRead.Valid)?.group
            val next = transform(old)
            rows = ResponseGroupHistory.replace(target.chatId, rows, target, rows.last()["responseGroup"] as String?, next).messages
            next
        }
    }
    private fun assignment(server: MockWebServer, index: Int, stream: Boolean, tools: Boolean = false): ResponseModelAssignment {
        val endpoint = ApiEndpointObject("fixture", server.url("/v1/").toString(), "fixture-only-$index", id = "endpoint-$index")
        val original = ResponseModelAssignment("same-model", "Same label", endpoint.id, endpoint.host, "chat_completions",
            emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val options = JsonObject().apply {
            addProperty("model", original.modelId); addProperty("stream", stream); addProperty("temperature", index / 10.0)
            add("provider", JsonObject().apply { add("only", JsonArray().apply { add("route-$index") }) })
            add("messages", JsonArray())
            if (tools) add("tools", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "function"); add("function", JsonObject().apply {
                    addProperty("name", "create_image"); addProperty("description", "fixture image tool")
                    add("parameters", JsonObject().apply { addProperty("type", "object") })
                })
            }) })
        }
        return ParallelChatSnapshot.assignment(original, endpoint, options.toString(), showReasoning = true)
    }
    private fun response(index: Int, stream: Boolean): MockResponse {
        val message = JsonObject().apply { addProperty("content", "answer-$index"); addProperty("reasoning", "reason-$index") }
        val frame = JsonObject().apply {
            addProperty("id", "fixture-$index"); addProperty("model", "same-model"); addProperty("provider", "provider-$index")
            add("choices", JsonArray().apply { add(JsonObject().apply {
                addProperty("index", 0); add(if (stream) "delta" else "message", message); addProperty("finish_reason", "stop")
            }) })
            add("usage", JsonObject().apply { addProperty("prompt_tokens", 20 + index); addProperty("completion_tokens", 4)
                addProperty("total_tokens", 24 + index); addProperty("cost", (index + 1) * 0.01) })
        }
        return MockResponse().setHeader("Content-Type", if (stream) "text/event-stream" else "application/json")
            .setBody(if (stream) "data: $frame\n\ndata: [DONE]\n\n" else frame.toString())
    }

    @Test fun fourRealHttpRequestsKeepFrozenIncludesEndpointsReasoningAndReceiptsIsolated() = runBlocking {
        withTimeout(30_000) {
            val servers = List(4) { MockWebServer() }
            val gates = List(4) { CountDownLatch(1) }
            val arrivals = Channel<Pair<Int, RecordedRequest>>(Channel.UNLIMITED)
            servers.forEachIndexed { i, server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        arrivals.trySend(i to request)
                        check(gates[i].await(20, TimeUnit.SECONDS))
                        return response(i, i % 2 == 0)
                    }
                }
                server.start()
            }
            try {
                val imageBytes = "frozen image fixture".toByteArray()
                val pdfBytes = "%PDF frozen fixture".toByteArray()
                fun ref(bytes: ByteArray, store: String, mime: String, ext: String): ResponseAttachment {
                    val digest = ResponseAttachment.digest(bytes)
                    return ResponseAttachment(store, "$digest.$ext", mime, bytes.size.toLong(), digest)
                }
                val image = ref(imageBytes, "image", "image/png", "png")
                val pdf = ref(pdfBytes, "pdf", "application/pdf", "pdf")
                val context = ParallelChatSnapshot.text("assistant", "canonical earlier answer")
                val user = ParallelChatSnapshot.message(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "parallel-snapshot: ordinary text with manual memory") })
                        add(JsonObject().apply { addProperty("type", "image_url"); add("image_url", JsonObject().apply {
                            addProperty("url", ParallelChatSnapshot.attachmentReference(image)) }) })
                        add(JsonObject().apply { addProperty("type", "file"); add("file", JsonObject().apply {
                            addProperty("filename", "original.pdf"); addProperty("file_data", ParallelChatSnapshot.attachmentReference(pdf)) }) })
                    })
                })
                val shared: List<ParallelInputPart> = listOf(context, user, ParallelInputPart.Attachment(image), ParallelInputPart.Attachment(pdf))
                val group = ParallelInput.freeze(id(), id(), "full_history", listOf(context), shared.drop(1),
                    servers.mapIndexed { i, server -> ParallelSlotInput(assignment(server, i, i % 2 == 0), shared) }, 0, 100L)
                val target = ResponseGroupTarget("chat", group.assistantMessageId, group.groupId)
                val store = Store(group)
                val commits = AtomicInteger()
                val http = ParallelChatTransport { a -> "fixture-only-${a.endpointProfileId.substringAfterLast('-')}" }
                val coordinator = ParallelRequestCoordinator(this, store, { _, a -> if (a.store == "image") imageBytes else pdfBytes },
                    { http }, { TokenPricingCatalog(it.assignment.modelId) })
                val run = coordinator.dispatch("chat", group) { commits.incrementAndGet() }
                val seen = List(4) { arrivals.receive() }.sortedBy { it.first }
                assertEquals(1, commits.get())
                seen.forEach { (i, request) ->
                    assertEquals("Bearer fixture-only-$i", request.getHeader("Authorization"))
                    assertEquals("/v1/chat/completions", request.path)
                    val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                    assertEquals(i / 10.0, body.get("temperature").asDouble, 0.00001)
                    assertEquals("route-$i", body.getAsJsonObject("provider").getAsJsonArray("only")[0].asString)
                    val prompt = body.getAsJsonArray("messages").toString()
                    assertTrue(prompt.contains("canonical earlier answer")); assertTrue(prompt.contains("parallel-snapshot: ordinary text with manual memory"))
                    assertTrue(prompt.contains("data:image/png;base64,")); assertTrue(prompt.contains("data:application/pdf;base64,"))
                    assertFalse(prompt.contains(ParallelChatSnapshot.attachmentReference(image)));
                    assertFalse(prompt.contains(ParallelChatSnapshot.attachmentReference(pdf))); assertFalse(prompt.contains("answer-")); assertFalse(prompt.contains("reason-"))
                }
                for (index in listOf(3, 1, 2, 0)) gates[index].countDown()
                run.await()
                val saved = store.read(target)
                saved.slots.forEachIndexed { i, slot ->
                    val version = slot.versions.single()
                    assertEquals("answer-$i", version.fields["message"])
                    assertEquals("reason-$i", version.fields["reasoningText"])
                    assertEquals("done", version.fields["state"])
                    val receipt = TokenUsageAccounting.decodeRecords(version.attempts.single().usageRecord).single()
                    assertEquals(run.requestIds[i], receipt.requestId)
                    assertEquals(20 + i, receipt.inputTokens)
                    assertEquals((i + 1) * 0.01, receipt.totalCost!!, 0.00001)
                    assertEquals("provider-$i", receipt.provider)
                }
                assertEquals(0.10, TokenUsageAccounting.aggregate(ResponseGroupEdits.usage(saved)).totalCost, 0.00001)
                assertTrue(coordinator.unsavedRequestIds().isEmpty())
            } finally { gates.forEach { it.countDown() }; servers.forEach { it.shutdown() } }
        }
    }

    @Test fun toolRejectionHasOneDistinctDurableRetryAndNoAttachmentOrSiblingLoss() = runBlocking {
        withTimeout(30_000) {
            val servers = List(2) { MockWebServer().apply { start() } }
            try {
                servers[0].enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"tools are not supported"},"usage":{"prompt_tokens":7,"completion_tokens":0,"total_tokens":7,"cost":0.03}}"""))
                servers[0].enqueue(response(0, false))
                servers[1].enqueue(response(1, false))
                val current = ParallelChatSnapshot.text("user", "original frozen input")
                val group = ParallelInput.freeze(id(), id(), "last_message_only", emptyList(), listOf(current),
                    servers.mapIndexed { i, s -> ParallelSlotInput(assignment(s, i, false, tools = true), listOf(current)) }, 0, 100)
                val store = Store(group)
                val http = ParallelChatTransport { "fixture-only" }
                val engine = ParallelRequestCoordinator(this, store, { _, _ -> null }, { http }, { TokenPricingCatalog(it.assignment.modelId) })
                val run = engine.dispatch("chat", group) {}
                run.await()
                val saved = store.read(ResponseGroupTarget("chat", group.assistantMessageId, group.groupId))
                val attempts = saved.slots[0].versions.single().attempts
                assertEquals(2, attempts.size); assertNotEquals(attempts[0].requestId, attempts[1].requestId)
                assertEquals(attempts[0].requestId, attempts[1].parentRequestId)
                assertEquals("failed", attempts[0].state); assertEquals("done", attempts[1].state)
                assertEquals(0.03, TokenUsageAccounting.decodeRecords(attempts[0].usageRecord).single().totalCost!!, 0.00001)
                val initial = JsonParser.parseString(servers[0].takeRequest().body.readUtf8()).asJsonObject
                val retry = JsonParser.parseString(servers[0].takeRequest().body.readUtf8()).asJsonObject
                assertTrue(initial.has("tools")); assertFalse(retry.has("tools"))
                assertEquals(initial.get("messages"), retry.get("messages"))
                assertEquals(1, servers[1].requestCount); assertEquals(3, ResponseGroupEdits.usage(saved).size)
            } finally { servers.forEach { it.shutdown() } }
        }
    }

    @Test fun oneImagePerModelUsesGlobalRulesAndPersistsContinuationBeforeExecution() = runBlocking {
        withTimeout(30_000) {
            val servers = List(4) { MockWebServer().apply { start() } }
            try {
                servers.forEachIndexed { i, server ->
                    val arguments = """{"prompt":"image-$i","description":"description-$i"}"""
                    val call = JsonObject().apply { addProperty("id", "tool-$i"); addProperty("type", "function")
                        add("function", JsonObject().apply { addProperty("name", "create_image"); addProperty("arguments", arguments) }) }
                    val message = JsonObject().apply { addProperty("content", "lead-$i"); add("tool_calls", JsonArray().apply { add(call); add(call.deepCopy().apply { addProperty("id", "extra-$i") }) }) }
                    server.enqueue(MockResponse().setBody(JsonObject().apply { addProperty("model", "same-model")
                        add("choices", JsonArray().apply { add(JsonObject().apply { add("message", message); addProperty("finish_reason", "tool_calls") }) }) }.toString()))
                    server.enqueue(response(i, false))
                }
                val current = ParallelChatSnapshot.text("user", "same user turn")
                val group = ParallelInput.freeze(id(), id(), "full_history", emptyList(), listOf(current),
                    servers.mapIndexed { i, server -> ParallelSlotInput(assignment(server, i, false, tools = true), listOf(current)) }, 0, 100)
                val store = Store(group)
                val target = ResponseGroupTarget("chat", group.assistantMessageId, group.groupId)
                val images = AtomicInteger()
                val confirmed = AtomicInteger()
                val rules = ParallelImageRules(true, true, "global-image-endpoint", "global-image-model", ImageShape.AUTOMATIC,
                    ImageQuality.HIGH, mapOf("fixture-option" to "global-value"))
                val tool = ParallelImageTools({ rules }, { _, _ -> confirmed.incrementAndGet(); true }, { owner, imageId, request, record ->
                    val saved = store.read(target).slots.single { it.slotId == owner.slotId }.versions.single().attempts.last()
                    assertEquals(owner.requestId, saved.requestId); assertEquals(imageId, saved.imageJobId)
                    assertEquals("streaming", saved.state); assertNotNull(saved.parentRequestId)
                    assertEquals(rules.endpointId, request.endpointId); assertEquals(rules.modelId, request.modelId)
                    assertEquals(rules.quality, request.defaultQuality); assertEquals(rules.parameters, request.parameters)
                    images.incrementAndGet()
                    record(imageId, """{"imageId":"$imageId","status":"complete"}""")
                    CreateImageTool.successResult(imageId, request.description!!)
                })
                val http = ParallelChatTransport { "fixture-only" }
                val engine = ParallelRequestCoordinator(this, store, { _, _ -> null }, { http }, { TokenPricingCatalog(it.assignment.modelId) }, tools = tool)
                val run = engine.dispatch("chat", group) {}
                run.await()
                val saved = store.read(target)
                assertEquals(4, confirmed.get()); assertEquals(4, images.get())
                assertEquals(8, ResponseGroupEdits.usage(saved).size)
                saved.slots.forEachIndexed { i, slot ->
                    val version = slot.versions.single()
                    assertEquals("lead-$i\nanswer-$i", version.fields["message"])
                    assertEquals(2, version.attempts.size)
                    assertEquals("done", version.attempts.last().state)
                    assertNotNull(version.attempts.last().imageResult)
                    assertNotEquals(version.attempts[0].requestId, version.attempts[1].requestId)
                    servers[i].takeRequest()
                    val followup = JsonParser.parseString(servers[i].takeRequest().body.readUtf8()).asJsonObject
                    assertFalse(followup.has("tools"))
                    val messages = followup.getAsJsonArray("messages")
                    assertEquals("tool-$i", messages[2].asJsonObject.get("tool_call_id").asString)
                    assertTrue(messages[3].asJsonObject.get("content").asString.contains("only one image"))
                    assertEquals(2, servers[i].requestCount)
                }
                assertTrue(engine.unsavedRequestIds().isEmpty())
            } finally { servers.forEach { it.shutdown() } }
        }
    }
    @Test fun cancellingOneRealHttpCallLeavesTheSiblingSavedAndDoesNotRetry() = runBlocking {
        withTimeout(30_000) {
            val servers = List(2) { MockWebServer().apply { start() } }
            try {
                servers[0].enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                servers[1].enqueue(response(1, false))
                val current = ParallelChatSnapshot.text("user", "same frozen input")
                val group = ParallelInput.freeze(id(), id(), "full_history", emptyList(), listOf(current),
                    servers.mapIndexed { i, server -> ParallelSlotInput(assignment(server, i, false), listOf(current)) }, 0, 100)
                val store = Store(group)
                val http = ParallelChatTransport { "fixture-only" }
                val engine = ParallelRequestCoordinator(this, store, { _, _ -> null }, { http }, { TokenPricingCatalog(it.assignment.modelId) })
                val run = engine.dispatch("chat", group) {}
                assertNotNull(servers[0].takeRequest(10, TimeUnit.SECONDS))
                engine.cancel(run.requestIds[0])
                run.await()
                val saved = store.read(ResponseGroupTarget("chat", group.assistantMessageId, group.groupId))
                assertEquals("stopped", saved.slots[0].versions.single().fields["state"])
                assertEquals("done", saved.slots[1].versions.single().fields["state"])
                assertEquals("answer-1", saved.slots[1].versions.single().fields["message"])
                assertEquals(1, servers[0].requestCount); assertEquals(1, servers[1].requestCount)
                assertTrue(engine.unsavedRequestIds().isEmpty())
            } finally { servers.forEach { it.shutdown() } }
        }
    }

    @Test fun truncatedStreamRetainsItsPartialAndReportedChargeWithoutFailingTheSibling() = runBlocking {
        withTimeout(30_000) {
            val servers = List(2) { MockWebServer().apply { start() } }
            try {
                servers[0].enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"half\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7,\"cost\":0.05}}\n\n"))
                servers[1].enqueue(response(1, false))
                val current = ParallelChatSnapshot.text("user", "same frozen input")
                val group = ParallelInput.freeze(id(), id(), "full_history", emptyList(), listOf(current),
                    servers.mapIndexed { i, server -> ParallelSlotInput(assignment(server, i, i == 0), listOf(current)) }, 0, 100)
                val store = Store(group)
                val http = ParallelChatTransport { "fixture-only" }
                val engine = ParallelRequestCoordinator(this, store, { _, _ -> null }, { http }, { TokenPricingCatalog(it.assignment.modelId) })
                engine.dispatch("chat", group) {}.await()
                val saved = store.read(ResponseGroupTarget("chat", group.assistantMessageId, group.groupId))
                assertEquals("failed", saved.slots[0].versions.single().fields["state"])
                assertEquals("half", saved.slots[0].versions.single().fields["message"])
                assertEquals("incomplete_stream", saved.slots[0].versions.single().attempts.single().failure)
                assertEquals("done", saved.slots[1].versions.single().fields["state"])
                assertEquals(0.07, TokenUsageAccounting.aggregate(ResponseGroupEdits.usage(saved)).totalCost, 0.00001)
                assertTrue(engine.unsavedRequestIds().isEmpty())
            } finally { servers.forEach { it.shutdown() } }
        }
    }

}
