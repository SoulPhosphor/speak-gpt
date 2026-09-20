package org.teslasoft.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationModeMigrationTest {
    @Test
    fun missingAndUnknownLegacyMetadataDefaultToChat() {
        assertEquals(ConversationMode.CHAT, ConversationMode.fromStored(null))
        assertEquals(ConversationMode.CHAT, ConversationMode.fromStored(""))
        assertEquals(ConversationMode.CHAT, ConversationMode.fromStored("future-value"))
    }

    @Test
    fun savedPlaygroundMetadataReopensAsPlayground() {
        assertEquals(
            ConversationMode.PLAYGROUND,
            ConversationMode.fromStored(ConversationMode.PLAYGROUND.storedValue)
        )
    }
}
