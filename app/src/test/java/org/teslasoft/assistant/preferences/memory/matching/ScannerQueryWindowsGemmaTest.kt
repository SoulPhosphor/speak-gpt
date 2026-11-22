/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.librarian.HfTokenizer
import java.io.File

/**
 * Stage 6B.2 integration check of [ScannerQueryWindows] with the real
 * EmbeddingGemma tokenizer and the app's 512-token query allowance, counted
 * exactly as the model counts a query (`OnnxEmbeddingModel.countTokens`: the
 * query prefix plus the text, with the tokenizer's special tokens).
 *
 * The tokenizer is licensed and not part of the repository. Download it from
 * the URL in `EmbeddingModels.kt` and point `GEMMA_TOKENIZER_JSON` at the file:
 *
 *     GEMMA_TOKENIZER_JSON=/path/to/tokenizer.json ./gradlew testDebugUnitTest \
 *         --tests '*ScannerQueryWindowsGemmaTest*'
 *
 * Without it the tests are skipped; routine CI relies on the synthetic
 * byte-fallback tests in [ScannerQueryWindowsTest].
 */
class ScannerQueryWindowsGemmaTest {

    private lateinit var tokenizer: HfTokenizer

    /** The catalog's query prefix (`EmbeddingModels.GEMMA_QUERY_PREFIX`). */
    private val queryPrefix = "task: search result | query: "
    private val limit = 512
    private val count: (String) -> Int = { tokenizer.countTokens(queryPrefix + it) }

    @Before
    fun load() {
        val path = System.getenv("GEMMA_TOKENIZER_JSON")
        assumeTrue("GEMMA_TOKENIZER_JSON not set", !path.isNullOrBlank() && File(path).isFile)
        tokenizer = HfTokenizer.load(File(path!!))
    }

    private fun assertWindowsFitAndAreWhole(text: String, windows: List<String>) {
        assertTrue(windows.size > 1)
        for (w in windows) {
            val n = count(w)
            assertTrue("window over the query allowance: $n", n <= limit)
            // The model reads the whole window: encoding at the allowance cuts nothing.
            assertEquals(n, tokenizer.encode(queryPrefix + w, limit).size)
            assertFalse(Character.isLowSurrogate(w.first()))
            assertFalse(Character.isHighSurrogate(w.last()))
        }
        // In order, each window starts inside the text already covered, and
        // together they reach the end: nothing skipped or lost.
        val compact = text.replace(Regex("\\s+"), "")
        var covered = 0
        for (w in windows) {
            val joined = w.replace(" ", "")
            val at = compact.indexOf(joined, startIndex = maxOf(0, covered - joined.length))
            assertTrue("window out of order or text skipped", at in 0..covered)
            covered = maxOf(covered, at + joined.length)
        }
        assertEquals(compact.length, covered)
    }

    @Test fun aLongTurnIsCoveredToItsEndWithinTheAllowance() {
        val words = (1..3_000).map { "word$it" }
        val text = words.joinToString(" ") + " and the last thing: violin lessons start Monday"
        assertTrue(count(text) > limit)
        val windows = ScannerQueryWindows.split(text, limit, count)
        assertWindowsFitAndAreWhole(text, windows)
        assertTrue(windows.last().endsWith("violin lessons start Monday"))
    }

    @Test fun denseUnbrokenUnicodeIsSplitWithoutLosingOrBreakingCharacters() {
        val random = java.util.Random(7)
        val alphabet = (0x1F300..0x1F5FF).toList() + (0x4E00..0x9FFF).toList() +
            (0x0900..0x097F).toList() + (0x1F900..0x1F9FF).toList()
        val sb = StringBuilder()
        repeat(6_000) { sb.appendCodePoint(alphabet[random.nextInt(alphabet.size)]) }
        val text = sb.toString()
        assertTrue(count(text) > limit)
        assertWindowsFitAndAreWhole(text, ScannerQueryWindows.split(text, limit, count))
    }

    @Test fun piecesOverASmallAllowanceAreHalvedAndStillFit() {
        // A deliberately small allowance makes single 48-character pieces of
        // rare characters too large, exercising the halving with real counts.
        val random = java.util.Random(11)
        val sb = StringBuilder()
        repeat(800) { sb.appendCodePoint(0x20000 + random.nextInt(0xA6DF)) } // CJK Extension B
        val text = sb.toString()
        val small = 40
        val windows = ScannerQueryWindows.split(text, small, count)
        assertTrue(windows.size > 1)
        windows.forEach { w ->
            assertTrue("window over budget: ${count(w)}", count(w) <= small)
            assertFalse(Character.isLowSurrogate(w.first()))
            assertFalse(Character.isHighSurrogate(w.last()))
        }
        var covered = 0
        for (w in windows) {
            val joined = w.replace(" ", "")
            val at = text.indexOf(joined, startIndex = maxOf(0, covered - joined.length))
            assertTrue(at in 0..covered)
            covered = maxOf(covered, at + joined.length)
        }
        assertEquals(text.length, covered)
    }
}
