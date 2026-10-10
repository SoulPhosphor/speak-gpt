package org.teslasoft.assistant.preferences.backup.portable

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageBookmarks

class BookmarkBackupCompatibilityTest {
    private val chatId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private fun artifact(format: String, messages: JSONArray): String {
        val chat = JSONObject().put("name", "Chat").put("chat_id", chatId)
            .put("list_id", chatId).put("messages", messages).put("settings", JSONArray())
        return JSONObject().put("format", format).put("complete", true)
            .put("folders", JSONArray()).put("chats", JSONArray().put(chat)).toString()
    }

    @Test fun bothPortableFormatsRestoreUserAndAssistantBookmarks() {
        val messages = JSONArray().put(JSONObject().put("message", "Question").put("isBot", false).put("bookmark", true))
            .put(JSONObject().put("message", "Answer").put("isBot", true).put("bookmark", true))
        for (format in listOf(ChatLogicalSerializer.FORMAT_V1, ChatLogicalSerializer.FORMAT_V2)) {
            val result = PortableChatRestorePlan.parse(artifact(format, messages))
            val restoredJson = (result as PortableChatRestorePlan.Result.Ok).plan.chats.single().messagesJson
            val restored: List<Map<String, Any>> = Gson().fromJson(
                restoredJson, object : TypeToken<List<Map<String, Any>>>() {}.type
            )
            assertTrue(MessageBookmarks.isBookmarked(restored[0]))
            assertTrue(MessageBookmarks.isBookmarked(restored[1]))
            assertEquals("Question", restored[0]["message"])
            assertEquals("Answer", restored[1]["message"])
        }
    }

    @Test fun oldBackupsRestoreWithoutInventingBookmarks() {
        val result = PortableChatRestorePlan.parse(artifact(ChatLogicalSerializer.FORMAT_V2,
            JSONArray().put(JSONObject().put("message", "Old answer").put("isBot", true))))
        val restored = JSONArray((result as PortableChatRestorePlan.Result.Ok).plan.chats.single().messagesJson)
        assertFalse(restored.getJSONObject(0).has("bookmark"))
    }

    @Test fun newerBookmarkOnlyChangesAreNotSkippedByMergeRestore() {
        fun chat(bookmarked: Boolean, modifiedAt: Long) = ChatLogicalImportPlan.ChatPlan(
            chatId, mapOf("id" to chatId, "name" to "Chat", "content_modified_at" to modifiedAt.toString()),
            """[{"message":"Answer","isBot":true,"bookmark":$bookmarked}]""", 1, emptyList()
        )
        val current = PortableChatRestorePlan.Plan(listOf(chat(false, 1)), emptyList())
        val backup = PortableChatRestorePlan.Plan(listOf(chat(true, 2)), emptyList())
        val result = ChatMergePlanner.plan(current, backup) as ChatMergePlanner.Result.Ready
        assertTrue(JSONArray(result.plan.chats.single().messagesJson).getJSONObject(0).getBoolean("bookmark"))
    }
}
