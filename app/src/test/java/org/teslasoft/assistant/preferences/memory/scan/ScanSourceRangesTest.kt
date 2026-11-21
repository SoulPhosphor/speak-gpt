/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanSourceRangesTest {

    private val raw = ScanSourceRanges.encode(listOf(
        ScanSourceRanges.Range("old", emptyList(), listOf("m3", "m2")),
        ScanSourceRanges.Range("other", listOf("x1"), listOf("x4"))
    ))

    @Test fun aRenamedChatKeepsItsRangesForRerun() {
        val moved = ScanSourceRanges.decode(ScanSourceRanges.repoint(raw, "old", "new"))
        assertEquals(listOf("new", "other"), moved.map { it.chatId })
        assertEquals(listOf("m3", "m2"), moved[0].endAnchors)
        assertEquals(ScanSourceRanges.decode(raw)[1], moved[1])
    }

    @Test fun historyThatNeverNamedTheChatIsLeftAlone() {
        assertNull(ScanSourceRanges.repoint(raw, "absent", "new"))
        assertNull(ScanSourceRanges.repoint(null, "old", "new"))
    }
}
