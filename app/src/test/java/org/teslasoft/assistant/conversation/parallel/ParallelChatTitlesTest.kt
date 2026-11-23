package org.teslasoft.assistant.conversation.parallel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.ChatTitleExtractor
import java.util.UUID

class ParallelChatTitlesTest {
    private fun id() = UUID.randomUUID().toString()
    private fun fixture(host: String = "https://fixture.invalid/v1/"): Pair<ResponseGroup, ApiEndpointObject> {
        val system = ParallelChatSnapshot.text("system", "frozen persona")
        val user = ParallelChatSnapshot.text("user", "Weekend hiking plans")
        val endpoints = (0..1).map { ApiEndpointObject("Fixture $it", host, "fixture-key-$it", id = "endpoint-$it") }
        val slots = endpoints.mapIndexed { i, endpoint ->
            val assignment = ResponseModelAssignment("fixture-$i", "Fixture $i", endpoint.id, endpoint.host, "chat_completions",
                emptyMap(), emptyMap(), emptyMap(), emptyMap())
            val options = JsonObject().apply {
                addProperty("model", assignment.modelId); addProperty("stream", true); addProperty("max_tokens", 900)
                add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
                add("provider", JsonObject().apply { add("only", JsonArray().apply { add("provider-$i") }) })
                add("tools", JsonArray()); addProperty("tool_choice", "auto")
                add("messages", JsonArray())
            }
            ParallelSlotInput(ParallelChatSnapshot.assignment(assignment, endpoint, options.toString(), showReasoning = true), listOf(system, user))
        }
        return ParallelInput.freeze(id(), id(), "full_history", listOf(system), listOf(user), slots, 1, 100L) to endpoints.first()
    }

    @Test fun firstModelNamesTheChatEvenWhenTheSecondModelIsPrimaryAndBrowsed() {
        val (group, endpoint) = fixture()
        val original = ResponseGroupCodec.encode(group)
        val request = ParallelChatTitles.prepare("chat", group, { null })
        val root = JsonParser.parseString(ParallelChatSnapshot.body(request)).asJsonObject
        assertEquals("fixture-0", root.get("model").asString)
        assertEquals("provider-0", root.getAsJsonObject("provider").getAsJsonArray("only").single().asString)
        assertEquals(group.slots.first().slotId, request.slotId)
        assertEquals(group.slots[1].slotId, group.primarySlotId)
        assertEquals(group.slots[1].slotId, group.foregroundSlotId)
        assertEquals(128, root.get("max_tokens").asInt)
        assertFalse(root.get("stream").asBoolean)
        assertFalse(root.has("tools")); assertFalse(root.has("tool_choice")); assertFalse(root.has("stream_options"))
        assertEquals(listOf("frozen persona", ChatTitleExtractor.TITLE_INSTRUCTION, "Weekend hiking plans"),
            root.getAsJsonArray("messages").map { it.asJsonObject.get("content").asString })
        assertEquals(original, ResponseGroupCodec.encode(group))
        assertTrue(ParallelChatTitles.endpointMatches(request.assignment, endpoint))
        assertFalse(group.slots.flatMap { it.versions }.flatMap { it.attempts }.any { it.requestId == request.requestId })
        assertNotEquals(request.requestId, ParallelChatTitles.prepare("chat", group, { null }).requestId)
    }

    @Test fun changedEndpointCannotRedirectTheFrozenTitleRequest() {
        val (group, endpoint) = fixture()
        val assignment = group.slots.first().assignment
        assertTrue(ParallelChatTitles.endpointMatches(assignment, endpoint))
        endpoint.chatEndpoint = "/different"
        assertFalse(ParallelChatTitles.endpointMatches(assignment, endpoint))
        endpoint.chatEndpoint = assignment.parameters.getValue("parallel_chat_path")
        endpoint.host = "https://other.invalid/v1"
        assertFalse(ParallelChatTitles.endpointMatches(assignment, endpoint))
        endpoint.host = assignment.endpoint
        endpoint.apiKey = ""
        assertFalse(ParallelChatTitles.endpointMatches(assignment, endpoint))
    }

    @Test fun textOnlySummarizerInputKeepsTheConversationAndNamingInstruction() {
        val (group, _) = fixture()
        val messages = ParallelChatTitles.textMessages(group)
        assertEquals(listOf("frozen persona", ChatTitleExtractor.TITLE_INSTRUCTION, "Weekend hiking plans"), messages.map { it.content })
    }

