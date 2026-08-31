package org.teslasoft.assistant.preferences.chatsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTargetResolverTest {
    @Test fun stableIdWinsAndStaleTargetFallsBack() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val rows = listOf(hashMapOf<String, Any>("message" to "Text", "isBot" to false, "message_id" to id))
        assertEquals(0, SearchTargetResolver.resolve(rows, id, null, null, null))
        assertNull(SearchTargetResolver.resolve(rows, "missing", null, null, null))
    }

    @Test fun legacyOrdinalIsVerifiedAndShiftedUniqueFingerprintCanBeFound() {
        val rows = listOf(
            hashMapOf<String, Any>("message" to "Inserted", "isBot" to false),
            hashMapOf<String, Any>("message" to "Target", "isBot" to true)
        )
        val fingerprint = SearchableMessageProjection.fingerprint("assistant\u001fTarget")
        assertEquals(1, SearchTargetResolver.resolve(rows, null, 0, "assistant", fingerprint))
        val duplicate = rows + hashMapOf<String, Any>("message" to "Target", "isBot" to true)
        assertNull(SearchTargetResolver.resolve(duplicate, null, 0, "assistant", fingerprint))
    }
}

