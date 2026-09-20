package org.teslasoft.assistant.preferences.chatdeletion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord

class ChatDeletionCoordinatorTest {
    private val journalId = "11111111-1111-4111-8111-111111111111"
    private val folderId = "22222222-2222-4222-8222-222222222222"

    @Test fun deleteChatOnlyCommitsMetadataThenCleansDataWithoutTouchingImages() {
        val fixture = Fixture(listOf("chat" to null), records = mutableListOf(image("image", "chat")))
        val coordinator = fixture.coordinator(setting = false)
        val preflight = (coordinator.preflight(ChatDeletionTarget.Chats(setOf("chat")))
            as ChatDeletionPreflightResult.Ready).value
        var visibleCommitObserved = false
        val result = coordinator.execute(preflight, ChatDeletionDecision.DELETE_CHAT_ONLY) {
            visibleCommitObserved = true
            assertTrue(fixture.navigation.chats.isEmpty())
        }
        assertTrue(result.metadataCommitted)
        assertTrue(result.cleanupComplete)
        assertTrue(visibleCommitObserved)
        assertEquals(setOf("chat"), fixture.cleanup.cleaned)
        assertEquals(listOf("image"), fixture.catalog.records.map { it.imageId })
        assertEquals(0, fixture.catalog.tombstoneCalls)
    }

    @Test fun lockAcquiredAfterDialogStillVetoesDeleteAll() {
        val fixture = Fixture(listOf("chat" to null), records = mutableListOf(image("image", "chat")))
        val coordinator = fixture.coordinator(setting = true)
        val preflight = (coordinator.preflight(ChatDeletionTarget.Chats(setOf("chat")))
            as ChatDeletionPreflightResult.Ready).value
        fixture.catalog.records[0] = fixture.catalog.records[0].copy(locked = true)

        val result = coordinator.execute(preflight, ChatDeletionDecision.DELETE_ALL)
        assertTrue(result.cleanupComplete)
        assertTrue(fixture.catalog.records.single().locked)
        assertEquals(0, fixture.catalog.tombstoneCalls)
    }

    @Test fun copiedReferenceDoesNotBecomeOwnedByTheDeletedChat() {
        val fixture = Fixture(
            listOf("copy" to null, "origin" to null),
            records = mutableListOf(image("image", "origin"))
        )
        val coordinator = fixture.coordinator(setting = true)
        val preflight = (coordinator.preflight(ChatDeletionTarget.Chats(setOf("copy")))
            as ChatDeletionPreflightResult.Ready).value
        assertEquals(ChatDeletionDialogVariant.ORDINARY, preflight.policy.variant)
        coordinator.execute(preflight, ChatDeletionDecision.DELETE_CHAT_ONLY)
        assertEquals(listOf("image"), fixture.catalog.records.map { it.imageId })
    }

    @Test fun staleFolderMembershipChangesNothing() {
        val fixture = Fixture(listOf("one" to folderId), folders = mutableSetOf(folderId))
        val coordinator = fixture.coordinator(setting = true)
        val preflight = (coordinator.preflight(ChatDeletionTarget.Folder(folderId))
            as ChatDeletionPreflightResult.Ready).value
        fixture.navigation.chats.add("two" to folderId)

        val result = coordinator.execute(preflight, ChatDeletionDecision.DELETE_CHAT_ONLY)
        assertFalse(result.metadataCommitted)
        assertEquals(ChatDeletionFailure.STALE_TARGET, result.failure)
        assertEquals(setOf("one", "two"), fixture.navigation.chats.mapTo(HashSet()) { it.first })
        assertTrue(folderId in fixture.navigation.folders)
        assertTrue((fixture.journal.read() as ChatDeletionJournalRead.Available).entries.isEmpty())
    }

    @Test fun folderDeletionAcceptsOnlyTheStableUuidIdentity() {
        val fixture = Fixture(emptyList(), folders = mutableSetOf("not-a-uuid"))
        val result = fixture.coordinator(setting = true)
            .preflight(ChatDeletionTarget.Folder("not-a-uuid"))
        assertEquals(
            ChatDeletionFailure.INVALID_TARGET,
            (result as ChatDeletionPreflightResult.Failure).reason
        )
        assertTrue("not-a-uuid" in fixture.navigation.folders)
    }

