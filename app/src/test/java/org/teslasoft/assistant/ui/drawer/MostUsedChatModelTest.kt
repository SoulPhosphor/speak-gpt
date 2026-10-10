package org.teslasoft.assistant.ui.drawer

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.usage.*
import org.teslasoft.assistant.ui.drawer.MostUsedChatModel.Request

class MostUsedChatModelTest {
    @Test fun `request count wins over the most recently selected model`() {
        assertEquals("Gemma 4", MostUsedChatModel.select(listOf(
            Request("1", "Gemma 4", 10), Request("2", "Gemma 4", 20),
            Request("3", "Other model", 30)
        )))
    }

    @Test fun `receipt enrichment of one request cannot count it twice`() {
        assertEquals("Other model", MostUsedChatModel.select(listOf(
            Request("same-request", "Gemma 4", 10), Request("same-request", "Gemma 4", 10),
            Request("other-request", "Other model", 20)
        )))
    }

    @Test fun `model spelling and whitespace do not split counts`() {
        assertEquals("gemma 4", MostUsedChatModel.select(listOf(
            Request("1", " Gemma 4 ", 10), Request("2", "gemma 4", 20),
            Request("3", "Other model", 30)
        )))
    }

    @Test fun `ties select the most recently used model with deterministic equal-time ordering`() {
        assertEquals("Gemma 4", MostUsedChatModel.select(listOf(
            Request("1", "Other model", 10), Request("2", "Gemma 4", 20)
        )))
        assertEquals("Other model", MostUsedChatModel.select(listOf(
            Request("1", "Gemma 4", 10), Request("2", "Other model", 10)
        )))
    }

    @Test fun `missing model history has no invented winner`() {
        assertNull(MostUsedChatModel.select(emptyList()))
        assertNull(MostUsedChatModel.select(listOf(Request("1", " ", 10))))
    }

    private fun entry(id: String, model: String, category: UsageCategory = UsageCategory.CHAT, messageId: String? = "same-reply") =
        UsageLogEntry(id, category, messageId, 100L, TurnUsageRecord(model, "provider"))

    @Test fun `only conversation requests count including regenerated and deleted replies`() {
        val stored = UsageLogState(true, listOf(
            entry("original", "Gemma 4"), entry("regenerated", "Gemma 4"),
            entry("other", "Other model", messageId = "deleted-reply"),
            entry("summary1", "Other model", UsageCategory.SUMMARIZING),
            entry("summary2", "Other model", UsageCategory.SUMMARIZING),
            entry("tts", "Other model", UsageCategory.TTS),
            entry("stt", "Other model", UsageCategory.STT),
            entry("image", "Other model", UsageCategory.IMAGE_GENERATION)
        ))
        assertEquals("Gemma 4", MostUsedChatModel.select(stored, emptyList()))
        assertEquals(8, stored.entries.size)
        assertTrue(stored.seeded)
    }

    @Test fun `legacy replies count without double counting a logged reply or an incomplete reply`() {
        val stored = UsageLogState(true, listOf(entry("logged", "Gemma 4")))
        val replies = listOf(
            mapOf<String, Any>("isBot" to true, "message_id" to "same-reply", "responseModel" to "Other model"),
            mapOf<String, Any>("isBot" to true, "message_id" to "old-reply", "responseModel" to "Gemma 4"),
            mapOf<String, Any>("isBot" to true, "responseModel" to "Other model", "state" to "streaming"),
            mapOf<String, Any>("isBot" to false, "responseModel" to "Other model")
        )
        assertEquals("Gemma 4", MostUsedChatModel.select(stored, replies))
    }

    @Test fun `legacy regenerated snapshots count individually without counting the canonical reply twice`() {
        val message = mapOf<String, Any>("isBot" to true, "responseModel" to "Gemma 4",
            "variants" to """[{"responseModel":"Gemma 4","messageTime":"10"},
                {"responseModel":"Claude","messageTime":"20"}]""")
        // A tie uses the latest snapshot, even when an older one is canonical.
        assertEquals("Claude", MostUsedChatModel.select(UsageLog.EMPTY, listOf(message)))
        val moreGemma = message + ("variants" to """[{"responseModel":"Gemma 4","messageTime":"10"},
            {"responseModel":"Gemma 4","messageTime":"20"},
            {"responseModel":"Claude","messageTime":"30"}]""") + ("responseModel" to "Claude")
        assertEquals("Gemma 4", MostUsedChatModel.select(UsageLog.EMPTY, listOf(moreGemma)))
    }

    @Test fun `legacy versions ignore incomplete snapshots even when the current reply is incomplete`() {
        val message = mapOf<String, Any>("isBot" to true, "responseModel" to "Claude", "state" to "streaming",
            "variants" to """[{"responseModel":"Gemma 4","state":"done"},
                {"responseModel":"Claude","state":"interrupted"},
                {"responseModel":"Claude","state":"streaming"}]""")
        assertEquals("Gemma 4", MostUsedChatModel.select(UsageLog.EMPTY, listOf(message)))
    }

    @Test fun `unreadable legacy versions fall back to the canonical reply`() {
        for (variants in listOf("not json", "[]", "[null]")) {
            val message = mapOf<String, Any>("isBot" to true, "responseModel" to "Gemma 4", "variants" to variants)
            assertEquals("Gemma 4", MostUsedChatModel.select(UsageLog.EMPTY, listOf(message)))
        }
    }

    @Test fun `pre-log durable request history is seeded only in memory`() {
        val records = listOf(TurnUsageRecord("Gemma 4", "provider"), TurnUsageRecord("Gemma 4", "provider"))
        val messages = listOf(mapOf<String, Any>("isBot" to true, "message_id" to "old-reply",
            TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(records)))
        val stored = UsageLog.EMPTY
        assertEquals("Gemma 4", MostUsedChatModel.select(stored, messages))
        assertFalse(stored.seeded)
        assertTrue(stored.entries.isEmpty())
    }
}
