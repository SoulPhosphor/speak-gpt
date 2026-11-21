package org.teslasoft.assistant.conversation.parallel

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
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
}
