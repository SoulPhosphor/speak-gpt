package org.teslasoft.assistant.preferences.chatnavigation

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatStorageHealth

class ChatNavigationProjectionTest {
    private val alpha = FolderRecord("11111111-1111-4111-8111-111111111111", "Alpha", pinned = false)
    private val beta = FolderRecord("22222222-2222-4222-8222-222222222222", "beta", pinned = true)
    private val zeta = FolderRecord("33333333-3333-4333-8333-333333333333", "Zeta", pinned = true)

    @Test fun foldersArePinnedFirstThenAlphabeticalAndChatsNewestFirst() {
        val snapshot = ChatNavigationProjection.build(
            chats = listOf(
                ChatNavigationItem("old", "Old", 10, false, beta.id),
                ChatNavigationItem("new", "New", 20, false, beta.id),
                ChatNavigationItem("loose", "Loose", 30, false, null)
            ),
            folders = listOf(alpha, zeta, beta),
            storageState = ChatStorageHealth.ReadState.OK,
            locale = Locale.US
        )
        assertEquals(listOf("beta", "Zeta", "Alpha"), snapshot.folders.map { it.folder.name })
        assertEquals(listOf("new", "old"), snapshot.folders[0].chats.map { it.id })
        assertEquals(listOf("loose"), snapshot.unfiledChats.map { it.id })
    }

    @Test fun pinnedAssignedChatAppearsOnceAndRetainsMembership() {
        val pinned = ChatNavigationItem("pinned", "Pinned", 40, true, alpha.id)
        val snapshot = ChatNavigationProjection.build(
            listOf(pinned), listOf(alpha), ChatStorageHealth.ReadState.OK, Locale.US
        )
        assertEquals(listOf("pinned"), snapshot.pinnedChats.map { it.id })
        assertTrue(snapshot.folders.single().chats.isEmpty())
        assertTrue(snapshot.unfiledChats.isEmpty())
        assertEquals(alpha.id, snapshot.pinnedChats.single().folderId)
    }

    @Test fun staleFolderAssignmentNeverHidesAChat() {
        val orphan = ChatNavigationItem("visible", "Visible", 1, false, "missing-folder")
        val snapshot = ChatNavigationProjection.build(
            listOf(orphan), emptyList(), ChatStorageHealth.ReadState.OK, Locale.US
        )
        assertEquals(listOf("visible"), snapshot.unfiledChats.map { it.id })
    }

    @Test fun emptyFoldersRemainInCompleteSnapshot() {
        val snapshot = ChatNavigationProjection.build(
            emptyList(), listOf(alpha), ChatStorageHealth.ReadState.EMPTY, Locale.US
        )
        assertEquals(alpha, snapshot.folders.single().folder)
        assertTrue(snapshot.folders.single().chats.isEmpty())
    }
}
