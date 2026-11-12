package org.teslasoft.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState

class CompanionAvatarRecoveryTest {
    @Test fun `stopped reply then failed retry retain X until a completed reply`() {
        val recovery = CompanionAvatarRecovery()
        val messages = mutableListOf<Map<String, Any?>>()
        fun addReply(state: String): Boolean {
            messages += mapOf("isBot" to true, MessageCompletionState.KEY_STATE to state)
            return MessageCompletionState.chatShowsErrorAvatar(messages)
        }
        assertTrue(addReply(MessageCompletionState.STOPPED))
        recovery.onErrorAvatarBound()
        assertFalse(recovery.takeRecovery(true))
        assertTrue(addReply(MessageCompletionState.FAILED))
        recovery.onErrorAvatarBound()
        assertFalse(recovery.takeRecovery(true))
        assertFalse(addReply(MessageCompletionState.DONE))
        assertTrue(recovery.takeRecovery(false))
        assertFalse(recovery.takeRecovery(false))
        // A later failure does not reinstate the X after a successful reply.
        assertFalse(addReply(MessageCompletionState.FAILED))
        assertFalse(recovery.takeRecovery(false))
    }

    @Test fun `normal completed replies do not force full portrait refreshes`() {
        assertFalse(CompanionAvatarRecovery().takeRecovery(false))
    }

    @Test fun `several bound X portraits require one shared refresh`() {
        val recovery = CompanionAvatarRecovery()
        repeat(4) { recovery.onErrorAvatarBound() }
        assertTrue(recovery.takeRecovery(false))
        assertFalse(recovery.takeRecovery(false))
    }
}
