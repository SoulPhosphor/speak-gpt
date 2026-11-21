/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageNumbers

/** Permanent message numbers (owner decision, Stage 6A question 10). */
class MessageNumbersTest {

    private fun msgs(vararg numbers: Long?) = numbers.map { n ->
        HashMap<String, Any>().apply { n?.let { put(MessageNumbers.KEY, it.toDouble()) } }
    }

    private fun numbers(messages: List<Map<String, Any>>) = messages.map { MessageNumbers.numberOf(it) }

    @Test fun existingMessagesAreNumberedInOrderFromOne() {
        val chat = msgs(null, null, null)
        assertEquals(3L, MessageNumbers.ensure(chat, 0))
        assertEquals(listOf(1L, 2L, 3L), numbers(chat))
    }

    @Test fun deletedNumbersAreNeverReusedAndOthersAreNotRenumbered() {
        // Messages 5 to 10 were deleted; 10 was the highest ever assigned.
        val chat = msgs(1, 2, 3, 4, null)
        assertEquals(11L, MessageNumbers.ensure(chat, 10))
        assertEquals(listOf(1L, 2L, 3L, 4L, 11L), numbers(chat))
    }

    @Test fun anExistingHigherNumberKeepsItsNumber() {
        val chat = msgs(1, 11, null)
        assertEquals(12L, MessageNumbers.ensure(chat, 10))
        assertEquals(listOf(1L, 11L, 12L), numbers(chat))
    }

    @Test fun editingOrRegeneratingKeepsTheSlotsNumber() {
        val chat = msgs(1, 2)
        chat[1]["message"] = "a regenerated reply"
        assertFalse(MessageNumbers.needsNumbers(chat))
        assertEquals(2L, MessageNumbers.ensure(chat, 2))
        assertEquals(listOf(1L, 2L), numbers(chat))
    }

    @Test fun gapsAreNormal() {
        val chat = msgs(4, 6, 7)
        assertFalse(MessageNumbers.needsNumbers(chat))
        assertEquals(listOf(4L, 6L, 7L), numbers(chat))
    }

    @Test fun aConflictFromAMergeKeepsTheFirstAndRenumbersTheLater() {
        val chat = msgs(1, 2, 2)
        assertTrue(MessageNumbers.needsNumbers(chat))
        assertEquals(3L, MessageNumbers.ensure(chat, 2))
        assertEquals(listOf(1L, 2L, 3L), numbers(chat))
    }

    @Test fun storedNumbersReadBackFromGsonDoubles() {
        assertEquals(5L, MessageNumbers.numberOf(mapOf(MessageNumbers.KEY to 5.0)))
        assertEquals(5L, MessageNumbers.numberOf(mapOf(MessageNumbers.KEY to "5")))
        assertEquals(null, MessageNumbers.numberOf(mapOf(MessageNumbers.KEY to 0)))
    }
}
