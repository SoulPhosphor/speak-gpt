package org.teslasoft.assistant.preferences.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistPrompt

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TranscriptTurnVisibilityTest {

    private fun turn(role: String, content: String, id: String? = null): JSONObject =
        JSONObject().put("role", role).put("content", content).also {
            if (id != null) TranscriptTurnVisibility.stamp(it, id, hidden = false)
        }

    private fun mark(id: String, role: String, text: String, hidden: Boolean) =
        MemoryStore.TranscriptMessageMark(id, role, text, hidden)

    private fun record(content: String) = TranscriptRecord(
        transcriptId = "t-1", chatId = "c", companionId = null, worldId = null,
        roleplayCharacterId = null, userPersonaId = null, source = "live",
        startedAt = null, endedAt = null, content = content, modelTag = null,
        quickSettingsJson = null, reviewStatus = "pending", processedAt = null
    )

    @Test fun hidingOneMessageKeepsTheOtherSideOfTheExchangeForMemory() {
        val content = JSONArray()
            .put(turn("user", "I moved to Lisbon.", "u1"))
            .put(turn("assistant", "Lisbon is lovely.", "a1"))
            .toString()

        val hidden = TranscriptTurnVisibility.withMessageHidden(
            content, mark("a1", "assistant", "Lisbon is lovely.", hidden = true), allowTextMatch = false
        )!!
        val rendered = ArchivistPrompt.userMessage("Chat", null, listOf(record(hidden))).text
        assertTrue(rendered.contains("User: I moved to Lisbon."))
        assertFalse(rendered.contains("Lisbon is lovely."))

        val restored = TranscriptTurnVisibility.withMessageHidden(
            hidden, mark("a1", "assistant", "Lisbon is lovely.", hidden = false), allowTextMatch = false
        )!!
        assertTrue(
            ArchivistPrompt.userMessage("Chat", null, listOf(record(restored))).text
                .contains("Assistant: Lisbon is lovely.")
        )
    }

    @Test fun unchangedContentReportsNoUpdate() {
        val content = JSONArray().put(turn("user", "hello", "u1")).toString()
        assertNull(
            TranscriptTurnVisibility.withMessageHidden(
                content, mark("u1", "user", "hello", hidden = false), allowTextMatch = false
            )
        )
        assertNull(
            TranscriptTurnVisibility.withMessageHidden(
                content, mark("other", "user", "hello", hidden = true), allowTextMatch = true
            )
        )
    }

    @Test fun olderTurnsWithoutAnIdMatchByTextAndReceiveTheId() {
        val content = JSONArray().put(turn("user", "remember this")).toString()
        val m = mark("u9", "user", "remember this", hidden = true)
        assertEquals(1, TranscriptTurnVisibility.textMatchCount(content, m))
        val updated = JSONArray(TranscriptTurnVisibility.withMessageHidden(content, m, allowTextMatch = true)!!)
        val t = updated.getJSONObject(0)
        assertEquals("u9", t.getString(TranscriptTurnVisibility.MESSAGE_ID))
        assertTrue(TranscriptTurnVisibility.isHidden(t))
    }

    @Test fun textMatchIsNotUsedWhenTheCallerFoundSeveralCandidates() {
        val content = JSONArray().put(turn("user", "ok")).put(turn("user", "ok")).toString()
        val m = mark("u1", "user", "ok", hidden = true)
        assertEquals(2, TranscriptTurnVisibility.textMatchCount(content, m))
        assertNull(TranscriptTurnVisibility.withMessageHidden(content, m, allowTextMatch = false))
    }
}
