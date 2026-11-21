/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTextSplitterTest {

    private val chars: (String) -> Int = { it.length }

    private fun pieces(text: String, max: Int) =
        ScanTextSplitter.split(text, max, chars).map { text.substring(it.start, it.end) }

    @Test fun aMessageThatFitsStaysWhole() {
        assertEquals(listOf("short"), pieces("short", 10))
    }

    @Test fun fragmentsCoverTheWholeMessageExactly() {
        val text = "one two three\nfour five\n\nsix seven eight nine ten " + "x".repeat(50)
        for (max in listOf(1, 3, 7, 12, 20)) {
            val ranges = ScanTextSplitter.split(text, max, chars)
            assertEquals(text, ranges.joinToString("") { text.substring(it.start, it.end) })
            var at = 0
            for (r in ranges) {
                assertEquals(at, r.start)
                assertTrue(r.end > r.start)
                at = r.end
            }
            assertEquals(text.length, at)
        }
    }

    @Test fun paragraphBreaksComeBeforeLineBreaksAndSpaces() {
        assertEquals(listOf("aaaa bbb\n\n", "cccc"), pieces("aaaa bbb\n\ncccc", 11))
        assertEquals(listOf("aaaa bbb\n", "cccc dd"), pieces("aaaa bbb\ncccc dd", 10))
        assertEquals(listOf("aaaa ", "bbbb"), pieces("aaaa bbbb", 6))
    }

    @Test fun periodsDoNotDecideBoundaries() {
        // Dictated text: no punctuation at all is still split, between words.
        val text = "so then I went to the store and they had none left so I came home"
        val parts = pieces(text, 20)
        assertTrue(parts.size > 1)
        parts.dropLast(1).forEach { assertTrue(it.endsWith(" ")) }
    }

    @Test fun aWordLongerThanTheLimitIsSplitBetweenWholeCharacters() {
        val emoji = "😀" // one character, two UTF-16 units
        val text = emoji.repeat(10)
        val parts = pieces(text, 3)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { part ->
            assertTrue(part.isNotEmpty())
            assertTrue("no half of a surrogate pair", !Character.isLowSurrogate(part.first()))
            assertTrue("no half of a surrogate pair", !Character.isHighSurrogate(part.last()))
        }
    }

    @Test fun evenALimitOfOneMakesProgress() {
        assertEquals(listOf("a", "b", "c"), pieces("abc", 1))
    }
}
