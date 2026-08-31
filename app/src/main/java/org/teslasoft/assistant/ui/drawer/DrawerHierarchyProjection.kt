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
    fun build(
        snapshot: ChatNavigationSnapshot,
        foldersExpanded: Boolean,
        expandedFolderIds: Set<String>
    ): List<DrawerRow> = buildList {
        add(DrawerRow.Gallery)
        add(DrawerRow.FoldersHeader(foldersExpanded))
        if (foldersExpanded) {
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

