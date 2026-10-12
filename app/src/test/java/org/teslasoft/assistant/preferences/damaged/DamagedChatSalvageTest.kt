package org.teslasoft.assistant.preferences.damaged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DamagedChatSalvageTest {

    private fun msg(text: String, bot: Boolean = false, time: Long? = null, id: String? = null): HashMap<String, Any> =
        hashMapOf<String, Any>("message" to text, "isBot" to bot).apply {
            time?.let { put("messageTime", it.toString()) }
            id?.let { put("message_id", it) }
        }

    @Test
    fun aWholeValidListIsRecoveredCompletely() {
        val recovered = DamagedChatSalvage.salvage("""[{"message":"a","isBot":false},{"message":"b","isBot":true}]""")
        assertEquals(listOf("a", "b"), recovered.map { it["message"] })
    }

    @Test
    fun aTruncatedListKeepsEveryCompleteMessageBeforeTheBreak() {
        val recovered = DamagedChatSalvage.salvage("""[{"message":"a","isBot":false},{"message":"b","isBot":true},{"message":"c","isB""")
        assertEquals(listOf("a", "b"), recovered.map { it["message"] })
    }

    @Test
    fun invalidEntriesAreLeftOutAndNothingIsInvented() {
        val recovered = DamagedChatSalvage.salvage("""[{"message":"a","isBot":false},{"other":1},"text",{"message":"b","isBot":"true"}]""")
        assertEquals(listOf("a", "b"), recovered.map { it["message"] })
        assertTrue(DamagedChatSalvage.salvage("not a list").isEmpty())
        assertTrue(DamagedChatSalvage.salvage(null).isEmpty())
    }

    @Test
    fun mergingKeepsEveryReadableMessageAndAddsNoDuplicates() {
        val current = listOf(msg("new", time = 300, id = "n"), msg("kept", time = 200, id = "k"))
        val recovered = listOf(msg("old", time = 100, id = "o"), msg("kept again", time = 200, id = "k"))
        val merged = DamagedChatSalvage.merge(recovered, current)
        assertEquals(listOf("old", "kept", "new"), merged.map { it["message"] })
    }

    @Test
    fun withoutTimesRecoveredMessagesComeFirstInTheirOwnOrder() {
        val merged = DamagedChatSalvage.merge(listOf(msg("r1"), msg("r2")), listOf(msg("c1", bot = true)))
        assertEquals(listOf("r1", "r2", "c1"), merged.map { it["message"] })
    }

    @Test
    fun repeatedRecoveredMessagesAreBothKept() {
        val merged = DamagedChatSalvage.merge(listOf(msg("ok"), msg("ok")), emptyList())
        assertEquals(2, merged.size)
    }
}
