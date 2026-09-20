package org.teslasoft.assistant.preferences.chatsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchableMessageProjectionTest {
    @Test fun indexesOnlyVisiblePersistedUserAndAssistantText() {
        val rows = listOf(
            hashMapOf<String, Any>("message" to "Visible user", "isBot" to false, "includes" to "secret body", "message_id" to "not-a-uuid"),
            hashMapOf<String, Any>("message" to "Partial", "isBot" to true, "state" to "streaming"),
            hashMapOf<String, Any>("message" to "~file:/private/image.png", "isBot" to true),
            hashMapOf<String, Any>("message" to "Visible assistant", "isBot" to true, "state" to "done")
        )
        val projected = SearchableMessageProjection.project(rows)
        assertEquals(listOf("Visible user", "Visible assistant"), projected.map { it.text })
        assertNull(projected.first().messageId)
    }

    @Test fun duplicateStableIdsFallBackForEveryAmbiguousRow() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val rows = listOf(
            hashMapOf<String, Any>("message" to "One", "isBot" to false, "message_id" to id),
            hashMapOf<String, Any>("message" to "Two", "isBot" to true, "message_id" to id)
        )
        val projected = SearchableMessageProjection.project(rows)
        assertNull(projected[0].messageId)
        assertNull(projected[1].messageId)
    }
}
