package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Memory provenance rules (development plan M.3). */
class MemorySourceRulesTest {

    @Test
    fun theScannerRecordsOnlyTheConversation() {
        val s = MemorySourceRules.conversationOnly("src-1", "m-1", "chat-1", "2026-11-16T12:00:00Z")
        assertEquals(MemorySource.KIND_CONVERSATION, s.kind)
        assertEquals("chat-1", s.chatId)
        assertNull(s.messageId)
        assertNull(s.speaker)
        assertNull(s.speakerName)
        assertNull(s.glamourId)
        assertNull(s.statedAt)
        assertEquals(MemorySource.ATTACHED_MEMORY_ASSISTANT, s.attachedBy)
    }

    @Test
    fun userEntryIsDatedAtCreationWithNoConversation() {
        val s = MemorySourceRules.userEntry("src-u", "m-1", "2026-11-16T09:00:00Z")
        assertEquals(MemorySource.KIND_USER_ENTRY, s.kind)
        assertEquals("2026-11-16T09:00:00Z", s.statedAt)
        assertNull(s.chatId)
        assertNull(s.speaker)
    }

    @Test
    fun sourcesShowOldestAttachmentFirst() {
        val late = MemorySourceRules.conversationOnly("b", "m", "c2", "2026-11-16T12:00:00Z")
        val early = MemorySourceRules.conversationOnly("a", "m", "c1", "2026-11-15T12:00:00Z")
        assertEquals(listOf(early, late), MemorySourceRules.displayOrder(listOf(late, early)))
    }
}
