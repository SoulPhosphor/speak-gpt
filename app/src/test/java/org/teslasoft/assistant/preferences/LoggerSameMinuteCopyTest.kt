/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LoggerSameMinuteCopyTest {

    private val a = "[2026-11-19 2:04 AM] [VoiceLoop] [INFO] readback finished"
    private val b = "[2026-11-19 2:04 AM] [MicRoute] [INFO] requested Bluetooth SCO\n  detail"
    private val c = "[2026-11-19 2:05 AM] [VoiceLoop] [INFO] next minute"

    @Test
    fun everyEntryCopiesItsWholeMinuteInDisplayedOrder() {
        val copies = Logger.sameMinuteCopyText(listOf(a, b, c))
        assertEquals("$a\n\n$b", copies[0])
        assertEquals("$a\n\n$b", copies[1])
        assertEquals(c, copies[2])
    }

    @Test
    fun newestFirstOrderIsKept() {
        val copies = Logger.sameMinuteCopyText(listOf(c, b, a))
        assertEquals(c, copies[0])
        assertEquals("$b\n\n$a", copies[1])
    }

    @Test
    fun busyMinuteSharesOneCopyAcrossEveryButton() {
        val entries = (0 until 1000).map {
            "[2026-11-19 2:04 AM] [VoiceLoop] [INFO] $it ${"x".repeat(1024)}"
        }
        val copies = Logger.sameMinuteCopyText(entries)
        assertEquals(entries.joinToString("\n\n"), copies.first())
        copies.forEach { assertSame("Each button must share the minute's text", copies.first(), it) }
    }

    @Test
    fun sameClockTimeOnAnotherDayIsAnotherMinute() {
        val otherDay = "[2026-11-18 2:04 AM] [VoiceLoop] [INFO] yesterday"
        val copies = Logger.sameMinuteCopyText(listOf(a, otherDay))
        assertEquals(a, copies[0])
        assertEquals(otherDay, copies[1])
    }

    @Test
    fun olderEntriesWithSecondsShareTheirMinute() {
        val withSeconds = "[2026-07-19 4:15:31 PM] [VoiceLoop] [INFO] old"
        val plain = "[2026-07-19 4:15 PM] [VoiceLoop] [INFO] new"
        assertEquals(Logger.entryMinuteKey(plain), Logger.entryMinuteKey(withSeconds))
    }

    @Test
    fun providerFailureShapedEntriesGroupToo() {
        val provider = "[2026-11-19 2:04 AM]\nProvider: example"
        assertEquals("$a\n\n$provider", Logger.sameMinuteCopyText(listOf(a, provider))[1])
    }

    @Test
    fun textWithoutAHeaderIsCopiedOnItsOwn() {
        val loose = "legacy text without a header"
        assertNull(Logger.entryMinuteKey(loose))
        assertEquals(listOf(loose, a), Logger.sameMinuteCopyText(listOf(loose, a)))
    }
}
