/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.memory.ActiveMemoryAttribution
import org.teslasoft.assistant.preferences.memory.ActiveMemoryReference

/** Stage 6B.2: the memories a reply used are read as scanner clues. */
class ScanMemoryCluesTest {

    private fun message(id: String, bot: Boolean, attribution: List<ActiveMemoryReference>?) =
        HashMap<String, Any>().apply {
            put(MessageIdentity.KEY, id)
            put("message", "text $id")
            put("isBot", bot)
            attribution?.let { put(ActiveMemoryAttribution.MESSAGE_KEY, ActiveMemoryAttribution.encode(it)!!) }
        }

    private val used = listOf(
        ActiveMemoryReference(ActiveMemoryReference.SOURCE_MEMORY, "mem-1"),
        ActiveMemoryReference(ActiveMemoryReference.SOURCE_LOREBOOK, "lore-1"),
        ActiveMemoryReference(ActiveMemoryReference.SOURCE_MEMORY, "mem-2"),
        ActiveMemoryReference(ActiveMemoryReference.SOURCE_MEMORY, "mem-1")
    )

    @Test fun aReplyCarriesTheMemoriesItUsedButNotLorebookEntries() {
        val messages = ChatScanSource.fromMessages(listOf(
            message("u", bot = false, attribution = used),
            message("r", bot = true, attribution = used),
            message("old", bot = true, attribution = null)
        ))
        assertTrue("a user message has no record", messages[0].usedMemoryIds.isEmpty())
        assertEquals(listOf("mem-1", "mem-2"), messages[1].usedMemoryIds)
        assertTrue("replies saved before the record existed have none", messages[2].usedMemoryIds.isEmpty())
    }

    @Test fun theCluesTravelWithTheRowsAndTheirParts() {
        val messages = ChatScanSource.fromMessages(listOf(message("r", bot = true, attribution = used)))
        val rows = ScanSourceRows.build("chat", messages, 0, 2) { ScanSourceRows.GENERAL }
        assertEquals(listOf("mem-1", "mem-2"), rows.single().usedMemoryIds)
        assertEquals(listOf("mem-1", "mem-2"), rows.single().slice(0, 2).usedMemoryIds)
    }

    @Test fun theRecordDoesNotChangeTheMessageFingerprint() {
        val with = ChatScanSource.fromMessages(listOf(message("r", bot = true, attribution = used))).single()
        val without = ChatScanSource.fromMessages(listOf(message("r", bot = true, attribution = null))).single()
        assertEquals(without.fingerprint, with.fingerprint)
    }
}
