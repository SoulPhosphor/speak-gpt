package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.FakeSharedPreferences

class ChatNavigationStorageHealthTest {
    private val folderId = "11111111-1111-4111-8111-111111111111"

    @Test fun corruptFolderJsonIsPreservedAndBlocksEveryMutationWithoutRewritingChats() {
        val store = FakeSharedPreferences()
        var preserved: String? = null
        val repo = repository(
            rows = listOf(chatRow("chat-id", "Chat", 1)),
            chatStore = store,
            ids = listOf(folderId).iterator(),
            onCorrupt = { preserved = it }
        )
        repo.migrateSchema()
        val originalChats = store.getString("data", null)
        store.edit().putString(ChatNavigationRepository.FOLDERS_KEY, "{broken").commit()

        val result = repo.createFolder("Must Not Write")
        assertEquals(ChatNavigationFailure.CORRUPT_FOLDERS,
            (result as ChatNavigationResult.Failure).reason)
        assertEquals("{broken", preserved)
        assertEquals(originalChats, store.getString("data", null))
        assertEquals("{broken", store.getString(ChatNavigationRepository.FOLDERS_KEY, null))
    }

    @Test fun nonAuthoritativeChatReadBlocksMigrationAndMutation() {
        val store = FakeSharedPreferences()
        val repo = repository(
            rows = listOf(chatRow("chat-id", "Chat", 1)),
            chatStore = store,
            state = ChatStorageHealth.ReadState.LOCKED,
            ids = listOf(folderId).iterator()
        )
        val before = store.all
        val result = repo.createFolder("Blocked")
        assertEquals(ChatNavigationFailure.STORAGE_UNAVAILABLE,
            (result as ChatNavigationResult.Failure).reason)
        assertEquals(before, store.all)
        assertFalse(store.contains(ChatNavigationRepository.FOLDERS_KEY))
    }

    @Test fun failedCommitReturnsFailureAndDoesNotPretendSuccess() {
        val store = CommitFailingPreferences()
        val repo = repository(chatStore = store, ids = listOf(folderId).iterator())
        val result = repo.migrateSchema()
        assertTrue(result is ChatNavigationResult.Failure)
        assertEquals(ChatNavigationFailure.COMMIT_FAILED,
            (result as ChatNavigationResult.Failure).reason)
        assertFalse(store.contains(ChatNavigationRepository.FOLDERS_KEY))
    }

    @Test fun minifiedEmptyFolderWrapperIsPreservedThenRepairedWhenSchemaMarkerMatches() {
        val store = FakeSharedPreferences().apply {
            edit()
                .putString(ChatNavigationRepository.FOLDERS_KEY, "{}")
                .putInt(
                    ChatNavigationRepository.SCHEMA_VERSION_KEY,
                    ChatNavigationRepository.SCHEMA_VERSION
                )
                .commit()
        }
        var preserved: String? = null
        val repo = repository(
            rows = listOf(chatRow("chat-id", "Chat", 1)),
            chatStore = store,
            ids = listOf(folderId).iterator(),
            onCorrupt = { preserved = it }
        )

        val snapshot = repo.snapshot()
        assertTrue(snapshot is ChatNavigationResult.Success)
        assertEquals("chat-id", (snapshot as ChatNavigationResult.Success).value.allChats.single().id)
        assertEquals("{}", preserved)
        val repaired = store.getString(ChatNavigationRepository.FOLDERS_KEY, null).orEmpty()
        assertTrue(repaired.contains("\"version\":1"))
        assertTrue(repaired.contains("\"folders\":[]"))
    }

    @Test fun anAbsentFolderCatalogListsChatsAndStillAllowsPinning() {
        // A device that has never made a folder has no stored catalog. Treating
        // that as a storage failure took the whole drawer and the chat menu
        // down, and made pinning fail, over metadata that does not exist yet.
        val store = FolderCatalogHiddenPreferences(FakeSharedPreferences())
        var preserved: String? = null
        val repo = repository(
            rows = listOf(chatRow("chat-id", "Chat", 1)),
            chatStore = store,
            ids = listOf(folderId).iterator(),
            onCorrupt = { preserved = it }
        )

        val snapshot = repo.snapshot()
        assertTrue("absent folders must not fail the snapshot", snapshot is ChatNavigationResult.Success)
        val value = (snapshot as ChatNavigationResult.Success).value
        assertEquals("chat-id", value.allChats.single().id)
        assertTrue(value.folders.isEmpty())

        val pinned = repo.setChatPinned("chat-id", true)
        assertTrue("pinning must work with no folder catalog", pinned is ChatNavigationResult.Success)

        // Nothing was treated as corruption, so nothing was backed up.
        assertNull(preserved)
    }

    @Test fun emptyObjectWithoutMatchingSchemaMarkerRemainsBlockedAndUntouched() {
        val store = FakeSharedPreferences().apply {
            edit().putString(ChatNavigationRepository.FOLDERS_KEY, "{}").commit()
        }
        val repo = repository(
            rows = listOf(chatRow("chat-id", "Chat", 1)),
            chatStore = store,
            ids = listOf(folderId).iterator()
        )

        val result = repo.migrateSchema()
        assertEquals(
            ChatNavigationFailure.CORRUPT_FOLDERS,
            (result as ChatNavigationResult.Failure).reason
        )
        assertEquals("{}", store.getString(ChatNavigationRepository.FOLDERS_KEY, null))
    }
}

/**
 * A store whose folder catalog is never visible to a read, however it was
 * written. Reproduces the state of a device that has no stored catalog at the
 * moment the drawer or a chat action reads it.
 */
private class FolderCatalogHiddenPreferences(
    private val delegate: android.content.SharedPreferences
) : android.content.SharedPreferences by delegate {
    private val hidden = ChatNavigationRepository.FOLDERS_KEY

    override fun getAll(): MutableMap<String, *> =
        delegate.all.filterKeys { it != hidden }.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        if (key == hidden) defValue else delegate.getString(key, defValue)

    override fun contains(key: String?): Boolean =
        key != hidden && delegate.contains(key)
}
