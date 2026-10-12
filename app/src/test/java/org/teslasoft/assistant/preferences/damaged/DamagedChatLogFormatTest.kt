package org.teslasoft.assistant.preferences.damaged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DamagedChatLogFormatTest {

    @Test
    fun detectionsRoundTripWithUnknownDetailsLeftUnset() {
        val detections = listOf(
            DamagedChatLog.Detection(
                DamagedChatOperation.RENAMING_USERNAMES,
                1_700_000_000_000L,
                listOf(
                    DamagedChatLog.DamagedChat("a", "Trip Plans", 1_690_000_000_000L, null, null, null),
                    DamagedChatLog.DamagedChat("b", null, null, null, null, null)
                )
            ),
            DamagedChatLog.Detection(
                DamagedChatOperation.CHAT_SEARCH,
                1_700_000_100_000L,
                listOf(DamagedChatLog.DamagedChat("c", "Recipes", null, "Robin", "Sol", "provider/model"))
            )
        )
        val decoded = DamagedChatLog.decode(DamagedChatLog.encode(detections))
        assertEquals(detections, decoded)
        assertNull(decoded[0].chats[1].title)
        assertNull(decoded[0].chats[1].startedAt)
    }

    @Test
    fun unreadableOrUnknownEntriesAreNotInvented() {
        assertTrue(DamagedChatLog.decode("not json").isEmpty())
        val unknownOperation = """[{"operation":"SOMETHING_ELSE","detected_at":1,"chats":[{"chat_id":"x"}]}]"""
        assertTrue(DamagedChatLog.decode(unknownOperation).isEmpty())
        assertNull(DamagedChatLog.decodeOrNull(unknownOperation))
        assertNull(DamagedChatLog.decodeOrNull("not json"))
    }
}
