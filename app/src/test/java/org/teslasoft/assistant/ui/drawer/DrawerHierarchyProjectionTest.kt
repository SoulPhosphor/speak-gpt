package org.teslasoft.assistant.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationItem
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationProjection
import org.teslasoft.assistant.preferences.chatnavigation.FolderRecord

class DrawerHierarchyProjectionTest {
    @Test fun separateAreaLeavesOnlyTheFolderEntryInTheDrawerWithoutChangingExpansionChoices() {
        val folder = FolderRecord("world", "World")
        val nested = ChatNavigationItem("nested", "Nested", 1, false, folder.id)
        val outside = ChatNavigationItem("outside", "Outside", 2, false, null)
        val snapshot = ChatNavigationProjection.build(
            listOf(nested, outside), listOf(folder), ChatStorageHealth.ReadState.OK
        )
        val expanded = setOf(folder.id)
        val inline = DrawerHierarchyProjection.build(snapshot, true, expanded)
        val separate = DrawerHierarchyProjection.build(snapshot, true, expanded, separateFolders = true)
        assertEquals(DrawerRow.FoldersHeader(false), separate[1])
        assertFalse(separate.any { it is DrawerRow.Folder || (it is DrawerRow.Chat && it.nested) })
        assertEquals(listOf("outside"), separate.filterIsInstance<DrawerRow.Chat>().map { it.value.id })
        // Either lock setting can use the separate-area projection; only its entry is locked.
        assertEquals(emptyList<DrawerRow>(), DrawerHierarchyProjection.lockedLayers(separate).folderRows)
        // Turning separate-area mode off returns to the exact original inline hierarchy.
        assertEquals(inline, DrawerHierarchyProjection.build(snapshot, true, expanded, separateFolders = false))
        assertEquals(listOf("nested"), DrawerHierarchyProjection.lockedLayers(inline)
            .folderRows.filterIsInstance<DrawerRow.Chat>().map { it.value.id })
    }

    @Test fun lockedLayerContainsEveryExpandedFolderAndItsChats() {
        val a = FolderRecord("a", "Alpha")
        val b = FolderRecord("b", "Beta")
        val nested = DrawerRow.Chat(ChatNavigationItem("inside", "Inside", 1, false, a.id), true)
        val outside = DrawerRow.Chat(ChatNavigationItem("outside", "Outside", 2, false, null), false)
        val rows = listOf(
            DrawerRow.Gallery, DrawerRow.FoldersHeader(true),
            DrawerRow.Folder(a, true), nested, DrawerRow.Folder(b, false),
            DrawerRow.Section("Pinned Chats"), outside
        )
        val layers = DrawerHierarchyProjection.lockedLayers(rows)
        assertEquals(rows.subList(2, 5), layers.folderRows)
        assertEquals(listOf(rows[0], rows[1], rows[5], rows[6]), layers.mainRows)
        assertEquals(DrawerRow.FoldersHeader(true), layers.header)
        // Splitting the layer does not discard or duplicate any chat.
        assertEquals(
            rows.filterIsInstance<DrawerRow.Chat>().map { it.value.id }.sorted(),
            (layers.mainRows + layers.folderRows).filterIsInstance<DrawerRow.Chat>()
                .map { it.value.id }.sorted()
        )
    }

    @Test fun collapsedLockedContainerHasOnlyItsHeading() {
        val rows = listOf(DrawerRow.Gallery, DrawerRow.FoldersHeader(false))
        val layers = DrawerHierarchyProjection.lockedLayers(rows)
        assertEquals(rows, layers.mainRows)
        assertEquals(emptyList<DrawerRow>(), layers.folderRows)
        assertFalse(layers.header.expanded)
    }

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

