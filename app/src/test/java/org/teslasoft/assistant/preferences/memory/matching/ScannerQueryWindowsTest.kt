/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 6B.2: query windows cover a long turn to its end. */
class ScannerQueryWindowsTest {

    /** One token per space-separated word plus two for the query prefix. */
    private val count: (String) -> Int = { it.split(" ").size + 2 }

    @Test fun shortTextIsOneWindow() {
        assertEquals(listOf("hello there"), ScannerQueryWindows.split("  hello there ", 512, count))
    }

    @Test fun blankTextHasNoWindows() {
        assertTrue(ScannerQueryWindows.split("   ", 512, count).isEmpty())
    }

    @Test fun longTextWindowsFitOverlapAndReachTheEnd() {
        val words = (1..1000).map { "w$it" }
        val windows = ScannerQueryWindows.split(words.joinToString(" "), 100, count)
        assertTrue(windows.size > 10)
        windows.forEach { assertTrue(count(it) <= 100) }
        assertTrue(windows.last().endsWith("w1000"))
        assertTrue(windows.first().startsWith("w1 "))
        // Every word is covered, and consecutive windows share words.
        val covered = windows.flatMap { it.split(" ") }.toSet()
        assertEquals(words.toSet(), covered)
        windows.zipWithNext().forEach { (a, b) ->
            assertTrue(a.split(" ").toSet().intersect(b.split(" ").toSet()).isNotEmpty())
        }
    }

    @Test fun textWithoutSpacesIsCutWithoutSplittingCharacters() {
        val emoji = "🎻" // violin, a surrogate pair
        val text = emoji.repeat(300)
        val pieces = ScannerQueryWindows.pieces(text)
        assertTrue(pieces.size > 1)
        assertEquals(text, pieces.joinToString(""))
        pieces.forEach {
            assertTrue(!Character.isLowSurrogate(it.first()))
            assertTrue(!Character.isHighSurrogate(it.last()))
        }
        // Counted by pieces, as a real tokenizer would count many tokens.
        val byPieces: (String) -> Int = { ScannerQueryWindows.pieces(it).size + 2 }
        val windows = ScannerQueryWindows.split(text, 6, byPieces)
        assertTrue(windows.size > 1)
        windows.forEach { assertTrue(byPieces(it) <= 6) }
        assertEquals(pieces.last(), windows.last().split(" ").last())
    }

    @Test fun aWindowAlwaysMakesProgress() {
        // A counter that says even one word is too long must not loop forever.
        val windows = ScannerQueryWindows.split("a b c d", 3) { if (it.contains(" ")) 99 else 50 }
        assertEquals(listOf("a", "b", "c", "d"), windows)
    }
}
