package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences

class ChatFolderMigrationTest {
    @Test fun migrationIsIdempotentAndDoesNotGuessLegacyAssignments() {
        val store = FakeSharedPreferences()
        val repo = repository(listOf(chatRow("legacy-id", "Legacy", 10)), chatStore = store)

        assertTrue(repo.migrateSchema() is ChatNavigationResult.Success)
        val firstFolders = store.getString(ChatNavigationRepository.FOLDERS_KEY, null)
        assertEquals(ChatNavigationRepository.SCHEMA_VERSION,
            store.getInt(ChatNavigationRepository.SCHEMA_VERSION_KEY, 0))
        assertFalse(store.getString("data", "").orEmpty().contains("folder_id"))

        assertTrue(repo.migrateSchema() is ChatNavigationResult.Success)
        assertEquals(firstFolders, store.getString(ChatNavigationRepository.FOLDERS_KEY, null))
    }

    @Test fun legacyRowsProjectAsUnfiledWithTheirExistingStableIds() {
        val repo = repository(listOf(chatRow("existing-stable-id", "Legacy", 10)))
        val snapshot = (repo.snapshot() as ChatNavigationResult.Success).value
        assertEquals("existing-stable-id", snapshot.unfiledChats.single().id)
        assertEquals(null, snapshot.unfiledChats.single().folderId)
    }
}
