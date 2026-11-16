package org.teslasoft.assistant.ui.activities.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex
import org.teslasoft.assistant.preferences.chatnavigation.ConversationParticipants
import org.teslasoft.assistant.preferences.memory.MemorySourceRules
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** The memory editor's source lines (development plan M.3). */
class MemorySourceLinesTest {

    private val day: (String?) -> String? = { MemoryDateFormatter.formatDay(it, Locale.US, ZoneOffset.UTC) }
    private val source = MemorySourceRules.conversationOnly("s1", "m1", "chat-1", "2026-11-16T00:00:00Z")
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun entry(first: Long?, last: Long?) = ChatListIndex.Entry(
        hasBookmarks = false, mostUsedModel = null, model = "m", companionId = "c",
        memoryExcluded = false,
        startedAt = 1L,
        participants = ConversationParticipants(listOf("Lena"), listOf("Ada"), last, first)
    )

    private fun present(entry: ChatListIndex.Entry?, users: List<String> = listOf("Lena", "Captain Vale")) =
        MemorySourceLines.Conversation.Present("chat-1", "Chat", entry, users, listOf("Ada"))

    private fun lines(c: MemorySourceLines.Conversation) =
        MemorySourceLines.groups(listOf(source), { c }, day, ZoneOffset.UTC).single()

    @Test
    fun aConversationShowsParticipantsDatesAndItsLink() {
        val l = lines(present(entry(millis("2025-11-10T15:00:00Z"), millis("2025-11-12T09:00:00Z"))))
        assertEquals(
            listOf(
                MemorySourceLines.Line.Labeled(MemorySourceLines.Label.USER, "Lena, Captain Vale"),
                MemorySourceLines.Line.Labeled(MemorySourceLines.Label.COMPANION, "Ada"),
                MemorySourceLines.Line.Labeled(MemorySourceLines.Label.DATE, "November 10, 2025"),
                MemorySourceLines.Line.Labeled(MemorySourceLines.Label.LAST_MESSAGE_DATE, "November 12, 2025"),
                MemorySourceLines.Line.Open("chat-1", "Chat")
            ),
            l
        )
    }

    @Test
    fun aSameDayConversationHasNoLastMessageDate() {
        val l = lines(present(entry(millis("2025-11-10T08:00:00Z"), millis("2025-11-10T23:00:00Z"))))
        assertTrue(l.none { it is MemorySourceLines.Line.Labeled && it.label == MemorySourceLines.Label.LAST_MESSAGE_DATE })
    }

    @Test
    fun dateUsesOnlyTheFirstMessagesOwnTime() {
        // The chat list's earliest-known time (startedAt) is not the first message's date.
        val l = lines(present(entry(null, millis("2025-11-12T09:00:00Z"))))
        assertTrue(MemorySourceLines.Line.Labeled(MemorySourceLines.Label.DATE, null) in l)
        assertTrue(
            MemorySourceLines.Line.Labeled(MemorySourceLines.Label.LAST_MESSAGE_DATE, "November 12, 2025") in l
        )
    }

    @Test
    fun missingDatesReadUnavailable() {
        val l = lines(present(null, users = emptyList()))
        assertEquals(
            listOf(
                MemorySourceLines.Line.Labeled(MemorySourceLines.Label.COMPANION, "Ada"),
                MemorySourceLines.Line.Labeled(MemorySourceLines.Label.DATE, null),
                MemorySourceLines.Line.Open("chat-1", "Chat")
            ),
            l
        )
    }

    @Test
    fun aDeletedOrUnreadableConversationShowsOnlyThat() {
        assertEquals(listOf(MemorySourceLines.Line.Deleted), lines(MemorySourceLines.Conversation.Deleted))
        assertEquals(listOf(MemorySourceLines.Line.Unavailable), lines(MemorySourceLines.Conversation.Unavailable))
    }

    @Test
    fun aMemoryTheUserWroteShowsOnlyItsDate() {
        val entrySource = MemorySourceRules.userEntry("s2", "m1", "2026-11-16T09:00:00Z")
        val l = MemorySourceLines.groups(listOf(entrySource), { error("no conversation") }, day).single()
        assertEquals(listOf(MemorySourceLines.Line.Labeled(MemorySourceLines.Label.DATE, "November 16, 2026")), l)
    }

    @Test
    fun noSourcesNoLines() {
        assertTrue(MemorySourceLines.groups(emptyList(), { null }, day).isEmpty())
    }
}
