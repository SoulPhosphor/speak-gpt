/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageNumbers
import org.teslasoft.assistant.preferences.MessageVisibility

/** The scanner reads the chat itself: the latest wording of each UUID, only
 *  what the AI is sent, in the chat's own order (Stage 6A). */
class ChatScanSourceTest {

    private fun message(id: String?, text: String, bot: Boolean = false, extra: Map<String, Any> = emptyMap()) =
        HashMap<String, Any>().apply {
            id?.let { put(MessageIdentity.KEY, it) }
            put("message", text)
            put("isBot", bot)
            putAll(extra)
        }

    @Test fun hiddenUnfinishedAndBlankMessagesAreNotRead() {
        val messages = ChatScanSource.fromMessages(listOf(
            message("a", "visible"),
            message("b", "hidden", extra = mapOf(MessageVisibility.KEY to true)),
            message("c", "cut off", bot = true, extra = mapOf(MessageCompletionState.KEY_STATE to "failed")),
            message("d", "   "),
            message("e", "finished reply", bot = true)
        ))
        assertEquals(listOf(true, false, false, false, true), messages.map { it.eligible })
    }

    @Test fun messagesWithoutAUuidAreNeverMatchedByText() {
        val messages = ChatScanSource.fromMessages(listOf(message(null, "no id"), message("x", "has id")))
        assertEquals(listOf("x"), messages.map { it.id })
    }

    @Test fun scopeNumberAndOrderAreRead() {
        val scope = ScanMessageScope(companionPersonaId = "persona-1", projectId = "p")
        val messages = ChatScanSource.fromMessages(listOf(
            message("a", "first", extra = mapOf(MessageNumbers.KEY to 4.0, ScanMessageScope.KEY to scope.encode())),
            message("b", "second", extra = mapOf(MessageNumbers.KEY to 6.0))
        ))
        assertEquals(listOf(4L, 6L), messages.map { it.number })
        assertEquals(scope, messages[0].scope)
        assertNull(messages[1].scope)
    }

    @Test fun theFingerprintFollowsWhatTheScannerWouldRead() {
        val base = ChatScanSource.fingerprint(false, "text", true)
        assertEquals(base, ChatScanSource.fingerprint(false, "text", true))
        assertNotEquals(base, ChatScanSource.fingerprint(false, "text!", true))
        assertNotEquals(base, ChatScanSource.fingerprint(false, "text", false))
        assertNotEquals(base, ChatScanSource.fingerprint(true, "text", true))
    }

    @Test fun aVersionChangeThatOnlyMovesTheScopeChangesTheFingerprint() {
        val companionA = ScanMessageScope(companionPersonaId = "a").encode()
        val companionB = ScanMessageScope(companionPersonaId = "b").encode()
        val first = ChatScanSource.fromMessages(listOf(
            message("r", "same words", bot = true, extra = mapOf(ScanMessageScope.KEY to companionA))
        )).single()
        val second = ChatScanSource.fromMessages(listOf(
            message("r", "same words", bot = true, extra = mapOf(ScanMessageScope.KEY to companionB))
        )).single()
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test fun onlyAssistantRepliesThatHaveNotFinishedAreUnfinished() {
        val messages = ChatScanSource.fromMessages(listOf(
            message("u", "question"),
            message("s", "partial", bot = true, extra = mapOf(MessageCompletionState.KEY_STATE to MessageCompletionState.STREAMING)),
            message("d", "done", bot = true, extra = mapOf(MessageCompletionState.KEY_STATE to MessageCompletionState.DONE)),
            message("l", "legacy", bot = true)
        ))
        assertEquals(listOf(false, true, false, false), messages.map { it.unfinishedReply })
    }

    @Test fun regeneratingAReplyKeepsItsIdentityAndChangesOnlyItsFingerprint() {
        val before = ChatScanSource.fromMessages(listOf(message("r", "old reply", bot = true))).single()
        val after = ChatScanSource.fromMessages(listOf(message("r", "new reply", bot = true))).single()
        assertEquals(before.id, after.id)
        assertNotEquals(before.fingerprint, after.fingerprint)
    }

    @Test fun unknownScopeReadsAsGeneral() {
        val rows = ScanSourceRows.build(
            "c", ChatScanSource.fromMessages(listOf(message("a", "hello"))), 0, 0
        ) { scope -> if (scope == null) ScanSourceRows.GENERAL else ScanScopeResolution.Excluded }
        assertEquals(1, rows.size)
        assertNull(rows.single().companionId)
        assertNull(rows.single().projectId)
    }

    @Test fun aCompanionWithParticipationNoneIsNotRead() {
        val scope = ScanMessageScope(companionPersonaId = "silent")
        val rows = ScanSourceRows.build(
            "c",
            ChatScanSource.fromMessages(listOf(
                message("a", "kept"),
                message("b", "excluded", extra = mapOf(ScanMessageScope.KEY to scope.encode()))
            )),
            0, 0
        ) { s -> if (s?.companionPersonaId == "silent") ScanScopeResolution.Excluded else ScanSourceRows.GENERAL }
        assertEquals(listOf("a"), rows.map { it.messageId })
    }

    @Test fun scopeEncodingRoundTripsAndAbsentMeansUnknown() {
        val scope = ScanMessageScope("p", null, "w", "c", "r", "u", "proj")
        assertEquals(scope, ScanMessageScope.decode(scope.encode()))
        assertNull(ScanMessageScope.decode(null))
        assertFalse(ScanMessageScope.has(HashMap<String, Any>()))
        val m = HashMap<String, Any>()
        assertTrue(ScanMessageScope.write(m, scope))
        assertFalse(ScanMessageScope.write(m, scope))
    }
}
