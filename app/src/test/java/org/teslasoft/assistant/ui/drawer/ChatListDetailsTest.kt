package org.teslasoft.assistant.ui.drawer

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class ChatListDetailsTest {
    @Test fun `a user or assistant bookmark marks the chat`() {
        for (bot in listOf(true, false)) {
            assertTrue(ChatListDetails.fromMessages(listOf(mapOf("isBot" to bot, "bookmark" to true))).hasBookmarks)
        }
        assertFalse(ChatListDetails.fromMessages(listOf(mapOf("bookmark" to false))).hasBookmarks)
    }

    @Test fun `start date uses earliest valid message timestamp rather than latest activity`() {
        val details = ChatListDetails.fromMessages(listOf(
            mapOf("messageTime" to "2000"), mapOf("messageTime" to 1000.0),
            mapOf("messageTime" to "broken"), mapOf("messageTime" to -1),
            mapOf("messageTime" to Double.NaN)
        ))
        assertEquals(1000L, details.startedAt)
    }

    @Test fun `preserved start date remains stable after the original message is deleted`() {
        assertEquals(1000L, ChatListDetails.fromMessages(listOf(mapOf("messageTime" to "9000")), 1000L).startedAt)
    }

    @Test fun `date and model share one date-only line with a middle dot`() {
        val time = Instant.parse("2026-09-04T17:30:00Z").toEpochMilli()
        assertEquals("September 4, 2026 · Gemma 4",
            ChatListDetails.metadata(time, "Gemma 4", Locale.US, TimeZone.getTimeZone("UTC")))
        assertEquals("September 4, 2026",
            ChatListDetails.metadata(time, null, Locale.US, TimeZone.getTimeZone("UTC")))
    }

    @Test fun `missing dates and models leave no dangling separators`() {
        assertNull(ChatListDetails.fromMessages(emptyList()).startedAt)
        assertEquals("Gemma 4", ChatListDetails.metadata(null, "Gemma 4"))
        assertEquals("", ChatListDetails.metadata(null, null))
        assertEquals("", ChatListDetails.metadata(-1, " "))
    }
}
