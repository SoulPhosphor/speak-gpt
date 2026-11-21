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
import org.teslasoft.assistant.preferences.memory.ChatScanCoverage
import org.teslasoft.assistant.preferences.memory.ScanChatProgress

class ScanChatStatusTest {

    private fun msg(id: String, eligible: Boolean = true) = ScanMessage(
        id = id, number = null, role = "user", text = id, eligible = eligible, scope = null,
        timeMillis = null, model = null, fingerprint = id
    )

    private val chat = listOf(msg("m1"), msg("m2"), msg("m3"), msg("m4"))
    private fun at(index: Int) = ScanChatProgress("c", ScanPosition.anchors(chat.map { it.id }, index))

    @Test fun newMessagesAfterTheBookmarkAreUnscanned() {
        assertTrue(ScanChatStatus.hasUnscanned(at(1), chat))
        assertFalse(ScanChatStatus.hasUnscanned(at(3), chat))
        assertEquals("partial", ScanChatStatus.reviewState(at(1), chat))
        assertEquals("processed", ScanChatStatus.reviewState(at(3), chat))
        assertEquals("pending", ScanChatStatus.reviewState(null, chat))
    }

    @Test fun aPendingCompleteRescanCountsNothingAsScanned() {
        val rescan = at(3).copy(needsFullRescan = true)
        assertEquals(-1, ScanChatStatus.bookmarkIndex(rescan, chat))
        assertEquals("pending", ScanChatStatus.reviewState(rescan, chat))
    }

    @Test fun aClaimedChatIsProcessing() {
        assertEquals("processing", ScanChatStatus.reviewState(at(1).copy(claimRunId = "run"), chat))
    }

    @Test fun aChatWithNothingToReadHasNoStatus() {
        assertNull(ScanChatStatus.reviewState(null, listOf(msg("m1", eligible = false))))
    }

    @Test fun coverageUsesMessageNumbersFromOne() {
        assertEquals(ChatScanCoverage.Status.Unscanned, ScanChatStatus.coverage(null, chat))
        assertEquals(ChatScanCoverage.Status.Complete, ScanChatStatus.coverage(at(3), chat))
        assertEquals(ChatScanCoverage.Status.Range(1, 2), ScanChatStatus.coverage(at(1), chat))
    }

    @Test fun coverageShowsPermanentNumbersAfterADeletion() {
        // Message 1 was deleted; the chat was scanned through message 10.
        val numbered = (2..12).map { n ->
            ScanMessage("m$n", n.toLong(), "user", "t$n", true, null, null, null, "t$n")
        }
        val progress = ScanChatProgress("c", ScanPosition.anchors(numbered.map { it.id }, 8))
        assertEquals(ChatScanCoverage.Status.Range(2, 10), ScanChatStatus.coverage(progress, numbered))
    }

    @Test fun aLostBookmarkReadsMoreRatherThanLess() {
        val lost = ScanChatProgress("c", listOf("gone"))
        assertEquals(-1, ScanChatStatus.bookmarkIndex(lost, chat))
    }
}
