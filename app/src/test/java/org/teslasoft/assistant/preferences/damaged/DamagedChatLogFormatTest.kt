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

    @Test
    fun causesRoundTripAndOlderOrUnknownCausesReadAsNotRecorded() {
        val withCauses = listOf(
            DamagedChatLog.Detection(
                DamagedChatOperation.OPENING_A_CHAT,
                1L,
                DamagedChatCause.entries.mapIndexed { i, cause ->
                    DamagedChatLog.DamagedChat("c$i", null, null, null, null, null, cause = cause)
                }
            )
        )
        assertEquals(withCauses, DamagedChatLog.decode(DamagedChatLog.encode(withCauses)))

        val older = """[{"operation":"CHAT_LIST","detected_at":1,"chats":[{"chat_id":"x"}]}]"""
        assertNull(DamagedChatLog.decode(older).single().chats.single().cause)
        val unknown = """[{"operation":"CHAT_LIST","detected_at":1,"chats":[{"chat_id":"x","cause":"NEW_KIND"}]}]"""
        assertNull(DamagedChatLog.decode(unknown).single().chats.single().cause)
    }
}
