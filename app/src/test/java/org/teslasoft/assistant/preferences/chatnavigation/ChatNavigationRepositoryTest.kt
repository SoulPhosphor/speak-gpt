package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences

class ChatNavigationRepositoryTest {
    private val firstId = "11111111-1111-4111-8111-111111111111"
    private val secondId = "22222222-2222-4222-8222-222222222222"

    @Test fun createUsesUniqueUuidAndRenameNeverChangesItOrMembership() {
        val repo = repository(
            rows = listOf(chatRow("chat-id", "Chat", 123)),
            ids = listOf(firstId, secondId).iterator()
        )
        val created = (repo.createFolder(" Folder ") as ChatNavigationResult.Success).value
        assertEquals(firstId, created.id)
        assertEquals("Folder", created.name)
        assertFalse(created.pinned)

        repo.moveChat("chat-id", created.id)
        val renamed = (repo.renameFolder(created.id, "Renamed") as ChatNavigationResult.Success).value
        assertEquals(created.id, renamed.id)
        val snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertEquals(created.id, snapshot.folders.single().folder.id)
        assertEquals(listOf("chat-id"), snapshot.folders.single().chats.map { it.id })
        assertEquals(123, snapshot.allChats.single().timestamp)
    }

    @Test fun idFactoryCollisionRetriesRatherThanReusingFolderIdentity() {
        val repo = repository(ids = listOf(firstId, firstId, secondId).iterator())
        val one = (repo.createFolder("One") as ChatNavigationResult.Success).value
        val two = (repo.createFolder("Two") as ChatNavigationResult.Success).value
        assertEquals(firstId, one.id)
        assertEquals(secondId, two.id)
        assertNotEquals(one.id, two.id)
    }

    @Test fun folderAndChatPinsStayIndependentAndFolderMembershipSurvivesChatPin() {
        val repo = repository(
            rows = listOf(chatRow("chat-id", "Chat", 10)),
            ids = listOf(firstId).iterator()
        )
        repo.createFolder("Folder")
        repo.moveChat("chat-id", firstId)
        repo.setFolderPinned(firstId, true)
        repo.setChatPinned("chat-id", true)

        var snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertTrue(snapshot.folders.single().folder.pinned)
        assertEquals(firstId, snapshot.pinnedChats.single().folderId)
        assertTrue(snapshot.folders.single().chats.isEmpty())

        repo.setChatPinned("chat-id", false)
        snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertEquals("chat-id", snapshot.folders.single().chats.single().id)
        assertTrue(snapshot.folders.single().folder.pinned)
    }

    @Test fun moveAndPinDoNotChangeTimestampOrUnrelatedMetadata() {
        val store = FakeSharedPreferences()
        val row = chatRow("chat-id", "Chat", 77).apply { put("future_metadata", "keep") }
        val repo = repository(listOf(row), chatStore = store, ids = listOf(firstId).iterator())
        repo.createFolder("Folder")
        repo.moveChat("chat-id", firstId)
        repo.setChatPinned("chat-id", true)
        val snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertEquals(77, snapshot.allChats.single().timestamp)
        assertTrue(store.getString("data", "").orEmpty().contains("future_metadata"))
        assertTrue(store.getString("data", "").orEmpty().contains("keep"))
    }

    @Test fun batchFolderRemovalRejectsStaleMembershipThenCommitsFolderAndRowsTogether() {
        val repo = repository(
            rows = listOf(chatRow("one", "One", 1), chatRow("two", "Two", 2)),
            ids = listOf(firstId).iterator()
        )
        repo.createFolder("Folder")
        repo.moveChat("one", firstId)
        repo.moveChat("two", firstId)

        val stale = repo.removeChatMetadataBatch(setOf("one"), firstId)
        assertEquals(
            ChatNavigationFailure.STALE_MEMBERSHIP,
            (stale as ChatNavigationResult.Failure).reason
        )
        assertEquals(2, (repo.snapshot() as ChatNavigationResult.Success).value.allChats.size)

        val removed = repo.removeChatMetadataBatch(setOf("one", "two"), firstId)
        assertTrue(removed is ChatNavigationResult.Success)
        val snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertTrue(snapshot.allChats.isEmpty())
        assertTrue(snapshot.folders.isEmpty())
    }

    @Test fun expansionPreferencesAreStableIdKeyedAndCleanedAfterFolderRemoval() {
        val presentation = FakeSharedPreferences()
        val repo = repository(
            presentationStore = presentation,
            ids = listOf(firstId).iterator()
        )
        val folder = (repo.createFolder("Before") as ChatNavigationResult.Success).value
        assertFalse(repo.areFoldersExpanded())
        assertFalse(repo.isFolderExpanded(folder.id))
        assertTrue(repo.setFoldersExpanded(true))
        assertTrue(repo.setFolderExpanded(folder.id, true))
        repo.renameFolder(folder.id, "After")
        assertTrue(repo.isFolderExpanded(folder.id))

        repo.removeChatMetadataBatch(emptySet(), folder.id)
        assertFalse(presentation.contains("chat_navigation.folder_expanded.${folder.id}"))
    }
}
