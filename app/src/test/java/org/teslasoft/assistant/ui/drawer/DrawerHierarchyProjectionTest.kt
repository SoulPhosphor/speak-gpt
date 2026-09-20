package org.teslasoft.assistant.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationItem
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationProjection
import org.teslasoft.assistant.preferences.chatnavigation.FolderRecord

class DrawerHierarchyProjectionTest {
    @Test fun pinnedAssignedChatAppearsOnceAndCollapsedFoldersHideOnlyPresentation() {
        val folder = FolderRecord("123e4567-e89b-12d3-a456-426614174000", "World")
        val pinned = ChatNavigationItem("p", "Pinned", 2, true, folder.id)
        val nested = ChatNavigationItem("n", "Nested", 1, false, folder.id)
        val snapshot = ChatNavigationProjection.build(
            listOf(pinned, nested), listOf(folder), ChatStorageHealth.ReadState.OK
        )
        val collapsed = DrawerHierarchyProjection.build(snapshot, false, emptySet())
        assertEquals(1, collapsed.filterIsInstance<DrawerRow.Chat>().count { it.value.id == "p" })
        assertFalse(collapsed.filterIsInstance<DrawerRow.Chat>().any { it.value.id == "n" })
        assertEquals(2, snapshot.allChats.size)

        val expanded = DrawerHierarchyProjection.build(snapshot, true, setOf(folder.id))
        assertEquals(1, expanded.filterIsInstance<DrawerRow.Chat>().count { it.value.id == "p" })
        assertEquals(1, expanded.filterIsInstance<DrawerRow.Chat>().count { it.value.id == "n" })
    }
}

