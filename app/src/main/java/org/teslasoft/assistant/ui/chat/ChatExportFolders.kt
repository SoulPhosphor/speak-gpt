package org.teslasoft.assistant.ui.chat

import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationSnapshot

/** Folder membership comes from all chats; pinned chats are absent from folder rows. */
object ChatExportFolders {
    fun names(snapshot: ChatNavigationSnapshot?): Map<String, String>? {
        if (snapshot == null || snapshot.foldersUnavailable) return null
        val folders = snapshot.folders.associate { it.folder.id to it.folder.name }
        return snapshot.allChats.mapNotNull { chat ->
            folders[chat.folderId]?.let { chat.id to it }
        }.toMap()
    }
}
