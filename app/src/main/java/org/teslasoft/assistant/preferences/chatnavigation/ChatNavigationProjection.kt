/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import java.text.Collator
import java.util.Locale
import org.teslasoft.assistant.preferences.ChatStorageHealth

object ChatNavigationProjection {
    fun build(
        chats: List<ChatNavigationItem>,
        folders: List<FolderRecord>,
        storageState: ChatStorageHealth.ReadState,
        locale: Locale = Locale.getDefault()
    ): ChatNavigationSnapshot {
        val chatOrder = compareByDescending<ChatNavigationItem> { it.timestamp }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.id }
        val orderedChats = chats.sortedWith(chatOrder)
        val pinnedChats = orderedChats.filter { it.pinned }
        val unpinnedChats = orderedChats.filterNot { it.pinned }

        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val folderOrder = Comparator<FolderRecord> { left, right ->
            when {
                left.pinned != right.pinned -> if (left.pinned) -1 else 1
                else -> collator.compare(left.name, right.name)
                    .takeIf { it != 0 }
                    ?: left.name.compareTo(right.name, ignoreCase = true)
                        .takeIf { it != 0 }
                    ?: left.id.compareTo(right.id)
            }
        }
        val orderedFolders = folders.sortedWith(folderOrder)
        val knownFolderIds = folders.mapTo(HashSet()) { it.id }

        val groups = orderedFolders.map { folder ->
            FolderNavigationGroup(
                folder,
                unpinnedChats.filter { it.folderId == folder.id }
            )
        }
        val unfiled = unpinnedChats.filter {
            it.folderId == null || it.folderId !in knownFolderIds
        }

        return ChatNavigationSnapshot(
            storageState = storageState,
            pinnedChats = pinnedChats,
            folders = groups,
            unfiledChats = unfiled,
            allChats = orderedChats
        )
    }
}
