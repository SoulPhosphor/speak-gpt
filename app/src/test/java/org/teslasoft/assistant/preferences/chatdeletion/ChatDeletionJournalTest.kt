package org.teslasoft.assistant.preferences.chatdeletion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences

class ChatDeletionJournalTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val folder = "33333333-3333-4333-8333-333333333333"

    @Test fun journalUuidIsUniqueAndTargetIdentitiesNeverChange() {
        val ids = listOf(first, first, second).iterator()
        val store = ChatDeletionJournalStore(
            FakeSharedPreferences(),
            idFactory = { ids.next() },
            now = { 9L }
        )
        val one = (store.create(setOf("chat-a"), folder, ChatDeletionDecision.DELETE_ALL)
            as ChatDeletionJournalWrite.Success).entry
        val two = (store.create(setOf("chat-b"), null, ChatDeletionDecision.DELETE_CHAT_ONLY)
            as ChatDeletionJournalWrite.Success).entry
        assertNotEquals(one.journalId, two.journalId)

        val advanced = one.copy(
            stage = ChatDeletionJournalStage.CLEANUP_PENDING,
            candidateAssetFileNames = setOf("image.png")
        )
        assertTrue(store.update(advanced))
        assertFalse(store.update(advanced.copy(chatIds = setOf("renamed-chat-id"))))
        val restored = (store.read() as ChatDeletionJournalRead.Available).entries
            .first { it.journalId == one.journalId }
        assertEquals(setOf("chat-a"), restored.chatIds)
        assertEquals(folder, restored.folderId)
        assertEquals(first, restored.journalId)
    }

    @Test fun stageAndCandidateAssetsCannotRegress() {
        val store = ChatDeletionJournalStore(
            FakeSharedPreferences(),
            idFactory = { first }
        )
        val created = (store.create(setOf("chat"), null, ChatDeletionDecision.DELETE_ALL)
            as ChatDeletionJournalWrite.Success).entry
        val advanced = created.copy(
            stage = ChatDeletionJournalStage.CLEANUP_PENDING,
            candidateAssetFileNames = setOf("one.png", "two.png")
        )
        assertTrue(store.update(advanced))
        assertFalse(store.update(created))
        assertFalse(store.update(advanced.copy(candidateAssetFileNames = setOf("one.png"))))
    }

    @Test fun malformedJournalIsUnavailableAndNeverOverwrittenAsEmpty() {
        val prefs = FakeSharedPreferences().apply {
            edit().putString("entries", "not-json").commit()
        }
        val store = ChatDeletionJournalStore(prefs, idFactory = { first })
        assertTrue(store.read() is ChatDeletionJournalRead.Unavailable)
        assertTrue(
            store.create(setOf("chat"), null, ChatDeletionDecision.DELETE_CHAT_ONLY) is
                ChatDeletionJournalWrite.Failure
        )
        assertEquals("not-json", prefs.getString("entries", null))
    }
}
