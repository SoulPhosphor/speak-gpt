package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Conversation participants and dates kept in the chat list index (M.3). */
class ConversationParticipantsTest {

    private fun user(name: String?, time: Long? = null) = buildMap<String, Any?> {
        put("isBot", false)
        name?.let { put("userName", it) }
        time?.let { put("messageTime", it.toString()) }
    }

    private fun reply(name: String?, time: Long? = null) = buildMap<String, Any?> {
        put("isBot", true)
        name?.let { put("companionName", it) }
        time?.let { put("messageTime", it.toString()) }
    }

    @Test
    fun namesAreDistinctInOrderOfFirstAppearance() {
        val p = ConversationParticipants.fromMessages(listOf(
            user("Lena", 100), reply("Ada", 200), user("Captain Vale", 300),
            user("Lena", 400), reply("Ada", 500), reply("Bo", 600)
        ))
        assertEquals(listOf("Lena", "Captain Vale"), p.userNames)
        assertEquals(listOf("Ada", "Bo"), p.companionNames)
        assertEquals(600L, p.lastMessageAt)
        assertEquals(100L, p.firstMessageAt)
    }

    @Test
    fun aFirstMessageWithoutATimeHasNoDateAndNeverBorrowsALaterOne() {
        val p = ConversationParticipants.fromMessages(listOf(user("Lena"), reply("Ada", 200), user("Lena", 300)))
        assertNull(p.firstMessageAt)
        assertEquals(300L, p.lastMessageAt)
        val q = ConversationParticipants.fromMessages(listOf(user("Lena", 100), reply("Ada")))
        assertEquals(100L, q.firstMessageAt)
        assertNull(q.lastMessageAt)
    }

    @Test
    fun messagesWithoutSavedNamesAreKeptForTheChatScreensFallback() {
        val p = ConversationParticipants.fromMessages(listOf(user(null), reply(null), reply("Ada")))
        assertEquals(listOf(ConversationParticipants.UNNAMED), p.userNames)
        assertEquals(listOf(ConversationParticipants.UNNAMED, "Ada"), p.companionNames)
        assertNull(p.lastMessageAt)
        assertEquals(listOf("User"), ConversationParticipants.displayed(p.userNames, { "User" }))
        // An unnamed reply shows the chat's current companion; the same name appears once.
        assertEquals(listOf("Ada"), ConversationParticipants.displayed(p.companionNames, { "Ada" }))
    }

    @Test
    fun theChatsOwnUsernameReplacesEveryUserName() {
        assertEquals(
            listOf("Lee"),
            ConversationParticipants.displayed(listOf("Lena", "Captain Vale"), { "User" }, override = "Lee")
        )
        assertEquals(
            listOf("Lena"),
            ConversationParticipants.displayed(listOf("Lena"), { "User" }, override = "  ")
        )
        assertEquals(emptyList<String>(), ConversationParticipants.displayed(emptyList(), { "User" }, "Lee"))
    }

    @Test
    fun imagePlaceholdersAreNotParticipants() {
        val placeholder = reply("Ada", 900) + ("imageProgressCard" to true)
        val p = ConversationParticipants.fromMessages(listOf(user("Lena", 100), placeholder))
        assertEquals(emptyList<String>(), p.companionNames)
        assertEquals(100L, p.lastMessageAt)
        assertEquals(100L, p.firstMessageAt)
    }

    @Test
    fun theIndexEntryKeepsParticipantsAcrossUpdates() {
        val participants = ConversationParticipants(listOf("Lena"), listOf("Ada"), 500)
        val entry = ChatListIndex.merge(
            null,
            ChatListIndex.Update(
                content = ChatListIndex.ContentValues(false, null, 100, participants),
                settings = ChatListIndex.SettingsValues("m", "c", false)
            )
        )!!
        assertEquals(participants, entry.participants)
        val after = ChatListIndex.merge(entry, ChatListIndex.Update(memory = ChatListIndex.MemoryValues("pending")))!!
        assertEquals(participants, after.participants)
    }
}
