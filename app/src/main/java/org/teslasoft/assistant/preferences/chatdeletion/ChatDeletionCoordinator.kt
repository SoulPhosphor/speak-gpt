/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.chatdeletion

import android.content.Context
import java.util.UUID
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexManager
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageAssetDeletionDisposition
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore

internal sealed class DeletionBackendResult<out T> {
    data class Success<T>(val value: T) : DeletionBackendResult<T>()
    data object Unavailable : DeletionBackendResult<Nothing>()
}

internal data class DeletionNavigationSnapshot(
    val chats: List<Pair<String, String?>>,
    val folderIds: Set<String>
)

internal interface ChatDeletionNavigationGateway {
    fun snapshot(): DeletionBackendResult<DeletionNavigationSnapshot>
    fun removeMetadata(chatIds: Set<String>, folderId: String?): Boolean
}

internal data class CatalogTombstoneResult(
    val success: Boolean,
    val removedImageIds: Set<String> = emptySet(),
    val lockedImageIds: Set<String> = emptySet()
)

internal interface ChatDeletionCatalogGateway {
    fun ownedImages(chatIds: Set<String>): DeletionBackendResult<List<GeneratedImageCatalogRecord>>
    fun tombstoneUnlockedOwned(
        chatIds: Set<String>,
        candidateImageIds: Set<String>
    ): DeletionBackendResult<CatalogTombstoneResult>
    fun deleteAssetIfUnreferenced(assetFileName: String): DeletionBackendResult<Boolean>
}

internal interface ChatDeletionCleanupGateway {
    fun cleanupChat(chatId: String): Boolean
}

/**
 * Storage coordinator for every chat/folder deletion path. The UI obtains one
 * preflight, displays its policy, then returns an explicit decision here.
 */
