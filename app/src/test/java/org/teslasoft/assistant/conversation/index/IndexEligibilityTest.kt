package org.teslasoft.assistant.conversation.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
import org.teslasoft.assistant.util.summarizer.SummarizerController

/** Memory development plan G.5a rules 1 and 7. */
class IndexEligibilityTest {

    private fun message(
        id: String = "m1",
        isBot: Boolean = true,
        text: String = "A finished reply.",
        hidden: Boolean = false,
        state: String? = null
    ) = IndexSourceMessage(id, isBot, text, hidden, state, 1L, null)

    @Test fun finishedVisibleMessagesWithAnIdAreEligible() {
        assertTrue(IndexEligibility.isEligible(message(isBot = false)))
        assertTrue(IndexEligibility.isEligible(message(state = null)))
        assertTrue(IndexEligibility.isEligible(message(state = "")))
        assertTrue(IndexEligibility.isEligible(message(state = MessageCompletionState.DONE)))
    }

    @Test fun unfinishedAndFailedRepliesAreNotEligible() {
        for (state in listOf(
            MessageCompletionState.STREAMING,
            MessageCompletionState.FAILED,
            MessageCompletionState.STOPPED,
            MessageCompletionState.INTERRUPTED,
            "some-future-state"
        )) {
            assertFalse(state, IndexEligibility.isEligible(message(state = state)))
        }
    }

    @Test fun hiddenMessagesAreNotEligible() {
        assertFalse(IndexEligibility.isEligible(message(hidden = true)))
        assertFalse(IndexEligibility.isEligible(message(isBot = false, hidden = true)))
    }

    @Test fun messagesWithoutAPermanentIdOrTextAreNotEligible() {
        assertFalse(IndexEligibility.isEligible(message(id = "")))
        assertFalse(IndexEligibility.isEligible(message(id = "  ")))
        assertFalse(IndexEligibility.isEligible(message(text = "")))
        assertFalse(IndexEligibility.isEligible(message(text = " \n ")))
    }

    @Test fun storedMessageGivesTheCanonicalVersionStateHiddenMarkAndScope() {
        val scope = ScanMessageScope(companionPersonaId = "persona-1", projectId = "project-2")
        val stored = hashMapOf<String, Any>(
            "message_id" to "m7",
            "isBot" to true,
            // Top-level fields mirror the canonical version; browsing changes displayVariant only.
            "message" to "Canonical reply",
            "variants" to "[{\"message\":\"Older reply\"},{\"message\":\"Canonical reply\"}]",
            "canonicalVariant" to "1",
            "displayVariant" to "0",
            MessageCompletionState.KEY_STATE to MessageCompletionState.DONE,
            ScanMessageScope.KEY to scope.encode()
        )
        val entry = SummarizerController.Entry(isBot = true, text = "Canonical reply", id = "m7", timeMillis = 42L)
        val source = IndexSourceMessage.from(stored, entry)!!
        assertEquals("m7", source.messageId)
        assertEquals("Canonical reply", source.modelText)
        assertEquals(MessageCompletionState.DONE, source.completionState)
        assertEquals(scope, source.scope)
        assertEquals(42L, source.timeMillis)
        assertNull("no name was saved on this reply", source.recordedSpeakerName)
        assertFalse(source.hiddenFromAi)
        assertTrue(IndexEligibility.isEligible(source))

        stored["hiddenFromAi"] = true
        stored.remove(ScanMessageScope.KEY)
        val hidden = IndexSourceMessage.from(stored, entry)!!
        assertTrue(hidden.hiddenFromAi)
        assertNull(hidden.scope)
        assertFalse(IndexEligibility.isEligible(hidden))
    }

    @Test fun theNameSavedOnTheMessageIsItsRecordedSpeakerName() {
        val reply = hashMapOf<String, Any>("message_id" to "a1", "isBot" to true, "message" to "Hi", "companionName" to " Ash ")
        assertEquals("Ash", IndexSourceMessage.from(reply, SummarizerController.Entry(true, "Hi", "a1"))!!.recordedSpeakerName)
        val user = hashMapOf<String, Any>(
            "message_id" to "u1", "isBot" to false, "message" to "Hi",
            "userName" to "Sam", "userNameSource" to "glamour:g-1"
        )
        assertEquals("Sam", IndexSourceMessage.from(user, SummarizerController.Entry(false, "Hi", "u1"))!!.recordedSpeakerName)
    }

    @Test fun theProgressCountsEachIndexedMessageOnceAndThoseWaitingForAnIdSeparately() {
        val messages = listOf(
            message(id = "u1", isBot = false, text = "Hello"),
            message(id = "a1"),
            message(id = "a1", text = "A repeated ID is indexed once."),
            message(id = "h1", hidden = true),
            message(id = "s1", state = MessageCompletionState.STREAMING),
            message(id = "", isBot = false, text = "Saved before IDs"),
            message(id = "", hidden = true),
            message(id = "e1", text = "  ")
        )
        // The same messages the updater writes: u1 and a1.
        assertEquals(2, IndexEligibility.indexedMessages(messages))
        // Only the visible message without an ID could be indexed once it has one.
        assertEquals(1, IndexEligibility.unidentified(messages))
    }

    @Test fun anEntryForAnotherMessageIsRefused() {
        val stored = hashMapOf<String, Any>("message_id" to "m1", "isBot" to false, "message" to "Hi")
        assertNull(IndexSourceMessage.from(stored, SummarizerController.Entry(false, "Hi", "m2")))
    }
}
