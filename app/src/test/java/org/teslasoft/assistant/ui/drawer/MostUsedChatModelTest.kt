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

    @Test fun `newly logged regenerations do not hide older unlogged variants`() {
        for (newModel in listOf("Claude", "Gemma 4")) {
            val record = TurnUsageRecord(newModel, "provider")
            val variants = listOf(
                mapOf("responseModel" to "Gemma 4", "messageTime" to "10"),
                mapOf("responseModel" to "Gemma 4", "messageTime" to "20"),
                mapOf("responseModel" to newModel, "messageTime" to "30",
                    TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(listOf(record)))
            )
            val message = mapOf<String, Any>("isBot" to true, "message_id" to "same-reply",
                "responseModel" to newModel, "variants" to com.google.gson.Gson().toJson(variants))
            val entries = mutableListOf(
                UsageLogEntry("new", UsageCategory.CHAT, "same-reply", 30L, record),
                entry("other1", "Other model", messageId = "other")
            )
            if (newModel == "Gemma 4") entries.add(entry("other2", "Other model", messageId = "other"))
            val log = UsageLogState(true, entries)
            assertEquals("Gemma 4", MostUsedChatModel.select(log, listOf(message)))
            // In the different-model case, two old Gemma replies beat a single
            // Claude regeneration; in the same-model case all three must count.
        }
    }

    @Test fun `durable snapshots already present in the log are counted once even before seeding`() {
        val record = TurnUsageRecord("Gemma 4", "provider")
        val message = mapOf<String, Any>("isBot" to true, "message_id" to "same-reply",
            TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(listOf(record)))
        val log = UsageLogState(false, listOf(
            UsageLogEntry("logged", UsageCategory.CHAT, "same-reply", 10L, record),
            entry("other", "Other model", messageId = "other")
        ))
        assertEquals("Other model", MostUsedChatModel.select(log, listOf(message)))
        assertFalse(log.seeded)
    }

    @Test fun `separately logged identical records preserve the number of requests`() {
        val record = TurnUsageRecord("Gemma 4", "provider")
        val message = mapOf<String, Any>("isBot" to true, "message_id" to "same-reply",
            TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(listOf(record, record)))
        val log = UsageLogState(true, listOf(
            UsageLogEntry("first", UsageCategory.CHAT, "same-reply", 10L, record),
            UsageLogEntry("second", UsageCategory.CHAT, "same-reply", 20L, record),
            entry("other", "Other model", messageId = "other")
        ))
        assertEquals("Gemma 4", MostUsedChatModel.select(log, listOf(message)))
    }

    @Test fun `mixed history already seeded into the log does not count legacy versions twice`() {
        val record = TurnUsageRecord("Claude", "provider")
        val message = mapOf<String, Any>("isBot" to true, "message_id" to "same-reply", "variants" to
            com.google.gson.Gson().toJson(listOf(
                mapOf("responseModel" to "Gemma 4"), mapOf("responseModel" to "Gemma 4"),
                mapOf("responseModel" to "Claude", TokenUsageAccounting.KEY_USAGE_RECORDS to
                    TokenUsageAccounting.encodeRecords(listOf(record)))
            )))
        val log = UsageLogState(true, listOf(
            entry("old1", "Gemma 4"), entry("old2", "Gemma 4"),
            UsageLogEntry("new", UsageCategory.CHAT, "same-reply", 30L, record),
            entry("other1", "Other model", messageId = "other"),
            entry("other2", "Other model", messageId = "other"),
            entry("other3", "Other model", messageId = "other")
        ))
        assertEquals("Other model", MostUsedChatModel.select(log, listOf(message)))
    }

    @Test fun `frozen retry requests carried by a user message still contribute`() {
        val record = TurnUsageRecord("Gemma 4", "provider")
        val message = mapOf<String, Any>("isBot" to false,
            TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(listOf(record, record)))
        val log = UsageLogState(true, listOf(entry("other", "Other model", messageId = "other")))
        assertEquals("Gemma 4", MostUsedChatModel.select(log, listOf(message)))
    }

    @Test fun `pre-log durable request history contributes without changing stored logs`() {
        val records = listOf(TurnUsageRecord("Gemma 4", "provider"), TurnUsageRecord("Gemma 4", "provider"))
        val messages = listOf(mapOf<String, Any>("isBot" to true, "message_id" to "old-reply",
            TokenUsageAccounting.KEY_USAGE_RECORDS to TokenUsageAccounting.encodeRecords(records)))
        val stored = UsageLog.EMPTY
        assertEquals("Gemma 4", MostUsedChatModel.select(stored, messages))
        assertFalse(stored.seeded)
        assertTrue(stored.entries.isEmpty())
    }
}
