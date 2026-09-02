package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertTrue(repo.snapshot() is ChatNavigationResult.Success)
        assertEquals("{}", preserved)
        val repaired = store.getString(ChatNavigationRepository.FOLDERS_KEY, null).orEmpty()
        assertTrue(repaired.contains("\"version\":1"))
        assertTrue(repaired.contains("\"folders\":[]"))
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
