package org.teslasoft.assistant.conversation.parallel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.backup.readable.ReadableChatFormats

class ParallelScopeAndPrivacyTest {
    @Test fun committedLegacyRenameMovesOnlyTheOriginalLiveScope() {
        val original = ParallelChatScope("fixture-old")
        val restored = ParallelChatScope("fixture-copy")
        val target = ResponseGroupTarget("fixture-old", "same-assistant-id", "same-group-id")
        ParallelChatScopes.rename("fixture-old", "fixture-renamed")
        assertEquals("fixture-renamed", original.target(target).chatId)
        assertEquals("fixture-copy", restored.target(target.copy(chatId = "fixture-copy")).chatId)
        assertEquals(target.groupId, original.target(target).groupId)
        try { restored.target(target); fail("A restored copy cannot own an original callback") } catch (_: IllegalArgumentException) { }
    }

    @Test fun readableExportFiltersThinkingInsideToolMessagesAndPreservesPermanentIds() {
        val gson = Gson()
        val rows: List<HashMap<String, Any>> = gson.fromJson(javaClass.classLoader!!
            .getResource("response-groups/history.json")!!.readText(), object : TypeToken<List<HashMap<String, Any>>>() {}.type)
        val row = rows[2]
        val group = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group
        val slot = group.slots[1]
        val version = slot.versions.last()
        val assistant = """{"role":"assistant","content":"fixture","tool_calls":[{"id":"call","type":"function","function":{"name":"create_image","arguments":"{}"}}],"reasoning_details":[{"data":"private-fixture-reasoning"}]}"""
        val changed = group.copy(slots = group.slots.map { s -> if (s != slot) s else s.copy(versions = s.versions.map { v ->
            if (v != version) v else v.copy(attempts = v.attempts.mapIndexed { index, a ->
                if (index != v.attempts.lastIndex) a else a.copy(toolAssistantMessage = assistant)
            })
        }) })
        val installed = ResponseGroupEdits.install(row, changed)
        val names = ReadableChatFormats.SpeakerNames("Companion", "User", "Companion")
        val hidden = gson.toJson(ReadableChatFormats.jsonRecords(listOf(installed), names, false))
        assertFalse(hidden.contains("private-fixture-reasoning"))
        assertTrue(hidden.contains(version.attempts.last().requestId))
        val included = gson.toJson(ReadableChatFormats.jsonRecords(listOf(installed), names, true))
        assertTrue(included.contains("private-fixture-reasoning"))
        assertFalse(included.contains("usageRecord"))
    }
    @Test fun readableExportSanitizesPreparedSnapshotThinkingWithoutChangingUserContentOrSavedEvidence() {
        val literal = """{"role":"assistant","content":"quoted","reasoning":"user-authored literal"}"""
        val assistant = ParallelChatSnapshot.message(JsonParser.parseString(
            """{"role":"assistant","content":"fixture answer","reasoning":"private-snapshot-reasoning","reasoning_content":"private-snapshot-content","reasoning_details":[{"type":"reasoning.encrypted","data":"private-snapshot-encrypted"}]}"""
        ).asJsonObject)
        val (row, original) = snapshotRow(assistant, ParallelChatSnapshot.text("user", literal), wire = true)
        val names = ReadableChatFormats.SpeakerNames("Companion", "User", "Companion")
        val hidden = ReadableChatFormats.jsonRecords(listOf(row), names, false)
        val clean = (ResponseGroupCodec.read(hidden.single()) as ResponseGroupRead.Valid).group
        val cleanAssistant = JsonParser.parseString(clean.input.parts.single {
            it.partId == clean.input.contextPartIds.single()
        }.text).asJsonObject
        for (key in listOf("reasoning", "reasoning_content", "reasoning_details")) assertFalse(cleanAssistant.has(key))
        assertEquals("fixture answer", cleanAssistant.get("content").asString)
        assertEquals(literal, JsonParser.parseString(clean.input.parts.single {
            it.partId == clean.input.currentTurnPartIds.single()
        }.text).asJsonObject.get("content").asString)
        assertEquals(original.slots.flatMap { it.versions.flatMap { v -> v.attempts.map { a -> a.requestId } } },
            clean.slots.flatMap { it.versions.flatMap { v -> v.attempts.map { a -> a.requestId } } })
        val included = ReadableChatFormats.jsonRecords(listOf(row), names, true)
        val full = (ResponseGroupCodec.read(included.single()) as ResponseGroupRead.Valid).group
        val fullAssistant = JsonParser.parseString(full.input.parts.single {
            it.partId == full.input.contextPartIds.single()
        }.text).asJsonObject
        assertEquals("private-snapshot-reasoning", fullAssistant.get("reasoning").asString)
        assertEquals("private-snapshot-content", fullAssistant.get("reasoning_content").asString)
        assertEquals("private-snapshot-encrypted", fullAssistant.getAsJsonArray("reasoning_details")[0].asJsonObject.get("data").asString)
        assertEquals(original, (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group)
    }

    @Test fun readableExportLeavesOrdinaryAndNonwireSnapshotTextLiteral() {
        val literal = """{"role":"assistant","content":"quoted","reasoning":"user-authored literal"}"""
        val (row, original) = snapshotRow(ParallelInputPart.Text(literal), ParallelInputPart.Text("literal current input"), wire = false)
        val names = ReadableChatFormats.SpeakerNames("Companion", "User", "Companion")
        val hidden = ReadableChatFormats.jsonRecords(listOf(row), names, false)
        val clean = (ResponseGroupCodec.read(hidden.single()) as ResponseGroupRead.Valid).group
        assertEquals(literal, clean.input.parts.single { it.partId == clean.input.contextPartIds.single() }.text)
        assertEquals(original, clean)
        val ordinary = ReadableChatFormats.jsonRecords(listOf(mapOf<String, Any>("isBot" to false,
            "message" to literal, "text" to literal)), names, false).single()
        assertEquals(literal, ordinary["message"])
        assertEquals(literal, ordinary["text"])
    }

    private fun snapshotRow(context: ParallelInputPart, current: ParallelInputPart, wire: Boolean): Pair<Map<String, Any>, ResponseGroup> {
        val group = ParallelInput.freeze(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "full_history",
            listOf(context), listOf(current), (0 until 2).map { index ->
                ParallelSlotInput(ResponseModelAssignment("fixture-model", "Fixture", "fixture-endpoint-" + index,
                    "https://fixture.invalid/v1/", "chat_completions", emptyMap(),
                    if (wire) mapOf(ParallelChatSnapshot.OPTIONS to "{}") else emptyMap(), emptyMap(), emptyMap()),
                    listOf(context, current))
            }, 0, 100)
        val row = ResponseGroupEdits.install(mapOf("message_id" to group.assistantMessageId, "isBot" to true,
            "message" to ""), group)
        return row to group
    }

}
