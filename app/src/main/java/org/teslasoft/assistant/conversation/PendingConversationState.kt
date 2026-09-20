/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.conversation

data class PendingConversationState(
    val id: String,
    val name: String,
    val mode: ConversationMode = ConversationMode.CHAT
)

sealed class PendingConversationCommitResult {
    data object Ok : PendingConversationCommitResult()
    data object AlreadyCommitted : PendingConversationCommitResult()
    data object StorageUnavailable : PendingConversationCommitResult()
    data object CommitFailed : PendingConversationCommitResult()

    val succeeded: Boolean
        get() = this === Ok || this === AlreadyCommitted
}
