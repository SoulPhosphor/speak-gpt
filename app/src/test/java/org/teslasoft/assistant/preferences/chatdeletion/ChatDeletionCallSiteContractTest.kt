package org.teslasoft.assistant.preferences.chatdeletion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDeletionCallSiteContractTest {
    private fun root(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("src/main not found from ${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(root(), relative).readText()

    @Test fun everyReachableChatDeletionPathUsesOneRequestCoordinator() {
        val activity = source("java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        val list = source("java/org/teslasoft/assistant/ui/fragments/tabs/ChatsListFragment.kt")
        val edit = source("java/org/teslasoft/assistant/ui/fragments/dialogs/AddChatDialogFragment.kt")

        assertTrue(activity.contains("ChatDeletionRequestCoordinator.requestChats("))
        assertEquals(2, Regex("ChatDeletionRequestCoordinator\\.requestChats\\(").findAll(list).count())
        assertTrue(edit.contains("ChatDeletionRequestCoordinator.requestChats("))
        assertFalse(activity.contains("deleteChatById("))
        assertFalse(list.contains(".deleteChat("))
        assertFalse(edit.contains(".deleteChat("))
        assertFalse(activity.contains("removeChatMetadataBatch("))
        assertFalse(list.contains("removeChatMetadataBatch("))
        assertFalse(edit.contains("removeChatMetadataBatch("))
        assertFalse(activity.contains("cleanupDeletedChatData("))
        assertFalse(list.contains("cleanupDeletedChatData("))
        assertFalse(edit.contains("cleanupDeletedChatData("))
    }

    @Test fun lowLevelChatPreferencesNoLongerExposesUiFacingListDeletion() {
        val preferences = source("java/org/teslasoft/assistant/preferences/ChatPreferences.kt")
        assertFalse(Regex("fun\\s+deleteChat(ById)?\\s*\\(").containsMatchIn(preferences))
        assertTrue(preferences.contains("internal fun cleanupDeletedChatData"))
    }

    @Test fun folderFlowAndThreeActionDialogPreserveStableIdsAndCancelFirstOrder() {
        val request = source("java/org/teslasoft/assistant/ui/util/ChatDeletionRequestCoordinator.kt")
        val dialog = source("java/org/teslasoft/assistant/ui/util/ChatDeleteDialog.kt")
        val layout = source("res/layout/dialog_three_actions_cancel_first.xml")

        assertTrue(request.contains("target = ChatDeletionTarget.Folder(folderId)"))
        assertTrue(request.contains("preflight.chatIds.isEmpty()"))
        assertTrue(request.contains("showFolderWarning"))
        assertOrdered(
            layout,
            "@+id/btn_dialog_cancel_action",
            "@+id/btn_dialog_middle_action",
            "@+id/btn_dialog_final_action"
        )
        assertOrdered(
            dialog,
            "cancel.setText(R.string.btn_cancel)",
            "chatOnly.setText(R.string.chat_delete_chat_only)",
            "deleteAll.setText(R.string.chat_delete_all)"
        )
    }

    @Test fun currentChatPinIsFirstAndUsesTheNavigationRepositoryIdentity() {
        val activity = source("java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        // Saved-chat identity comes from the chat list itself. Reading it from
        // the whole navigation snapshot additionally required the folder
        // catalog to be readable, so a folder-metadata problem removed Pin,
        // Export Chat and Delete from a healthy saved chat's menu.
        assertTrue(activity.contains("private fun readSavedChatRow(): SavedChatRow?"))
        assertTrue(activity.contains("list.chats.firstOrNull { ChatPreferences.storedChatId(it) == chatId }"))
        assertFalse(activity.contains("result.value.allChats.firstOrNull { it.id == chatId }"))
        assertTrue(activity.contains(".setChatPinned(chat.id, !chat.pinned)"))
        assertOrdered(
            activity,
            "if (it.pinned) R.string.chat_menu_unpin else R.string.chat_menu_pin",
            "menu.add(Menu.NONE, 1, 1, R.string.chat_menu_export)",
            "menu.add(Menu.NONE, 2, 2, R.string.alert_debug_section_logs)",
            "menu.add(Menu.NONE, 3, 3, R.string.btn_delete)"
        )
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        val positions = markers.map(source::indexOf)
        assertTrue("Missing marker in ${markers.toList()}", positions.all { it >= 0 })
        assertTrue(positions.zipWithNext().all { it.first < it.second })
    }
}