    @Test fun interruptedCleanupKeepsJournalAndRecoveryFinishesWithoutRecreatingMetadata() {
        val fixture = Fixture(listOf("chat" to null))
        fixture.cleanup.succeeds = false
        val coordinator = fixture.coordinator(setting = false)
        val preflight = (coordinator.preflight(ChatDeletionTarget.Chats(setOf("chat")))
            as ChatDeletionPreflightResult.Ready).value

        val interrupted = coordinator.execute(preflight, ChatDeletionDecision.DELETE_CHAT_ONLY)
        assertTrue(interrupted.metadataCommitted)
        assertFalse(interrupted.cleanupComplete)
        assertTrue(fixture.navigation.chats.isEmpty())
        assertEquals(
            ChatDeletionJournalStage.CLEANUP_PENDING,
            (fixture.journal.read() as ChatDeletionJournalRead.Available).entries.single().stage
        )

        fixture.cleanup.succeeds = true
        val recovered = coordinator.recover()
        assertEquals(1, recovered.completed)
        assertEquals(0, recovered.deferred)
        assertTrue(fixture.navigation.chats.isEmpty())
        assertTrue((fixture.journal.read() as ChatDeletionJournalRead.Available).entries.isEmpty())
    }

    @Test fun fabricatedDeleteAllDecisionCannotBypassTheCurrentGlobalSetting() {
        val fixture = Fixture(listOf("chat" to null), records = mutableListOf(image("image", "chat")))
        val coordinator = fixture.coordinator(setting = false)
        val actual = (coordinator.preflight(ChatDeletionTarget.Chats(setOf("chat")))
            as ChatDeletionPreflightResult.Ready).value
        val forged = actual.copy(
            policy = actual.policy.copy(
                allowedDecisions = actual.policy.allowedDecisions + ChatDeletionDecision.DELETE_ALL
            )
        )

        val result = coordinator.execute(forged, ChatDeletionDecision.DELETE_ALL)
        assertFalse(result.metadataCommitted)
        assertEquals(ChatDeletionFailure.DECISION_NOT_ALLOWED, result.failure)
        assertEquals(listOf("chat"), fixture.navigation.chats.map { it.first })
        assertEquals(listOf("image"), fixture.catalog.records.map { it.imageId })
    }

    private fun image(id: String, owner: String) = GeneratedImageCatalogRecord(
        imageId = id,
        fileHash = "hash-$id",
        assetFileName = "$id.png",
        mimeType = "image/png",
        width = 1,
        height = 1,
        createdAt = 1,
        originChatId = owner,
        originChatName = "Name",
        originMessageId = id
    )

    private inner class Fixture(
        chats: List<Pair<String, String?>>,
        folders: MutableSet<String> = mutableSetOf(),
        records: MutableList<GeneratedImageCatalogRecord> = mutableListOf()
    ) {
        val navigation = FakeNavigation(chats.toMutableList(), folders)
        val catalog = FakeCatalog(records)
        val cleanup = FakeCleanup()
        val journal = ChatDeletionJournalStore(
            FakeSharedPreferences(),
            idFactory = { journalId }
        )

        fun coordinator(setting: Boolean) = ChatDeletionCoordinator(
            navigation,
            catalog,
            journal,
            cleanup,
            deleteImagesWithChat = { setting }
        )
    }

    private class FakeNavigation(
        val chats: MutableList<Pair<String, String?>>,
        val folders: MutableSet<String>
    ) : ChatDeletionNavigationGateway {
        override fun snapshot() = DeletionBackendResult.Success(
            DeletionNavigationSnapshot(chats.toList(), folders.toSet())
        )

        override fun removeMetadata(chatIds: Set<String>, folderId: String?): Boolean {
            if (!chats.map { it.first }.containsAll(chatIds)) return false
            if (folderId != null) {
                val members = chats.filter { it.second == folderId }.mapTo(HashSet()) { it.first }
                if (folderId !in folders || members != chatIds) return false
            }
            chats.removeAll { it.first in chatIds }
            if (folderId != null) folders.remove(folderId)
            return true
        }
    }

    private class FakeCatalog(
        val records: MutableList<GeneratedImageCatalogRecord>
    ) : ChatDeletionCatalogGateway {
        var tombstoneCalls = 0

        override fun ownedImages(chatIds: Set<String>) =
            DeletionBackendResult.Success(records.filter { it.originChatId in chatIds })

        override fun tombstoneUnlockedOwned(
            chatIds: Set<String>,
            candidateImageIds: Set<String>
        ): DeletionBackendResult<CatalogTombstoneResult> {
            tombstoneCalls++
            val current = records.filter { it.imageId in candidateImageIds && it.originChatId in chatIds }
            val removed = current.filterNot { it.locked }
            records.removeAll { candidate -> removed.any { it.imageId == candidate.imageId } }
            return DeletionBackendResult.Success(
                CatalogTombstoneResult(
                    true,
                    removed.mapTo(HashSet()) { it.imageId },
                    current.filter { it.locked }.mapTo(HashSet()) { it.imageId }
                )
            )
        }

        override fun deleteAssetIfUnreferenced(assetFileName: String) =
            DeletionBackendResult.Success(true)
    }

    private class FakeCleanup : ChatDeletionCleanupGateway {
        val cleaned = LinkedHashSet<String>()
        var succeeds = true
        override fun cleanupChat(chatId: String): Boolean {
            cleaned.add(chatId)
            return succeeds
        }
    }
}
