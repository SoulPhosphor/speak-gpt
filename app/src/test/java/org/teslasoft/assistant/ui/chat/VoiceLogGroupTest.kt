/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogGroupTest {

    private val written = ArrayList<VoiceLogGroup.Line>()
    private val group = VoiceLogGroup { tag, level, message ->
        written.add(VoiceLogGroup.Line(tag, level, message))
    }

    @Test
    fun linesOfOneMomentAreWrittenAsOneEntry() {
        group.run {
            assertTrue(group.offer("VoiceLoop", "info", "readback finished; reopening mic (whisper-local)"))
            assertTrue(group.offer("VoiceLoop", "info", "listening turn started (auto re-arm)"))
            assertTrue(group.offer("MicRoute", "info", "requested Bluetooth SCO"))
        }
        assertEquals(1, written.size)
        assertEquals("VoiceLoop", written[0].tag)
        assertEquals(
            "readback finished; reopening mic (whisper-local)\n" +
                "  VoiceLoop: listening turn started (auto re-arm)\n" +
                "  MicRoute: requested Bluetooth SCO",
            written[0].message
        )
    }

    @Test
    fun entryTakesTheMostSevereLevel() {
        group.run {
            group.offer("VoiceDiag", "debug", "no-speech-timeout")
            group.offer("VoiceLoop", "warning", "stopped")
            group.offer("VoiceLoop", "info", "note")
        }
        assertEquals("warning", written.single().level)
        assertEquals("VoiceDiag", written.single().tag)
    }

    @Test
    fun nestedMomentsJoinTheOutermost() {
        group.run {
            group.offer("VoiceLoop", "info", "outer")
            group.run { group.offer("VoiceLoop", "info", "inner") }
            assertTrue(written.isEmpty())
        }
        assertEquals("outer\n  VoiceLoop: inner", written.single().message)
    }

    @Test
    fun multiLineMessagesStayIndentedInsideTheEntry() {
        group.run {
            group.offer("VoiceDiag", "debug", "end-of-turn\nVAD: speech=1200ms")
            group.offer("VoiceLoop", "info", "a\nb")
        }
        assertEquals(
            "end-of-turn\n  VAD: speech=1200ms\n  VoiceLoop: a\n    b",
            written.single().message
        )
        // No line inside the entry may start like a new entry header.
        assertFalse(written.single().message.lines().drop(1).any { it.startsWith("[") })
    }

    @Test
    fun linesOutsideAMomentAreNotCollected() {
        assertFalse(group.offer("VoiceLoop", "info", "alone"))
        group.run { }
        assertTrue(written.isEmpty())
    }

    @Test
    fun linesFromAnotherThreadAreNotCollected() {
        var offeredElsewhere = true
        group.run {
            val t = Thread { offeredElsewhere = group.offer("VoiceLoop", "info", "other thread") }
            t.start()
            t.join()
        }
        assertFalse(offeredElsewhere)
        assertTrue(written.isEmpty())
    }

    @Test
    fun entryIsWrittenEvenWhenTheMomentThrows() {
        try {
            group.run {
                group.offer("VoiceLoop", "warning", "before failure")
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) { }
        assertEquals("before failure", written.single().message)
    }
}
