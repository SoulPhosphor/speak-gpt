/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.chatdeletion

/** Pure decision matrix from image-gallery-spec.md sections 9-10. */
object ChatDeletionPolicy {
    fun decide(
        targetChatIds: Set<String>,
        deleteImagesWithChatEnabled: Boolean,
        ownedImageCount: Int,
        lockedImageCount: Int
    ): ChatDeletionPolicyResult {
        require(targetChatIds.none { it.isBlank() })
        require(ownedImageCount >= 0)
        require(lockedImageCount in 0..ownedImageCount)

        val variant = when {
            ownedImageCount == 0 -> ChatDeletionDialogVariant.ORDINARY
            !deleteImagesWithChatEnabled -> ChatDeletionDialogVariant.KEEP_IMAGES_NOTICE
            lockedImageCount > 0 -> ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES
            else -> ChatDeletionDialogVariant.DELETE_ALL
        }
        val decisions = when (variant) {
            ChatDeletionDialogVariant.DELETE_ALL,
            ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES -> setOf(
                ChatDeletionDecision.CANCEL,
                ChatDeletionDecision.DELETE_CHAT_ONLY,
                ChatDeletionDecision.DELETE_ALL
            )
            else -> setOf(
                ChatDeletionDecision.CANCEL,
                ChatDeletionDecision.DELETE_CHAT_ONLY
            )
        }
        return ChatDeletionPolicyResult(
            targetChatIds = targetChatIds.toSet(),
            variant = variant,
            ownedImageCount = ownedImageCount,
            lockedImageCount = lockedImageCount,
            allowedDecisions = decisions
        )
    }
}
