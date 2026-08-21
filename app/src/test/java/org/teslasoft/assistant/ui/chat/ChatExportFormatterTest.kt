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
                includeModel = true,
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
    fun dateAndTimeShareOneMetadataSegmentWithoutASeparatorBetweenThem() {
        val result = ChatExportFormatter.formatText(
            listOf(
                ChatExportMessage(
                    isCompanion = false,
                    name = "User",
                    content = "hello",
                    timestampMillis = 0L
                )
            ),
            ChatExportOptions(
                format = ChatExportFormat.TEXT,
                includeDate = true,
                includeTime = true
            ),
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        assertEquals("User\nJan 1, 1970 12:00 AM\nhello", result)
        assertFalse(result.contains("1970 · 12:00"))
    }

    @Test
    fun disabledFieldsAreNotWrittenToJson() {
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
        assertFalse(record.has("date"))
        assertFalse(record.has("time"))
        assertFalse(record.has("model"))
        assertFalse(record.has("tokens"))
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