class ChatDeletionCoordinator internal constructor(
    private val navigation: ChatDeletionNavigationGateway,
    private val catalog: ChatDeletionCatalogGateway,
    private val journal: ChatDeletionJournalStore,
    private val cleanup: ChatDeletionCleanupGateway,
    private val deleteImagesWithChat: () -> Boolean,
    private val onChatsDeleted: (Set<String>) -> Unit = {}
) {
    companion object {
        private const val MAX_IMAGE_SETTLEMENT_PASSES = 3

        fun get(context: Context): ChatDeletionCoordinator {
            val app = context.applicationContext
            val navigationRepository = ChatNavigationRepository.get(app)
            return ChatDeletionCoordinator(
                navigation = object : ChatDeletionNavigationGateway {
                    override fun snapshot(): DeletionBackendResult<DeletionNavigationSnapshot> {
                        return when (val result = navigationRepository.snapshot()) {
                            is ChatNavigationResult.Success -> DeletionBackendResult.Success(
                                DeletionNavigationSnapshot(
                                    chats = result.value.allChats.map { it.id to it.folderId },
                                    folderIds = result.value.folders.mapTo(LinkedHashSet()) { it.folder.id }
                                )
                            )
                            is ChatNavigationResult.Failure -> DeletionBackendResult.Unavailable
                        }
                    }

                    override fun removeMetadata(chatIds: Set<String>, folderId: String?): Boolean =
                        navigationRepository.removeChatMetadataBatch(chatIds, folderId) is
                            ChatNavigationResult.Success
                },
                catalog = object : ChatDeletionCatalogGateway {
                    override fun ownedImages(chatIds: Set<String>): DeletionBackendResult<List<GeneratedImageCatalogRecord>> {
                        val result = GeneratedImageCatalogStore.listOwnedByChats(app, chatIds)
                        return if (result.state == GeneratedImageCatalogStorageState.AVAILABLE) {
                            DeletionBackendResult.Success(result.records)
                        } else {
                            DeletionBackendResult.Unavailable
                        }
                    }

                    override fun tombstoneUnlockedOwned(
                        chatIds: Set<String>,
                        candidateImageIds: Set<String>
                    ): DeletionBackendResult<CatalogTombstoneResult> {
                        val result = GeneratedImageCatalogStore.tombstoneUnlockedOwned(
                            app,
                            chatIds,
                            candidateImageIds
                        )
                        return if (result.state == GeneratedImageCatalogStorageState.AVAILABLE && result.success) {
                            DeletionBackendResult.Success(
                                CatalogTombstoneResult(
                                    success = true,
                                    removedImageIds = result.removed.mapTo(LinkedHashSet()) { it.imageId },
                                    lockedImageIds = result.lockedImageIds
                                )
                            )
                        } else {
                            DeletionBackendResult.Unavailable
                        }
                    }

                    override fun deleteAssetIfUnreferenced(assetFileName: String): DeletionBackendResult<Boolean> {
                        val result = GeneratedImageCatalogStore.deleteAssetIfUnreferenced(app, assetFileName)
                        return if (result.state != GeneratedImageCatalogStorageState.AVAILABLE) {
                            DeletionBackendResult.Unavailable
                        } else {
                            DeletionBackendResult.Success(
                                result.disposition != GeneratedImageAssetDeletionDisposition.FAILED
                            )
                        }
                    }
                },
                journal = ChatDeletionJournalStore.get(app),
                cleanup = object : ChatDeletionCleanupGateway {
                    override fun cleanupChat(chatId: String): Boolean =
                        ChatPreferences.getChatPreferences().cleanupDeletedChatData(app, chatId)
                },
                deleteImagesWithChat = {
                    Preferences.getPreferences(app, "").getDeleteImagesWithChat()
                },
                onChatsDeleted = { ChatSearchIndexManager.get(app).scheduleChatsDeleted(it) }
            )
        }
    }

    fun preflight(target: ChatDeletionTarget): ChatDeletionPreflightResult {
        val stableTarget = when (target) {
            is ChatDeletionTarget.Chats -> ChatDeletionTarget.Chats(target.chatIds.toSet())
            is ChatDeletionTarget.Folder -> {
                if (!isUuid(target.folderId)) {
                    return failure(ChatDeletionFailure.INVALID_TARGET)
                }
                target.copy()
            }
        }
        val navigationSnapshot = when (val result = navigation.snapshot()) {
            is DeletionBackendResult.Success -> result.value
            DeletionBackendResult.Unavailable -> return failure(ChatDeletionFailure.STORAGE_UNAVAILABLE)
        }
        val chatIds = resolveTarget(stableTarget, navigationSnapshot)
            ?: return failure(ChatDeletionFailure.NOT_FOUND)
        if (chatIds.any { it.isBlank() }) return failure(ChatDeletionFailure.INVALID_TARGET)
        val owned = when (val result = catalog.ownedImages(chatIds)) {
            is DeletionBackendResult.Success -> result.value
            DeletionBackendResult.Unavailable -> return failure(ChatDeletionFailure.STORAGE_UNAVAILABLE)
        }
        val setting = try {
            deleteImagesWithChat()
        } catch (_: Exception) {
            return failure(ChatDeletionFailure.STORAGE_UNAVAILABLE)
        }
        return ChatDeletionPreflightResult.Ready(
            ChatDeletionPreflight(
                target = stableTarget,
                chatIds = chatIds,
                deleteImagesWithChatEnabled = setting,
                policy = ChatDeletionPolicy.decide(
                    targetChatIds = chatIds,
                    deleteImagesWithChatEnabled = setting,
                    ownedImageCount = owned.size,
                    lockedImageCount = owned.count { it.locked }
                )
            )
        )
    }

    fun execute(
        preflight: ChatDeletionPreflight,
        decision: ChatDeletionDecision,
        onMetadataCommitted: () -> Unit = {}
    ): ChatDeletionExecutionResult {
        if (decision == ChatDeletionDecision.CANCEL) {
            return ChatDeletionExecutionResult(false, true)
        }
        if (preflight.policy.targetChatIds != preflight.chatIds ||
            decision !in preflight.policy.allowedDecisions
        ) {
            return failedExecution(ChatDeletionFailure.DECISION_NOT_ALLOWED)
        }

        val navigationSnapshot = when (val result = navigation.snapshot()) {
            is DeletionBackendResult.Success -> result.value
            DeletionBackendResult.Unavailable -> return failedExecution(ChatDeletionFailure.STORAGE_UNAVAILABLE)
        }
        val currentChatIds = resolveTarget(preflight.target, navigationSnapshot)
            ?: return failedExecution(ChatDeletionFailure.STALE_TARGET)
        if (currentChatIds != preflight.chatIds) {
            return failedExecution(ChatDeletionFailure.STALE_TARGET)
        }

        // Re-read catalog ownership and Lock immediately before the journal +
        // visible metadata commit. Inaccessible catalog state is never treated
        // as permission to proceed with a destructive image decision.
        val currentOwned = when (val result = catalog.ownedImages(currentChatIds)) {
            is DeletionBackendResult.Success -> result.value
            DeletionBackendResult.Unavailable ->
                return failedExecution(ChatDeletionFailure.STORAGE_UNAVAILABLE)
        }
        val currentSetting = try {
            deleteImagesWithChat()
        } catch (_: Exception) {
            return failedExecution(ChatDeletionFailure.STORAGE_UNAVAILABLE)
        }
        val currentPolicy = ChatDeletionPolicy.decide(
            targetChatIds = currentChatIds,
            deleteImagesWithChatEnabled = currentSetting,
            ownedImageCount = currentOwned.size,
            lockedImageCount = currentOwned.count { it.locked }
        )
        if (decision !in currentPolicy.allowedDecisions) {
            return failedExecution(ChatDeletionFailure.DECISION_NOT_ALLOWED)
        }

        val folderId = (preflight.target as? ChatDeletionTarget.Folder)?.folderId
        val entry = when (val write = journal.create(currentChatIds, folderId, decision)) {
            is ChatDeletionJournalWrite.Success -> write.entry
            ChatDeletionJournalWrite.Failure -> return failedExecution(ChatDeletionFailure.JOURNAL_WRITE_FAILED)
        }
        if (!navigation.removeMetadata(currentChatIds, folderId)) {
            journal.remove(entry.journalId)
            return failedExecution(ChatDeletionFailure.METADATA_COMMIT_FAILED)
        }

        onChatsDeleted(currentChatIds)

        var cleanupEntry = entry.copy(stage = ChatDeletionJournalStage.CLEANUP_PENDING)
        journal.update(cleanupEntry)
        onMetadataCommitted()
        val complete = finishCleanup(cleanupEntry)
        return if (complete) {
            ChatDeletionExecutionResult(true, true)
        } else {
            ChatDeletionExecutionResult(true, false, ChatDeletionFailure.CLEANUP_INCOMPLETE)
        }
    }

    fun recover(): ChatDeletionRecoveryResult {
        val entries = when (val read = journal.read()) {
            is ChatDeletionJournalRead.Available -> read.entries
            ChatDeletionJournalRead.Unavailable -> return ChatDeletionRecoveryResult(0, 0, false)
        }
        var completed = 0
        var deferred = 0
        for (original in entries) {
            var entry = original
            if (entry.stage == ChatDeletionJournalStage.METADATA_PENDING) {
                val prepared = recoverMetadata(entry)
                if (prepared == null) {
                    deferred++
                    continue
                }
                entry = prepared
            }
            if (finishCleanup(entry)) completed++ else deferred++
        }
        return ChatDeletionRecoveryResult(completed, deferred, true)
    }

    private fun recoverMetadata(entry: ChatDeletionJournalEntry): ChatDeletionJournalEntry? {
        val snapshot = when (val result = navigation.snapshot()) {
            is DeletionBackendResult.Success -> result.value
            DeletionBackendResult.Unavailable -> return null
        }
        val currentIds = snapshot.chats.map { it.first }
        if (currentIds.size != currentIds.toSet().size) return null
        val remaining = entry.chatIds.filterTo(LinkedHashSet()) { it in currentIds }
        val folderId = entry.folderId

        val metadataAlreadyCommitted = remaining.isEmpty() &&
            (folderId == null || folderId !in snapshot.folderIds)
        if (!metadataAlreadyCommitted) {
            if (catalog.ownedImages(entry.chatIds) !is DeletionBackendResult.Success) return null
            if (folderId != null) {
                if (folderId !in snapshot.folderIds) return null
                val members = snapshot.chats.filter { it.second == folderId }.mapTo(LinkedHashSet()) { it.first }
                if (members != entry.chatIds) return null
                if (!navigation.removeMetadata(entry.chatIds, folderId)) return null
            } else {
                if (remaining.isEmpty() || !navigation.removeMetadata(remaining, null)) return null
            }
        }
        return entry.copy(stage = ChatDeletionJournalStage.CLEANUP_PENDING).also {
            journal.update(it)
        }
    }

    private fun finishCleanup(startingEntry: ChatDeletionJournalEntry): Boolean {
        var entry = startingEntry
        var chatsComplete = true
        for (chatId in entry.chatIds) {
            if (!cleanup.cleanupChat(chatId)) chatsComplete = false
        }

        var imagesComplete = true
        if (entry.decision == ChatDeletionDecision.DELETE_ALL) {
            var settled = false
            for (pass in 0 until MAX_IMAGE_SETTLEMENT_PASSES) {
                val owned = when (val result = catalog.ownedImages(entry.chatIds)) {
                    is DeletionBackendResult.Success -> result.value
                    DeletionBackendResult.Unavailable -> {
                        imagesComplete = false
                        break
                    }
                }
                val candidates = owned.filterNot { it.locked }
                if (candidates.isEmpty()) {
                    settled = true
                    break
                }
                val planned = entry.copy(
                    candidateAssetFileNames = entry.candidateAssetFileNames +
                        candidates.map { it.assetFileName }
                )
                if (!journal.update(planned)) {
                    imagesComplete = false
                    break
                }
                entry = planned
                val tombstone = catalog.tombstoneUnlockedOwned(
                    entry.chatIds,
                    candidates.mapTo(LinkedHashSet()) { it.imageId }
                )
                if (tombstone !is DeletionBackendResult.Success || !tombstone.value.success) {
                    imagesComplete = false
                    break
                }
            }
            if (!settled) {
                val remainingUnlocked = when (val result = catalog.ownedImages(entry.chatIds)) {
                    is DeletionBackendResult.Success -> result.value.any { !it.locked }
                    DeletionBackendResult.Unavailable -> true
                }
                if (remainingUnlocked) imagesComplete = false
            }
            for (assetFileName in entry.candidateAssetFileNames) {
                val deleted = catalog.deleteAssetIfUnreferenced(assetFileName)
                if (deleted !is DeletionBackendResult.Success || !deleted.value) {
                    imagesComplete = false
                }
            }
        }

        if (!chatsComplete || !imagesComplete) return false
        return journal.remove(entry.journalId)
    }

    private fun resolveTarget(
        target: ChatDeletionTarget,
        snapshot: DeletionNavigationSnapshot
    ): Set<String>? {
        val ids = snapshot.chats.map { it.first }
        if (ids.size != ids.toSet().size) return null
        return when (target) {
            is ChatDeletionTarget.Chats -> {
                if (target.chatIds.isEmpty() || target.chatIds.any { it.isBlank() } ||
                    !ids.toSet().containsAll(target.chatIds)
                ) null else target.chatIds.toSet()
            }
            is ChatDeletionTarget.Folder -> {
                if (target.folderId !in snapshot.folderIds) null
                else snapshot.chats.filter { it.second == target.folderId }
                    .mapTo(LinkedHashSet()) { it.first }
            }
        }
    }

    private fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun failure(reason: ChatDeletionFailure) = ChatDeletionPreflightResult.Failure(reason)

    private fun failedExecution(reason: ChatDeletionFailure) =
        ChatDeletionExecutionResult(false, false, reason)
}
