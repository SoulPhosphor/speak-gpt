package org.teslasoft.assistant.ui.drawer

import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationItem
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationSnapshot
import org.teslasoft.assistant.preferences.chatnavigation.FolderRecord

sealed class DrawerRow(open val stableKey: String) {
    data object Gallery : DrawerRow("gallery")
    data class FoldersHeader(val expanded: Boolean) : DrawerRow("folders")
    data class Folder(val value: FolderRecord, val expanded: Boolean) : DrawerRow("folder:${value.id}")
    data class Section(val title: String) : DrawerRow("section:$title")
    data class Chat(val value: ChatNavigationItem, val nested: Boolean) : DrawerRow("chat:${value.id}")
}

object DrawerHierarchyProjection {
    data class LockedLayers(
        val mainRows: List<DrawerRow>,
        val header: DrawerRow.FoldersHeader,
        val folderRows: List<DrawerRow>
    )

    /** Keep the header's place in the main list, but move its complete visible
     * subtree into the independently scrolling layer. Unlocked lists never use this. */
    fun lockedLayers(rows: List<DrawerRow>): LockedLayers {
        val headerIndex = rows.indexOfFirst { it is DrawerRow.FoldersHeader }
        require(headerIndex >= 0)
        val folderRows = rows.drop(headerIndex + 1).takeWhile {
            it is DrawerRow.Folder || (it is DrawerRow.Chat && it.nested)
        }
        return LockedLayers(
            rows.take(headerIndex + 1) + rows.drop(headerIndex + 1 + folderRows.size),
            rows[headerIndex] as DrawerRow.FoldersHeader,
            folderRows
        )
    }

    fun build(
        snapshot: ChatNavigationSnapshot,
        foldersExpanded: Boolean,
        expandedFolderIds: Set<String>,
        separateFolders: Boolean = false
    ): List<DrawerRow> = buildList {
        val showFolders = foldersExpanded && !separateFolders
        add(DrawerRow.Gallery)
        add(DrawerRow.FoldersHeader(showFolders))
        if (showFolders) {
            snapshot.folders.forEach { group ->
                val expanded = group.folder.id in expandedFolderIds
                add(DrawerRow.Folder(group.folder, expanded))
                if (expanded) group.chats.forEach { add(DrawerRow.Chat(it, nested = true)) }
            }
        }
        if (snapshot.pinnedChats.isNotEmpty()) {
            add(DrawerRow.Section("Pinned Chats"))
            snapshot.pinnedChats.forEach { add(DrawerRow.Chat(it, nested = false)) }
        }
        snapshot.unfiledChats.forEach { add(DrawerRow.Chat(it, nested = false)) }
    }
}

