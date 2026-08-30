/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

data class ImageGalleryActionPolicy(
    val canGoToChat: Boolean,
    val canAddToAvatarGallery: Boolean,
    val lockAction: LockAction,
    val canDelete: Boolean
) {
    enum class LockAction { LOCK, UNLOCK }

    companion object {
        fun forRecord(record: GeneratedImageCatalogRecord, originChatExists: Boolean) =
            ImageGalleryActionPolicy(
                canGoToChat = record.originChatId != null && originChatExists,
                canAddToAvatarGallery = true,
                lockAction = if (record.locked) LockAction.UNLOCK else LockAction.LOCK,
                canDelete = !record.locked
            )
    }
}

class ImageGallerySelection {
    private val selected = LinkedHashSet<String>()

    fun ids(): Set<String> = selected.toSet()

    fun toggle(record: GeneratedImageCatalogRecord): Boolean {
        if (record.locked) return false
        if (!selected.remove(record.imageId)) selected.add(record.imageId)
        return true
    }

    fun retainEligible(records: List<GeneratedImageCatalogRecord>) {
        val eligible = records.filterNot { it.locked }.mapTo(HashSet()) { it.imageId }
        selected.retainAll(eligible)
    }

    fun clear() = selected.clear()
}
