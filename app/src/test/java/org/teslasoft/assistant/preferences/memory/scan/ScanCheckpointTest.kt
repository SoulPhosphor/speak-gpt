/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCheckpointTest {

    private fun msg(id: String, text: String, eligible: Boolean = true, assistant: Boolean = false) =
        ScanMessage(
            id = id, number = id.drop(1).toLongOrNull(), role = if (assistant) "assistant" else "user",
            text = text, eligible = eligible, scope = null, timeMillis = null, model = null,
            fingerprint = ChatScanSource.fingerprint(assistant, text, eligible)
        )

    private val chat = listOf(msg("m1", "first"), msg("m2", "a long second message"), msg("m3", "third"))

    @Test fun aSavedCheckpointResumesAtTheExactUnreadPosition() {
        // A section ended part way through m2 (offset 7), then the process died.
        val saved = ScanCheckpoint().advancedTo(chat, 1, 7).encode()
        val restored = ScanCheckpoint.decode(saved)
        assertTrue(restored.stillValid(chat))
        assertEquals(1 to 7, restored.nextUnread(chat))
        val rows = ScanSourceRows.build("c", chat, 1, 7) { ScanSourceRows.GENERAL }
        assertEquals("second message", rows.first().text)
        assertEquals(7, rows.first().start)
        assertEquals(1, restored.sectionsDone)
    }

    @Test fun aFinishedRangeHasNothingUnread() {
        val done = ScanCheckpoint().advancedTo(chat, 2, chat[2].text.length)
        assertNull(done.nextUnread(chat))
    }

    @Test fun editingHidingOrDeletingAReadMessageInvalidatesTheCheckpoint() {
        val checkpoint = ScanCheckpoint().advancedTo(chat, 1, chat[1].text.length)
        assertTrue(checkpoint.stillValid(chat))
        assertFalse(checkpoint.stillValid(listOf(msg("m1", "first, edited"), chat[1], chat[2])))
        assertFalse(checkpoint.stillValid(listOf(msg("m1", "first", eligible = false), chat[1], chat[2])))
        assertFalse(checkpoint.stillValid(listOf(chat[1], chat[2])))
    }

    @Test fun aChangeToAnUnreadMessageDoesNotInvalidate() {
        val checkpoint = ScanCheckpoint().advancedTo(chat, 0, chat[0].text.length)
        assertTrue(checkpoint.stillValid(listOf(chat[0], chat[1], msg("m3", "third, edited"))))
    }

    @Test fun aHiddenMessageBeforeThePositionIsRecordedSoShowingItIsNoticed() {
        val withHidden = listOf(chat[0], msg("m2", "hidden one", eligible = false), chat[2])
        val checkpoint = ScanCheckpoint().advancedTo(withHidden, 2, chat[2].text.length)
        assertFalse(checkpoint.stillValid(listOf(chat[0], msg("m2", "hidden one"), chat[2])))
    }

    @Test fun anEmptyOrDamagedCheckpointStartsFromTheBeginning() {
        assertTrue(ScanCheckpoint.decode(null).isEmpty)
        assertTrue(ScanCheckpoint.decode("not json").isEmpty)
        assertEquals(0 to 0, ScanCheckpoint().nextUnread(chat))
    }
}
