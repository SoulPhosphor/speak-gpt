package org.teslasoft.assistant.ui.drawer

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationProjection
import org.teslasoft.assistant.preferences.chatnavigation.FolderRecord

class FolderInteractionPolicyTest {
    @Test fun pinnedFoldersStayInsideFoldersAndSortBeforeAlphabeticalUnpinnedFolders() {
        val folders = listOf(
            FolderRecord("00000000-0000-0000-0000-000000000001", "Beta"),
            FolderRecord("00000000-0000-0000-0000-000000000002", "Zulu", pinned = true),
            FolderRecord("00000000-0000-0000-0000-000000000003", "Alpha")
        )
        val snapshot = ChatNavigationProjection.build(
            emptyList(), folders, ChatStorageHealth.ReadState.EMPTY, Locale.US
        )
        val rows = DrawerHierarchyProjection.build(
            snapshot, foldersExpanded = true, expandedFolderIds = emptySet()
        )

        assertEquals(listOf("Zulu", "Alpha", "Beta"), rows.filterIsInstance<DrawerRow.Folder>().map { it.value.name })
        assertTrue(rows.none { it is DrawerRow.Section && it.title == "Zulu" })
    }
}
