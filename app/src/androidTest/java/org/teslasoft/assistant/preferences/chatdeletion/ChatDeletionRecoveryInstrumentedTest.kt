/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.chatdeletion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord

/** Arm64 device coverage for the encrypted metadata/journal boundaries. */
@RunWith(AndroidJUnit4::class)
class ChatDeletionRecoveryInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetStores() {
        SecurePrefs.get(context, "chat_list").edit().clear().commit()
        SecurePrefs.get(context, ChatDeletionJournalStore.FILE_NAME).edit().clear().commit()
        ChatStorageHealth.clearReadFailure(context, "chat_list")
    }

    @After
    fun cleanupStores() {
        SecurePrefs.get(context, "chat_list").edit().clear().commit()
        SecurePrefs.get(context, ChatDeletionJournalStore.FILE_NAME).edit().clear().commit()
        ChatStorageHealth.clearReadFailure(context, "chat_list")
    }

    @Test
    fun realEncryptedFolderMetadataCommitRemovesFolderAndMembersTogether() {
        val rows = arrayListOf(
            hashMapOf("id" to "chat-one", "name" to "One", "timestamp" to "1", "pinned" to "false"),
            hashMapOf("id" to "chat-two", "name" to "Two", "timestamp" to "2", "pinned" to "false")
        )
        assertTrue(
            SecurePrefs.get(context, "chat_list").edit()
                .putString("data", Gson().toJson(rows)).commit()
        )
        val repository = ChatNavigationRepository.get(context)
        val folder = (repository.createFolder("Delete Together") as ChatNavigationResult.Success).value
        repository.moveChat("chat-one", folder.id)
        repository.moveChat("chat-two", folder.id)

        assertTrue(
            repository.removeChatMetadataBatch(setOf("chat-one", "chat-two"), folder.id) is
                ChatNavigationResult.Success
        )
        val snapshot = (repository.snapshot() as ChatNavigationResult.Success).value
        assertTrue(snapshot.allChats.isEmpty())
        assertTrue(snapshot.folders.isEmpty())
    }

    @Test
    fun encryptedPendingJournalRecoversIdempotentlyAfterCleanupFailure() {
        val navigation = FakeNavigation(mutableSetOf("chat"))
        val catalog = EmptyCatalog()
        val cleanup = ToggleCleanup(false)
        val first = ChatDeletionCoordinator(
            navigation,
            catalog,
            ChatDeletionJournalStore.get(context),
            cleanup,
            deleteImagesWithChat = { false }
        )
        val preflight = (first.preflight(ChatDeletionTarget.Chats(setOf("chat")))
            as ChatDeletionPreflightResult.Ready).value

        val interrupted = first.execute(preflight, ChatDeletionDecision.DELETE_CHAT_ONLY)
        assertTrue(interrupted.metadataCommitted)
        assertFalse(interrupted.cleanupComplete)
        assertTrue(navigation.chatIds.isEmpty())

        cleanup.succeeds = true
        val recreated = ChatDeletionCoordinator(
            navigation,
            catalog,
            ChatDeletionJournalStore.get(context),
            cleanup,
            deleteImagesWithChat = { false }
        )
        val recovered = recreated.recover()
        assertEquals(1, recovered.completed)
        assertEquals(0, recovered.deferred)
        assertEquals(0, recreated.recover().completed)
        assertTrue(
            (ChatDeletionJournalStore.get(context).read() as
                ChatDeletionJournalRead.Available).entries.isEmpty()
        )
    }

    private class FakeNavigation(
        val chatIds: MutableSet<String>
    ) : ChatDeletionNavigationGateway {
        override fun snapshot() = DeletionBackendResult.Success(
            DeletionNavigationSnapshot(chatIds.map { it to null }, emptySet())
        )

        override fun removeMetadata(chatIds: Set<String>, folderId: String?): Boolean {
            if (!this.chatIds.containsAll(chatIds) || folderId != null) return false
            this.chatIds.removeAll(chatIds)
            return true
        }
    }

    private class EmptyCatalog : ChatDeletionCatalogGateway {
        override fun ownedImages(chatIds: Set<String>) =
            DeletionBackendResult.Success(emptyList<GeneratedImageCatalogRecord>())

        override fun tombstoneUnlockedOwned(
            chatIds: Set<String>,
            candidateImageIds: Set<String>
        ) = DeletionBackendResult.Success(CatalogTombstoneResult(true))

        override fun deleteAssetIfUnreferenced(assetFileName: String) =
            DeletionBackendResult.Success(true)
    }

    private class ToggleCleanup(var succeeds: Boolean) : ChatDeletionCleanupGateway {
        override fun cleanupChat(chatId: String): Boolean = succeeds
    }
}