    @Test fun summarizerChoiceBypassesChatAiAndMissingSetupNeverFallsBackToIt() = runBlocking {
        val prefs = Preferences(FakeSharedPreferences(), FakeSharedPreferences(), "chat")
        prefs.setUseSummarizerForTitles(true)
        var summaries = 0
        var applied: String? = null
        ParallelChatTitles.run(prefs, "_autoname_fixture", 3, { true }, { true },
            { fail("Chat AI must not be called when the summarizer is selected"); null },
            { summaries++; "Summary Model Title" }, { title, old ->
                assertEquals("_autoname_fixture", old); applied = title; true
            })
        assertEquals(1, summaries)
        assertEquals("Summary Model Title", applied)
        assertEquals(1, prefs.getAutoNameAttempts())
        ParallelChatTitles.run(prefs, "_autoname_fixture", 3, { true }, { false },
            { fail("Missing summarizer cannot substitute the chat AI"); null },
            { fail("Unconfigured summarizer cannot be called"); null }, { _, _ -> fail("No title to save"); false })
        assertEquals(1, prefs.getAutoNameAttempts())
    }

    @Test fun chatAiRetriesOncePerTurnAndUsesExistingSummarizerFallbackOnlyAtTheLimit() = runBlocking {
        val prefs = Preferences(FakeSharedPreferences(), FakeSharedPreferences(), "chat")
        var chats = 0
        var summaries = 0
        var applied: String? = null
        repeat(3) {
            ParallelChatTitles.run(prefs, "_autoname_fixture", 3, { true }, { true },
                { chats++; null }, { summaries++; "Backup Title" }, { title, _ -> applied = title; true })
        }
        assertEquals(3, chats)
        assertEquals(1, summaries)
        assertEquals("Backup Title", applied)
        ParallelChatTitles.run(prefs, "_autoname_fixture", 3, { true }, { true },
            { fail("No fourth request"); null }, { fail("No repeated backup"); null }, { _, _ -> false })
    }

    @Test fun changedTitleSourceDuringTheRequestDiscardsTheOldResult() = runBlocking {
        val prefs = Preferences(FakeSharedPreferences(), FakeSharedPreferences(), "chat")
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<String>()
        var applied = false
        val naming = async(start = CoroutineStart.UNDISPATCHED) {
            ParallelChatTitles.run(prefs, "_autoname_fixture", 3, { true }, { true },
                { started.complete(Unit); reply.await() }, { fail("Old selection cannot start a fallback"); null },
                { _, _ -> applied = true; true })
        }
        started.await()
        prefs.setUseSummarizerForTitles(true)
        reply.complete("Old Chat Model Title")
        naming.await()
        assertFalse(applied)
    }

    @Test fun titleUsesTheOwnedTransportWithoutChangingAnyReplyOrItsReceipts() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val (group, endpoint) = fixture(server.url("/v1/").toString())
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"fixture-title","choices":[{"message":{"role":"assistant","content":"Weekend Hiking Plans"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}"""))
            val request = ParallelChatTitles.prepare("chat", group, { null })
            val response = StringBuilder()
            ParallelChatTransport { assignment ->
                assertTrue(ParallelChatTitles.endpointMatches(assignment, endpoint)); endpoint.apiKey
            }.execute(request, object : ParallelOutput {
                override suspend fun text(delta: String) { response.append(delta) }
                override suspend fun reasoning(delta: String) {}
                override suspend fun warning(value: String) {}
                override suspend fun tools(assistantMessage: String) { fail("Titles must not call tools") }
            })
            assertEquals("Weekend Hiking Plans", ChatTitleExtractor.extract(response.toString()))
            val sent = server.takeRequest()
            assertEquals("Bearer fixture-key-0", sent.getHeader("Authorization"))
            assertEquals("/v1/chat/completions", sent.path)
            assertEquals(13, request.usage.snapshot().counts.totalTokens)
            assertTrue(group.slots.all { it.versions.single().fields["message"] == "" })
            assertTrue(group.slots.all { it.versions.single().attempts.single().usageRecord == null })
        } finally { server.shutdown() }
    }
}
