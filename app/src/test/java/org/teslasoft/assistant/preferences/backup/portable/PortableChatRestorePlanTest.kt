package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.util.Hash

class PortableChatRestorePlanTest {

    private val chatId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private val folderId = "7f2504e0-4f89-41d3-9a0c-0305e82c3301"

    @Test
    fun v1PreservesLegacyIdentityAndRestoresTheChatUnfiled() {
        val legacyId = Hash.hash("Old Chat")
        val entry = chat(legacyId, "Old Chat")
            .put("list_folder_id", folderId)

        val plan = ok(artifact(ChatLogicalSerializer.FORMAT_V1, entry))

        assertEquals(legacyId, plan.chats.single().chatId)
        assertEquals(legacyId, plan.chats.single().listRow["id"])
        assertFalse(plan.chats.single().listRow.containsKey("folder_id"))
        assertTrue(plan.folders.isEmpty())
    }

    @Test
    fun v2PreservesChatAndFolderIdentity() {
        val root = JSONObject(artifact(ChatLogicalSerializer.FORMAT_V2,
            chat(chatId, "Filed Chat").put("list_id", chatId).put("list_folder_id", folderId)))
            .put("folders", JSONArray().put(folder(folderId, "Research")))

        val plan = ok(root.toString())

        assertEquals(chatId, plan.chats.single().chatId)
        assertEquals(folderId, plan.chats.single().listRow["folder_id"])
        assertEquals(PortableChatRestorePlan.FolderPlan(folderId, "Research", true), plan.folders.single())
    }

    @Test
    fun v2RejectsAMissingFolderDefinition() {
        val entry = chat(chatId, "Filed Chat")
            .put("list_id", chatId)
            .put("list_folder_id", folderId)

        val rejected = PortableChatRestorePlan.parse(
            artifact(ChatLogicalSerializer.FORMAT_V2, entry)
        ) as PortableChatRestorePlan.Result.Rejected

        assertEquals(PortableChatRestorePlan.Reason.MISSING_FOLDER, rejected.reason)
    }

    @Test
    fun v2RejectsUnreferencedEmptyFolders() {
        val root = JSONObject(artifact(
            ChatLogicalSerializer.FORMAT_V2,
            chat(chatId, "Unfiled").put("list_id", chatId)
        )).put("folders", JSONArray().put(folder(folderId, "Empty")))

        val rejected = PortableChatRestorePlan.parse(root.toString())
            as PortableChatRestorePlan.Result.Rejected

        assertEquals(PortableChatRestorePlan.Reason.UNREFERENCED_FOLDER, rejected.reason)
    }

    @Test
    fun duplicateAndMalformedMessageIdsRejectTheWholeArtifact() {
        val messageId = "9f2504e0-4f89-41d3-9a0c-0305e82c3301"
        val duplicate = chat(chatId, "Duplicate", """[
            {"message":"one","message_id":"$messageId"},
            {"message":"two","message_id":"$messageId"}
        ]""").put("list_id", chatId)
        val malformed = chat(chatId, "Malformed", """[
            {"message":"one","message_id":"not-a-uuid"}
        ]""").put("list_id", chatId)

        assertEquals(
            PortableChatRestorePlan.Reason.DUPLICATE_MESSAGE_ID,
            (PortableChatRestorePlan.parse(artifact(ChatLogicalSerializer.FORMAT_V2, duplicate))
                as PortableChatRestorePlan.Result.Rejected).reason
        )
        assertEquals(
            PortableChatRestorePlan.Reason.MALFORMED_MESSAGE_ID,
            (PortableChatRestorePlan.parse(artifact(ChatLogicalSerializer.FORMAT_V2, malformed))
                as PortableChatRestorePlan.Result.Rejected).reason
        )
    }

    @Test
    fun identicalDuplicateChatRowsAreSkippedButDivergentRowsAreRejected() {
        val first = chat(chatId, "Same").put("list_id", chatId)
        val same = chat(chatId, "Same").put("list_id", chatId)
        val different = chat(chatId, "Same", """[{"message":"different"}]""")
            .put("list_id", chatId)

        val plan = ok(artifact(ChatLogicalSerializer.FORMAT_V2, first, same))
        assertEquals(1, plan.chats.size)
        assertEquals(1, plan.duplicateRowsConsolidated)

        val rejected = PortableChatRestorePlan.parse(
            artifact(ChatLogicalSerializer.FORMAT_V2, first, different)
        ) as PortableChatRestorePlan.Result.Rejected
        assertEquals(PortableChatRestorePlan.Reason.DUPLICATE_CHAT_ID, rejected.reason)
    }

    private fun ok(json: String): PortableChatRestorePlan.Plan =
        (PortableChatRestorePlan.parse(json) as PortableChatRestorePlan.Result.Ok).plan

    private fun chat(id: String, name: String, messages: String = "[]"): JSONObject = JSONObject()
        .put("name", name)
        .put("chat_id", id)
        .put("messages", JSONArray(messages))
        .put("settings", JSONArray())

    private fun folder(id: String, name: String): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("pinned", true)

    private fun artifact(format: String, vararg chats: JSONObject): String = JSONObject()
        .put("format", format)
        .put("complete", true)
        .put("folders", JSONArray())
        .put("chats", JSONArray().apply { chats.forEach(::put) })
        .toString()
}
