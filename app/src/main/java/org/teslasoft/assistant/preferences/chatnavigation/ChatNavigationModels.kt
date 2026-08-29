/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import org.teslasoft.assistant.preferences.ChatStorageHealth

/** Lightweight drawer metadata only. No transcript, preview, or attachment data belongs here. */
data class FolderRecord(
    val id: String,
    val name: String,
    val pinned: Boolean = false
)

data class ChatNavigationItem(
    val id: String,
    val name: String,
    val timestamp: Long,
    val pinned: Boolean,
    val folderId: String?
)

data class FolderNavigationGroup(
    val folder: FolderRecord,
    val chats: List<ChatNavigationItem>
)

/** A complete projection of every accessible chat and every persisted folder. */
data class ChatNavigationSnapshot(
    val storageState: ChatStorageHealth.ReadState,
    val pinnedChats: List<ChatNavigationItem>,
    val folders: List<FolderNavigationGroup>,
    val unfiledChats: List<ChatNavigationItem>,
    val allChats: List<ChatNavigationItem>
)

enum class ChatNavigationFailure {
    STORAGE_UNAVAILABLE,
    CORRUPT_FOLDERS,
    UNSUPPORTED_SCHEMA,
    BLANK_NAME,
    DUPLICATE_NAME,
    NOT_FOUND,
    INVALID_FOLDER_ID,
    STALE_MEMBERSHIP,
    COMMIT_FAILED
}

sealed class ChatNavigationResult<out T> {
    data class Success<T>(val value: T) : ChatNavigationResult<T>()
    data class Failure(val reason: ChatNavigationFailure) : ChatNavigationResult<Nothing>()
}

data class BatchMetadataRemoval(
    val removedChatIds: Set<String>,
    val removedFolderId: String?
)
