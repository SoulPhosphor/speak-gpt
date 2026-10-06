package org.teslasoft.assistant.ui.chat

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.ui.chat.RegenerationRecovery.Outcome

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RegenerationRecoveryTest {
    private fun user(id: String) = hashMapOf<String, Any>(
        "isBot" to false, "message" to "question", MessageIdentity.KEY to id
    )

    private fun reply(id: String, text: String, time: String) = hashMapOf<String, Any>(
        "isBot" to true, "message" to text, MessageIdentity.KEY to id,
        ChatAdapter.KEY_MESSAGE_TIME to time
    )

    private fun pendingOver(vararg history: HashMap<String, Any>): RegenerationRecovery.Pending =
        RegenerationRecovery.pendingFor(history.toList())!!

    @Test fun `a regeneration that produced no reply puts the original back`() {
        val original = reply("r1", "first answer", "100")
        val pending = pendingOver(user("u1"), original)

        val outcome = RegenerationRecovery.resolve(listOf(user("u1")), pending)

        assertEquals("first answer", (outcome as Outcome.Restore).original["message"])
        assertEquals("r1", MessageIdentity.idOf(outcome.original))
    }

    @Test fun `a new reply left unversioned becomes the newest version`() {
        val original = reply("r1", "first answer", "100")
        val pending = pendingOver(user("u1"), original)
        val partial = reply("r1", "second, cut off", "200")

        val outcome = RegenerationRecovery.resolve(listOf(user("u1"), partial), pending) as Outcome.Fold
        RegenerationRecovery.foldInto(outcome.reply, RegenerationRecovery.historyOf(pending.original), "r1")

        val versions = ChatAdapter.parseVariants(partial[ChatAdapter.KEY_VARIANTS]?.toString())
        assertEquals(listOf("first answer", "second, cut off"), versions.map { it["message"] })
        assertEquals("1", partial[ChatAdapter.KEY_CANONICAL_VARIANT])
        assertEquals("r1", MessageIdentity.idOf(partial))
    }

    @Test fun `earlier versions are kept when the original already had several`() {
        val original = reply("r1", "v2", "100")
        RegenerationRecovery.foldInto(original, mutableListOf(ChatAdapter.snapshotVariant(reply("r1", "v1", "50"))), "r1")

        assertEquals(2, RegenerationRecovery.historyOf(original).size)
    }

    @Test fun `recovery leaves settled or unrelated history alone`() {
        val original = reply("r1", "first answer", "100")
        val pending = pendingOver(user("u1"), original)

        // Never removed.
        assertTrue(RegenerationRecovery.resolve(listOf(user("u1"), reply("r1", "first answer", "100")), pending) is Outcome.Unchanged)
        // Already folded.
        val folded = reply("r1", "second", "200").also {
            RegenerationRecovery.foldInto(it, RegenerationRecovery.historyOf(original), "r1")
        }
        assertTrue(RegenerationRecovery.resolve(listOf(user("u1"), folded), pending) is Outcome.Unchanged)
        // The conversation moved on to a later message.
        assertTrue(RegenerationRecovery.resolve(listOf(user("u1"), user("u2")), pending) is Outcome.Unchanged)
        assertTrue(RegenerationRecovery.resolve(listOf(user("u1"), user("u2"), reply("r2", "x", "300")), pending) is Outcome.Unchanged)
    }

    @Test fun `a saved regeneration survives encoding`() {
        val pending = pendingOver(user("u1"), reply("r1", "first answer", "100"))

        val decoded = RegenerationRecovery.decode(RegenerationRecovery.encode(pending))!!

        assertEquals("u1", decoded.precedingMessageId)
        assertEquals("first answer", decoded.original["message"])
        assertEquals(true, decoded.original["isBot"])
        assertNull(RegenerationRecovery.decode(""))
        assertNull(RegenerationRecovery.decode("{broken"))
    }
}
