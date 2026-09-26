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
        val current = plan(chat(chatId, "[]", mapOf("timestamp" to "1", "pinned" to "true")))
        val backup = plan(chat(chatId, "[]", mapOf("timestamp" to "2", "pinned" to "false")))

        val ready = ChatMergePlanner.plan(current, backup) as ChatMergePlanner.Result.Ready

        assertEquals(1, ready.report.identicalSkipped)
        assertEquals(0, ready.report.conflicts.size)
        assertEquals(1, ready.plan.chats.size)
        assertEquals("true", ready.plan.chats.single().listRow["pinned"])
    }

    @Test
    fun divergentStableIdentityWithNoNewerVersionNeedsAChoice() {
        val current = plan(chat(chatId, """[{"message":"current"}]"""))
        val backup = plan(chat(chatId, """[{"message":"backup"}]"""))

        val decision = ChatMergePlanner.plan(current, backup)
            as ChatMergePlanner.Result.NeedsChatDecisions

        assertEquals(chatId, decision.collisions.single().incoming.chatId)
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
    fun newerIncomingVersionReplacesADeviceVersionWithMoreMessages() {
        val current = plan(chat(
            chatId,
            """[{"message":"one"},{"message":"two"}]""",
            mapOf("name" to "Current", "timestamp" to "100", "pinned" to "true")
        ))
        val backup = plan(chat(
            chatId,
            """[{"message":"edited"}]""",
            mapOf("name" to "Incoming", "timestamp" to "200", "pinned" to "false")
        ))

        val ready = ChatMergePlanner.plan(current, backup) as ChatMergePlanner.Result.Ready

        assertEquals(1, ready.plan.chats.single().messageCount)
        assertEquals("Incoming", ready.plan.chats.single().listRow["name"])
        assertEquals("true", ready.plan.chats.single().listRow["pinned"])
        assertEquals("200", ready.plan.chats.single().listRow["timestamp"])
    }

    @Test
    fun newerShorterVersionNeedsAChoiceWhenAutomaticReplacementIsOff() {
        val current = plan(chat(
            chatId,
            """[{"message":"one"},{"message":"two"}]""",
            mapOf("timestamp" to "100")
        ))
        val backup = plan(chat(
            chatId,
            """[{"message":"edited"}]""",
            mapOf("timestamp" to "200")
        ))

        val result = ChatMergePlanner.plan(
            current,
            backup,
            options = ChatMergePlanner.Options(alwaysUseNewer = false)
        )

        assertTrue(result is ChatMergePlanner.Result.NeedsChatDecisions)
    }

    @Test
    fun keepBothRetainsTheCurrentIdAndCreatesAStableUuidForTheIncomingCopy() {
        val current = plan(chat(
            chatId,
            """[{"message":"one"},{"message":"two"}]""",
            mapOf("timestamp" to "100")
        ))
        val backup = plan(chat(
            chatId,
            """[{"message":"edited"}]""",
            mapOf("timestamp" to "200")
        ))

        val ready = ChatMergePlanner.plan(
            current,
            backup,
            options = ChatMergePlanner.Options(keepBothWhenOlderHasMore = true)
        ) as ChatMergePlanner.Result.Ready

        assertEquals(2, ready.plan.chats.size)
        assertTrue(ready.plan.chats.any { it.chatId == chatId && it.messageCount == 2 })
        val copy = ready.plan.chats.single { it.chatId != chatId }
        assertEquals(copy.chatId, copy.listRow["id"])
        assertEquals(1, copy.messageCount)
        assertEquals(1, ready.report.copiesKept)
    }

    @Test
    fun hiddenEditTimeWinsWithoutChangingTheVisibleMessageTime() {
        val edited = chat(
            chatId,
            """[{"message":"edited","messageTime":100,"messageModifiedTime":300}]""",
            mapOf("timestamp" to "100")
        )

        assertEquals(300L, ChatMergePlanner.lastChangedAt(edited))
        assertEquals(100L, JSONArray(edited.messagesJson).getJSONObject(0).getLong("messageTime"))
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
