package org.teslasoft.assistant.ui.chat

import com.google.gson.JsonParser
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportFormatterTest {

    @Test
    fun textPreservesParagraphsAndUsesTheRequestedMetadataOrder() {
        val messages = listOf(
            ChatExportMessage(
                isCompanion = false,
                name = "User",
                content = "first\n\nsecond",
                tokenCount = 3
            ),
            ChatExportMessage(
                isCompanion = true,
                name = "Moon",
                content = "reply",
                model = "provider/model",
                tokenCount = 7
            )
        )

        val result = ChatExportFormatter.formatText(
            messages,
            ChatExportOptions(
                format = ChatExportFormat.TEXT,
                includeUserTokenCount = true,
                includeCompanionTokenCount = true
            )
        )

        assertEquals(
            "User\n3 Tokens\nfirst\n\nsecond\n\n" +
                "Moon\nprovider/model · 7 Tokens\nreply",
            result
        )
    }

    @Test
    fun dateAndTimeAreAlwaysIncludedInOneMetadataSegment() {
        val result = ChatExportFormatter.formatText(
            listOf(
                ChatExportMessage(
                    isCompanion = false,
                    name = "User",
                    content = "hello",
                    timestampMillis = 0L
                )
            ),
            ChatExportOptions(format = ChatExportFormat.TEXT),
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        assertTrue(result.contains("User\nJan 1, 1970"))
        assertTrue(result.contains("12:00"))
        assertTrue(result.endsWith("\nhello"))
        assertFalse(result.lines()[1].contains(" · "))
    }

    @Test
    fun jsonAlwaysCarriesDateTimeAndModelButTokensOnlyWhenSelected() {
        val result = ChatExportFormatter.formatJson(
            listOf(
                ChatExportMessage(
                    isCompanion = true,
                    name = "Moon",
                    content = "reply",
                    timestampMillis = 0L,
                    model = "provider/model",
                    tokenCount = 7
                )
            ),
            ChatExportOptions(format = ChatExportFormat.JSON),
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        val record = JsonParser.parseString(result).asJsonArray[0].asJsonObject
        assertEquals("reply", record.get("message").asString)
        assertEquals("Moon", record.get("name").asString)
        assertTrue(record.has("isBot"))
        assertEquals("Jan 1, 1970", record.get("date").asString)
        assertTrue(record.has("time"))
        assertEquals("provider/model", record.get("model").asString)
        assertFalse(record.has("tokens"))
    }

    @Test
    fun thinkingIsLabeledOnlyWhenIncludedAndPresent() {
        val messages = listOf(
            ChatExportMessage(true, "Moon", "reply", thinking = "pondering"),
            ChatExportMessage(true, "Moon", "plain")
        )
        assertEquals(
            "Moon\nThinking:\npondering\n\nResponse:\nreply\n\nMoon\nplain",
            ChatExportFormatter.formatText(messages, ChatExportOptions(format = ChatExportFormat.TEXT))
        )
        assertEquals(
            "Moon\nreply\n\nMoon\nplain",
            ChatExportFormatter.formatText(
                messages,
                ChatExportOptions(format = ChatExportFormat.TEXT, includeThinking = false)
            )
        )
        val on = JsonParser.parseString(ChatExportFormatter.formatJson(messages, ChatExportOptions())).asJsonArray
        assertEquals("pondering", on[0].asJsonObject.get("thinking").asString)
        assertFalse(on[1].asJsonObject.has("thinking"))
        val off = ChatExportFormatter.formatJson(messages, ChatExportOptions(includeThinking = false))
        assertFalse(off.contains("thinking"))
    }

    @Test
    fun tokenSwitchesOnlyApplyToTheirSpeaker() {
        val result = ChatExportFormatter.formatText(
            listOf(
                ChatExportMessage(false, "User", "hello", tokenCount = 2),
                ChatExportMessage(true, "Moon", "reply", tokenCount = 4)
            ),
            ChatExportOptions(
                format = ChatExportFormat.TEXT,
                includeCompanionTokenCount = true
            )
        )

        assertEquals("User\nhello\n\nMoon\n4 Tokens\nreply", result)
    }
}
