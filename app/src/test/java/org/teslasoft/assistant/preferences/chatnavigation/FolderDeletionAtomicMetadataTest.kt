package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderDeletionAtomicMetadataTest {
    private val folderId = "11111111-1111-4111-8111-111111111111"

    @Test fun staleMembershipCommitsNothingThenExactMembershipRemovesFolderAndRowsTogether() {
        val repo = repository(
            rows = listOf(chatRow("one", "One", 1), chatRow("two", "Two", 2)),
            ids = listOf(folderId).iterator()
        )
        repo.createFolder("Stable Folder")
        repo.moveChat("one", folderId)
        repo.moveChat("two", folderId)

        val stale = repo.removeChatMetadataBatch(setOf("one"), folderId)
        assertEquals(
            ChatNavigationFailure.STALE_MEMBERSHIP,
            (stale as ChatNavigationResult.Failure).reason
        )
        var snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertEquals(setOf("one", "two"), snapshot.allChats.mapTo(HashSet()) { it.id })
        assertEquals(folderId, snapshot.folders.single().folder.id)

        assertTrue(
            repo.removeChatMetadataBatch(setOf("one", "two"), folderId) is
                ChatNavigationResult.Success
        )
        snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertTrue(snapshot.allChats.isEmpty())
        assertTrue(snapshot.folders.isEmpty())
    }
}
