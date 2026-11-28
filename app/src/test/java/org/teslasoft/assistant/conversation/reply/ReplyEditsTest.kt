package org.teslasoft.assistant.conversation.reply

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReplyEditsTest {
    private fun row(text: String) = hashMapOf<String, Any>(
        MessageIdentity.KEY to "reply", "isBot" to true, "message" to text)
    private fun patch() = ReplyEdits(listOf(row("obsolete generated text").apply {
        put(ChatAdapter.KEY_MESSAGE_REASONING, "late reasoning")
    }), reasoningOnly = true)

    @Test fun lateReasoningPreservesTheEditedTextAndTheExistingReceipt() {
        val edited = row("edited text").apply {
            put(ChatAdapter.KEY_TOKEN_USAGE_RECORDS, "existing receipt")
            put("bookmark", true)
        }
        patch().mergeInto(mutableListOf(edited))
        assertEquals("edited text", edited["message"])
        assertEquals("existing receipt", edited[ChatAdapter.KEY_TOKEN_USAGE_RECORDS])
        assertEquals(true, edited["bookmark"])
        assertEquals("late reasoning", edited[ChatAdapter.KEY_MESSAGE_REASONING])
    }

    private fun versioned(canonical: String) = row(if (canonical == "0") "old answer" else "edited newest answer").apply {
        put(ChatAdapter.KEY_VARIANTS, ChatAdapter.variantsToJson(mutableListOf(
            hashMapOf("message" to "old answer", ChatAdapter.KEY_MESSAGE_REASONING to "old reasoning"),
            hashMapOf("message" to "edited newest answer"))))
        put(ChatAdapter.KEY_CANONICAL_VARIANT, canonical)
        put(ChatAdapter.KEY_DISPLAY_VARIANT, "0")
        put(ChatAdapter.KEY_MESSAGE_REASONING, "old reasoning")
    }

    @Test fun promotingAnOlderVersionDoesNotGiveItTheNewerVersionsReasoning() {
        val edited = versioned("0")
        patch().mergeInto(mutableListOf(edited))
        val versions = ChatAdapter.parseVariants(edited[ChatAdapter.KEY_VARIANTS]?.toString())
        assertEquals("old answer", edited["message"])
        assertEquals("old reasoning", edited[ChatAdapter.KEY_MESSAGE_REASONING])
        assertEquals("0", edited[ChatAdapter.KEY_CANONICAL_VARIANT])
        assertEquals("0", edited[ChatAdapter.KEY_DISPLAY_VARIANT])
        assertEquals("edited newest answer", versions.last()["message"])
        assertEquals("late reasoning", versions.last()[ChatAdapter.KEY_MESSAGE_REASONING])
    }

    @Test fun theCurrentGeneratedVersionReceivesReasoningWithoutRevertingItsEdit() {
        val edited = versioned("1")
        patch().mergeInto(mutableListOf(edited))
        val versions = ChatAdapter.parseVariants(edited[ChatAdapter.KEY_VARIANTS]?.toString())
        assertEquals("edited newest answer", edited["message"])
        assertEquals("late reasoning", edited[ChatAdapter.KEY_MESSAGE_REASONING])
        assertEquals("edited newest answer", versions.last()["message"])
        assertEquals("late reasoning", versions.last()[ChatAdapter.KEY_MESSAGE_REASONING])
        assertEquals("0", edited[ChatAdapter.KEY_DISPLAY_VARIANT])
    }
}
