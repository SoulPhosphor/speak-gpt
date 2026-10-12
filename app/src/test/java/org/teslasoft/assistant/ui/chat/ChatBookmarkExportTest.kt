package org.teslasoft.assistant.ui.chat

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageBookmarks
import org.teslasoft.assistant.preferences.backup.readable.ReadableChatFormats

class ChatBookmarkExportTest {
    private val messages = listOf(
        ChatExportMessage(false, "You", "Question", bookmarked = true, messageId = "user-id"),
        ChatExportMessage(true, "Companion", "Answer", bookmarked = true, messageId = "ai-id"),
        ChatExportMessage(true, "Companion", "Other answer")
    )

    @Test fun everyReadableExportStartsBookmarkedMessagesWithTheWordBookmark() {
        for (format in listOf(ChatExportFormat.TEXT, ChatExportFormat.MARKDOWN, ChatExportFormat.PDF)) {
            val options = ChatExportOptions(format = format)
            val text = ChatExportFormatter.formatText(messages, options)
            assertEquals("[Bookmark]\nYou\nQuestion\n\n[Bookmark]\nCompanion\nAnswer\n\nCompanion\nOther answer", text)
            assertTrue(ChatExportFormatter.formatHeader(messages[0], options).startsWith("[Bookmark]\n"))
        }
    }

    @Test fun jsonKeepsAnArrayAndIncludesBookmarkFolderAndStableId() {
        val json = ChatExportFormatter.formatJson(messages, ChatExportOptions(), folderName = "Research")
        val records = JsonParser.parseString(json).asJsonArray
        assertEquals(3, records.size())
        assertTrue(records[0].asJsonObject["bookmark"].asBoolean)
        assertTrue(records[1].asJsonObject["bookmark"].asBoolean)
        assertFalse(records[2].asJsonObject["bookmark"].asBoolean)
        assertEquals("user-id", records[0].asJsonObject["message_id"].asString)
        for (record in records) assertEquals("Research", record.asJsonObject["folder_name"].asString)
        val restored: List<Map<String, Any>> = Gson().fromJson(
            json, object : TypeToken<List<Map<String, Any>>>() {}.type
        )
        assertTrue(MessageBookmarks.isBookmarked(restored[0]))
        assertTrue(MessageBookmarks.isBookmarked(restored[1]))
        assertFalse(MessageBookmarks.isBookmarked(restored[2]))
    }

    @Test fun emptyDirectExportsKeepTheArrayContractWhileReadableBackupsCarryChatMetadata() {
        assertEquals("[]", ChatExportFormatter.formatJson(emptyList(), ChatExportOptions(), folderName = "Research"))
        val backup = JsonParser.parseString(ReadableChatFormats.formatJson("Empty chat", "[]", "Research")).asJsonObject
        assertEquals("Research", backup["folder_name"].asString)
        assertEquals(0, backup["messages"].asJsonArray.size())
    }

    @Test fun unfiledJsonDoesNotInventAFolderAndTextDoesNotIncludeFolders() {
        val json = ChatExportFormatter.formatJson(messages, ChatExportOptions())
        assertFalse(JsonParser.parseString(json).asJsonArray[0].asJsonObject.has("folder_name"))
        assertFalse(ChatExportFormatter.formatText(messages, ChatExportOptions()).contains("folder_name"))
    }

    @Test fun readableBackupTextAndJsonCarryBookmarks() {
        val raw = listOf(mapOf("message" to "Answer", "isBot" to true, "bookmark" to true))
        val json = ReadableChatFormats.formatJson("Chat", Gson().toJson(raw), "Research")
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("Research", root["folder_name"].asString)
        assertTrue(root["messages"].asJsonArray[0].asJsonObject["bookmark"].asBoolean)
        val names = ReadableChatFormats.SpeakerNames(null, "User", "Assistant")
        assertEquals("Chat\n\n[Bookmark]\nAssistant\nAnswer\n", ReadableChatFormats.formatText("Chat", raw, names, includeThinking = true))
    }

    @Test fun incrementalBackupDetectsBookmarkRemovalAndFolderRename() {
        val marked = """[{"message":"Answer","isBot":true,"bookmark":true}]"""
        val unmarked = """[{"message":"Answer","isBot":true}]"""
        val baseline = ReadableChatFormats.fingerprint("Chat", marked, "Research")
        assertNotEquals(baseline, ReadableChatFormats.fingerprint("Chat", unmarked, "Research"))
        assertNotEquals(baseline, ReadableChatFormats.fingerprint("Chat", marked, "Writing"))
    }

    @Test fun removingABookmarkLeavesTheMessageAndReplyVersionsIntact() {
        val message = hashMapOf<String, Any>("message" to "Answer", "responseVersions" to "versions")
        MessageBookmarks.set(message, true)
        assertTrue(MessageBookmarks.isBookmarked(message))
        MessageBookmarks.set(message, false)
        assertFalse(MessageBookmarks.isBookmarked(message))
        assertEquals("Answer", message["message"])
        assertEquals("versions", message["responseVersions"])
    }
}
