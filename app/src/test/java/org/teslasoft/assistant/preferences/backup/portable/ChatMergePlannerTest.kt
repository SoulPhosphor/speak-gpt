package org.teslasoft.assistant.preferences.backup.portable

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMergePlannerTest {

    private val chatId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private val currentFolderId = "5f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private val backupFolderId = "7f2504e0-4f89-41d3-9a0c-0305e82c3301"

    @Test
    fun identicalStableIdentityIsSkipped() {
        val current = plan(chat(chatId, "[]"))
        val backup = plan(chat(chatId, "[]"))

        val ready = ChatMergePlanner.plan(current, backup) as ChatMergePlanner.Result.Ready

        assertEquals(1, ready.report.identicalSkipped)
        assertEquals(0, ready.report.conflicts.size)
        assertEquals(1, ready.plan.chats.size)
    }

    @Test
    fun divergentStableIdentityKeepsCurrentAndReportsConflict() {
        val current = plan(chat(chatId, """[{"message":"current"}]"""))
        val backup = plan(chat(chatId, """[{"message":"backup"}]"""))

        val ready = ChatMergePlanner.plan(current, backup) as ChatMergePlanner.Result.Ready

        assertEquals(listOf(chatId), ready.report.conflicts)
        assertEquals(
            JSONArray("""[{"message":"current"}]""").toString(),
            ready.plan.chats.single().messagesJson
        )
    }

    @Test
    fun exactPrefixUsesTheVersionWithAdditionalMessagesAndKeepsCurrentOrganization() {
        val current = plan(chat(
            chatId,
            """[{"message":"one"}]""",
            mapOf("folder_id" to currentFolderId, "pinned" to "true", "timestamp" to "1")
        ))
        val backup = plan(chat(
            chatId,
            """[{"message":"one"},{"message":"two"}]""",
            mapOf("folder_id" to backupFolderId, "pinned" to "false", "timestamp" to "2")
        ))

        val ready = ChatMergePlanner.plan(current, backup) as ChatMergePlanner.Result.Ready

        assertEquals(1, ready.report.longerHistoriesUsed)
        assertEquals(2, ready.plan.chats.single().messageCount)
        assertEquals(currentFolderId, ready.plan.chats.single().listRow["folder_id"])
        assertEquals("true", ready.plan.chats.single().listRow["pinned"])
    }

    @Test
    fun sameFolderNameWithDifferentIdentityRequiresAnExplicitDecision() {
        val current = plan(
            chats = emptyList(),
            folders = listOf(PortableChatRestorePlan.FolderPlan(currentFolderId, "Research", false))
        )
        val backup = plan(
            chats = listOf(chat("8f2504e0-4f89-41d3-9a0c-0305e82c3301", "[]",
                mapOf("folder_id" to backupFolderId))),
            folders = listOf(PortableChatRestorePlan.FolderPlan(backupFolderId, "research", true))
        )

        val needsDecision = ChatMergePlanner.plan(current, backup)
            as ChatMergePlanner.Result.NeedsFolderDecisions

        assertEquals(1, needsDecision.collisions.size)
        assertEquals(currentFolderId, needsDecision.collisions.single().currentFolder.id)
        assertEquals(backupFolderId, needsDecision.collisions.single().backupFolder.id)
    }

    @Test
    fun mergeFolderDecisionMapsMembershipWithoutRemovingCurrentChats() {
        val currentChat = chat(chatId, "[]", mapOf("folder_id" to currentFolderId))
        val backupChatId = "8f2504e0-4f89-41d3-9a0c-0305e82c3301"
        val current = plan(
            listOf(currentChat),
            listOf(PortableChatRestorePlan.FolderPlan(currentFolderId, "Research", false))
        )
        val backup = plan(
            listOf(chat(backupChatId, "[]", mapOf("folder_id" to backupFolderId))),
            listOf(PortableChatRestorePlan.FolderPlan(backupFolderId, "research", true))
        )

        val ready = ChatMergePlanner.plan(
            current,
            backup,
            mapOf(backupFolderId to ChatMergePlanner.FolderResolution.MergeInto(currentFolderId))
        ) as ChatMergePlanner.Result.Ready

        assertEquals(setOf(chatId, backupChatId), ready.plan.chats.map { it.chatId }.toSet())
        assertEquals(currentFolderId, ready.plan.chats.single { it.chatId == backupChatId }.listRow["folder_id"])
        assertEquals(listOf(currentFolderId), ready.plan.folders.map { it.id })
    }

    @Test
    fun createNewFolderKeepsTheBackupUuidAndUsesTheApprovedNewName() {
        val current = plan(
            emptyList(),
            listOf(PortableChatRestorePlan.FolderPlan(currentFolderId, "Research", false))
        )
        val backupChatId = "8f2504e0-4f89-41d3-9a0c-0305e82c3301"
        val backup = plan(
            listOf(chat(backupChatId, "[]", mapOf("folder_id" to backupFolderId))),
            listOf(PortableChatRestorePlan.FolderPlan(backupFolderId, "Research", true))
        )

        val ready = ChatMergePlanner.plan(
            current,
            backup,
            mapOf(backupFolderId to ChatMergePlanner.FolderResolution.CreateNew("Restored Research"))
        ) as ChatMergePlanner.Result.Ready

        assertTrue(ready.plan.folders.any {
            it.id == backupFolderId && it.name == "Restored Research" && it.pinned
        })
        assertEquals(backupFolderId, ready.plan.chats.single().listRow["folder_id"])
    }

    private fun plan(
        chats: List<ChatLogicalImportPlan.ChatPlan>,
        folders: List<PortableChatRestorePlan.FolderPlan> = emptyList()
    ) = PortableChatRestorePlan.Plan(chats, folders)

    private fun plan(vararg chats: ChatLogicalImportPlan.ChatPlan) = plan(chats.toList())

    private fun chat(
        id: String,
        messages: String,
        extras: Map<String, String> = emptyMap()
    ): ChatLogicalImportPlan.ChatPlan {
        val row = linkedMapOf("id" to id, "name" to "Chat")
        row.putAll(extras)
        return ChatLogicalImportPlan.ChatPlan(
            chatId = id,
            listRow = row,
            messagesJson = JSONArray(messages).toString(),
            messageCount = JSONArray(messages).length(),
            settings = emptyList()
        )
    }
}
