package org.teslasoft.assistant.preferences

import org.junit.Assert.*
import org.junit.Test

class MessageBookmarksTest {
    @Test fun successfulToggleCommitsOnce() {
        val message = hashMapOf<String, Any>("message" to "answer")
        var commits = 0
        assertTrue(MessageBookmarks.toggleAndSave(message) { changed ->
            assertTrue(changed)
            commits++
            true
        })
        assertEquals(1, commits)
        assertTrue(MessageBookmarks.isBookmarked(message))
    }

    @Test fun failedAdditionRestoresBothTheMessageAndProcessLocalHistory() {
        val message = hashMapOf<String, Any>("message" to "answer")
        var stored: Map<String, Any> = emptyMap()
        val changes = mutableListOf<Boolean>()
        assertFalse(MessageBookmarks.toggleAndSave(message) { changed ->
            // Android can install this process-local value before reporting disk failure.
            stored = HashMap(message)
            changes.add(changed)
            false
        })
        assertEquals(listOf(true, false), changes)
        assertFalse(MessageBookmarks.isBookmarked(message))
        assertFalse(MessageBookmarks.isBookmarked(stored))
        assertFalse(stored.containsKey(MessageBookmarks.KEY))
        assertEquals("answer", stored["message"])
    }

    @Test fun failedRemovalRestoresTheExactPreviousBookmarkValue() {
        for (original in listOf(true, "true")) {
            val message = hashMapOf<String, Any>("message" to "answer", MessageBookmarks.KEY to original)
            var stored: Map<String, Any> = emptyMap()
            var attempts = 0
            assertFalse(MessageBookmarks.toggleAndSave(message) {
                stored = HashMap(message)
                ++attempts > 1
            })
            assertEquals(2, attempts)
            assertEquals(original, message[MessageBookmarks.KEY])
            assertEquals(original, stored[MessageBookmarks.KEY])
        }
    }
}
