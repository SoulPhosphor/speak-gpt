package org.teslasoft.assistant.ui.chat

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.chatnavigation.*

class ChatExportFoldersTest {
    @Test fun unreadableFolderMetadataCannotBeExportedAsAnEmptyFolderMap() {
        val snapshot = ChatNavigationSnapshot(
            ChatStorageHealth.ReadState.OK, emptyList(), emptyList(), emptyList(), emptyList(),
            foldersUnavailable = true
        )
        assertNull(ChatExportFolders.names(snapshot))
        assertNull(ChatExportFolders.names(null))
        assertEquals(emptyMap<String, String>(), ChatExportFolders.names(snapshot.copy(foldersUnavailable = false)))
    }

    @Test fun pinnedChatsKeepTheirFolderNameEvenWhenAbsentFromTheFolderRows() {
        val pinned = ChatNavigationItem("chat", "Chat", 0, true, "folder")
        val unfiled = ChatNavigationItem("other", "Other", 0, false, null)
        val snapshot = ChatNavigationSnapshot(
            ChatStorageHealth.ReadState.OK, listOf(pinned),
            listOf(FolderNavigationGroup(FolderRecord("folder", "Research"), emptyList())),
            listOf(unfiled), listOf(pinned, unfiled)
        )
        assertEquals(mapOf("chat" to "Research"), ChatExportFolders.names(snapshot))
    }
}
