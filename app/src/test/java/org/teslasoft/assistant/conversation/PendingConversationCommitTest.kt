package org.teslasoft.assistant.conversation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transaction-order and idempotency contract for the encrypted first commit. */
class PendingConversationCommitTest {
    private val source: String = run {
        val path = "src/main/java/org/teslasoft/assistant/preferences/ChatPreferences.kt"
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing ChatPreferences.kt")
    }

    @Test
    fun firstCommitJournalsThenWritesPayloadModeAndVisibleRow() {
        val body = source.substringAfter("fun commitPendingConversation(")
            .substringBefore("fun checkDuplicate(")
        assertOrdered(
            body,
            "pending_conversation_journal",
            "putString(\"chat\", Gson().toJson(messages)).commit()",
            "ConversationMode.MODE_KEY",
            "updated.add(row)",
            "putString(\"data\", Gson().toJson(updated)).commit()",
            "journal.edit().remove(chatId).commit()"
        )
    }

    @Test
    fun stableIdMakesRetryIdempotent() {
        val body = source.substringAfter("fun commitPendingConversation(")
            .substringBefore("fun checkDuplicate(")
        assertTrue(body.contains("firstOrNull { storedChatId(it) == chatId }"))
        assertTrue(body.contains("PendingConversationCommitResult.AlreadyCommitted"))
        assertTrue(body.contains("putBoolean(ConversationMode.PENDING_KEY, true)"))
    }

    private fun assertOrdered(text: String, vararg markers: String) {
        var prior = -1
        markers.forEach { marker ->
            val next = text.indexOf(marker, prior + 1)
            assertTrue("Missing or out-of-order marker: $marker", next > prior)
            prior = next
        }
    }
}
