package org.teslasoft.assistant.conversation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.ChatPreferences

/**
 * A provisional conversation must never be able to lose the turns it holds.
 *
 * It owns its history from the first turn but only becomes a listed chat when
 * its first commit runs, so every way a turn can arrive has to reach that
 * commit, and nothing may delete a conversation that holds turns.
 */
class PendingConversationDurabilityTest {

    private val coordinator = source("conversation/NewConversationCoordinator.kt")
    private val activity = source("ui/activities/ChatActivity.kt")
    private val chatPreferences = source("preferences/ChatPreferences.kt")

    @Test
    fun everyRecordedTurnReachesTheFirstCommit() {
        // Cloud transcription and on-device Whisper auto-send record their turn
        // through putMessage rather than the typed-send path, so the commit has
        // to live where the turn is recorded, not only in parseMessage.
        val putMessage = activity.substringAfter("private fun putMessage(message: String, isBot: Boolean) {")
        assertTrue(putMessage.contains("commitPendingConversationIfNeeded()"))
        assertTrue(activity.contains("private fun commitPendingConversationIfNeeded()"))
        val funnel = activity.substringAfter("private fun commitPendingConversationIfNeeded() {")
            .substringBefore("\n    }")
        assertTrue(funnel.contains("if (!pendingConversation || chatId.isBlank() || messages.isEmpty()) return"))
        assertTrue(funnel.contains("commitPendingConversation("))
        assertTrue(funnel.contains("if (committed) finishPendingCommit()"))
    }

    @Test
    fun leavingAConversationThatHoldsTurnsCommitsItInsteadOfDeletingIt() {
        val abandon = coordinator.substringAfter("fun abandonPendingConversation(")
            .substringBefore("private fun allocateUniqueId()")
        // An unreadable store is never cleared.
        assertTrue(abandon.contains("if (!ChatStorageHealth.isAuthoritative(stored.state)) return false"))
        // Content is committed, not discarded.
        assertTrue(abandon.contains("if (stored.messages.isNotEmpty())"))
        assertTrue(
            abandon.indexOf("commitPendingConversation(") <
                abandon.indexOf("SecurePrefs.get(app, \"chat_\$chatId\").edit().clear()")
        )
    }

    @Test
    fun aRetainedStartupSessionWithTurnsIsCommittedBeforeItIsReleased() {
        val restore = coordinator.substringAfter("fun createOrRestoreStartupPendingConversation()")
            .substringBefore("fun createPendingConversation(")
        assertTrue(restore.contains("if (history.messages.isEmpty())"))
        assertTrue(
            restore.indexOf("commitPendingConversation(") <
                restore.indexOf("session.edit().clear().commit()")
        )
    }

    @Test
    fun provisionalTitlesAreReservedAndCollisionsAreRenumbered() {
        // Two provisional conversations can exist at once (the startup blank
        // chat and a drawer New Chat). Both were handed "_autoname_1" because
        // neither had a chat-list row, and the second commit was then refused
        // as a duplicate title, which stranded that conversation.
        assertTrue(coordinator.contains("private fun nextPlaceholderName(): String"))
        assertTrue(coordinator.contains("pendingConversationIds().mapNotNullTo(taken)"))
        assertTrue(coordinator.contains("indexPendingConversation(chatId, request.name)"))

        val commit = chatPreferences.substringAfter("fun commitPendingConversation(")
            .substringBefore("fun checkDuplicate(")
        assertTrue(commit.contains("AUTONAME_PLACEHOLDER.matches(chatName)"))
        assertTrue(commit.contains("nextAutonameNumber(listResult.chats)"))
        // A title the user chose is still not silently duplicated.
        assertTrue(commit.contains("else return PendingConversationCommitResult.CommitFailed"))
    }

    @Test
    fun placeholderTitlesAreRecognizedAndNumberedFromTheList() {
        assertTrue(ChatPreferences.AUTONAME_PLACEHOLDER.matches("_autoname_1"))
        assertTrue(ChatPreferences.AUTONAME_PLACEHOLDER.matches("_autoname_42"))
        assertFalse(ChatPreferences.AUTONAME_PLACEHOLDER.matches("_autoname_"))
        assertFalse(ChatPreferences.AUTONAME_PLACEHOLDER.matches("Trip plans"))
        assertFalse(ChatPreferences.AUTONAME_PLACEHOLDER.matches("my _autoname_1 chat"))

        val chats = listOf(
            mapOf("name" to "_autoname_1", "id" to "a"),
            mapOf("name" to "Trip plans", "id" to "b"),
            mapOf("name" to "_autoname_2", "id" to "c")
        )
        assertEquals("3", ChatPreferences.nextAutonameNumber(chats))
        assertEquals("1", ChatPreferences.nextAutonameNumber(emptyList()))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
