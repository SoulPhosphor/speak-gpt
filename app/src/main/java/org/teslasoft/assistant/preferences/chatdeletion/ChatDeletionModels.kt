/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.chatdeletion

/** A deletion target is always expressed in durable identities, never names. */
sealed class ChatDeletionTarget {
    data class Chats(val chatIds: Set<String>) : ChatDeletionTarget()
    data class Folder(val folderId: String) : ChatDeletionTarget()
}

enum class ChatDeletionDecision {
    CANCEL,
    DELETE_CHAT_ONLY,
    DELETE_ALL
}

enum class ChatDeletionDialogVariant {
    ORDINARY,
    KEEP_IMAGES_NOTICE,
    DELETE_ALL,
    DELETE_ALL_WITH_LOCKED_IMAGES
}

data class ChatDeletionPolicyResult(
    val targetChatIds: Set<String>,
    val variant: ChatDeletionDialogVariant,
    val ownedImageCount: Int,
    val lockedImageCount: Int,
    val allowedDecisions: Set<ChatDeletionDecision>
)

data class ChatDeletionPreflight(
    val target: ChatDeletionTarget,
    val chatIds: Set<String>,
    val deleteImagesWithChatEnabled: Boolean,
    val policy: ChatDeletionPolicyResult
)

enum class ChatDeletionFailure {
    INVALID_TARGET,
    NOT_FOUND,
    STORAGE_UNAVAILABLE,
    STALE_TARGET,
    DECISION_NOT_ALLOWED,
    JOURNAL_WRITE_FAILED,
    METADATA_COMMIT_FAILED,
    CLEANUP_INCOMPLETE
}

sealed class ChatDeletionPreflightResult {
    data class Ready(val value: ChatDeletionPreflight) : ChatDeletionPreflightResult()
    data class Failure(val reason: ChatDeletionFailure) : ChatDeletionPreflightResult()
}

data class ChatDeletionExecutionResult(
    val metadataCommitted: Boolean,
    val cleanupComplete: Boolean,
    val failure: ChatDeletionFailure? = null
)

data class ChatDeletionRecoveryResult(
    val completed: Int,
    val deferred: Int,
    val journalAvailable: Boolean
)
