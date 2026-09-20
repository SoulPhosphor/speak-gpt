package org.teslasoft.assistant.preferences.chatnavigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNamePolicyTest {
    private val folder = FolderRecord("11111111-1111-4111-8111-111111111111", "World Chats")

    @Test fun trimsBeforeReturningPersistedName() {
        assertEquals(
            FolderNamePolicy.Validation.Valid("New Folder"),
            FolderNamePolicy.validate("  New Folder  ", listOf(folder))
        )
    }

    @Test fun rejectsBlankAndCaseInsensitiveTrimmedDuplicate() {
        assertTrue(FolderNamePolicy.validate(" \n ", listOf(folder)) is FolderNamePolicy.Validation.Blank)
        assertTrue(
            FolderNamePolicy.validate(" world chats ", listOf(folder))
                is FolderNamePolicy.Validation.Duplicate
        )
    }

    @Test fun renameExcludesOnlyTheSameStableId() {
        assertEquals(
            FolderNamePolicy.Validation.Valid("World Chats"),
            FolderNamePolicy.validate("World Chats", listOf(folder), folder.id)
        )
    }
}
