/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.librarian.HfTokenizer

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

    /**
     * The app's real tokenizer engine ([HfTokenizer]) with a minimal byte-
     * fallback vocabulary: a beginning-of-text token, one token per space, and
     * every other character costs one token per UTF-8 byte (an emoji 4, a CJK
     * character 3). Gemma's own vocabulary (not in the repository) has whole
     * tokens for many such characters, so it counts fewer tokens than this;
     * this counter is the dense worst case. Counts include a query prefix, as
     * the model's query count does.
     */
    private val byteTokenizer: HfTokenizer by lazy {
        val bytes = (0..255).joinToString(",") { b -> "\"<0x%02X>\": ${b + 3}".format(b) }
        HfTokenizer.fromJson(
            """
            {
              "added_tokens": [
                {"id": 0, "content": "<unk>", "special": true},
                {"id": 1, "content": "<bos>", "special": true}
              ],
              "normalizer": {"type": "Sequence", "normalizers": [
                {"type": "Replace", "pattern": {"String": " "}, "content": "\u2581"}
              ]},
              "pre_tokenizer": null,
              "post_processor": {
                "type": "TemplateProcessing",
                "single": [
                  {"SpecialToken": {"id": "<bos>", "type_id": 0}},
                  {"Sequence": {"id": "A", "type_id": 0}}
                ],
                "special_tokens": {"<bos>": {"id": "<bos>", "ids": [1], "tokens": ["<bos>"]}}
              },
              "model": {
                "type": "BPE", "unk_token": "<unk>", "fuse_unk": false, "byte_fallback": true,
                "vocab": {"<unk>": 0, "<bos>": 1, "\u2581": 2, $bytes},
                "merges": []
              }
            }
            """.trimIndent()
        )
    }
    private val queryPrefix = "query: "
    private val realCount: (String) -> Int = { byteTokenizer.countTokens(queryPrefix + it) }

    /** Unbroken text mixing emoji (surrogate pairs) and CJK, varied so every
     *  piece is distinct and a window's position can be traced. */
    private fun denseText(codePoints: Int): String {
        val alphabet = (0x1F300..0x1F3FF).toList() + (0x4E00..0x4EFF).toList()
        val random = java.util.Random(42)
        val sb = StringBuilder()
        repeat(codePoints) { sb.appendCodePoint(alphabet[random.nextInt(alphabet.size)]) }
        return sb.toString()
    }

    /** Each window as the run of [pieces] it holds (start, end exclusive). */
    private fun runs(windows: List<String>, pieces: List<String>): List<IntRange> = windows.map { w ->
        val parts = w.split(" ")
        val at = (0..pieces.size - parts.size).first { i -> pieces.subList(i, i + parts.size) == parts }
        at until at + parts.size
    }

    @Test fun denseUnbrokenTextIsSplitWithinTheBudgetWithoutLosingAnything() {
        val text = denseText(2_000)
        assertTrue("the case needs splitting", realCount(text) > 512)
        val windows = ScannerQueryWindows.split(text, 512, realCount)
        assertTrue(windows.size > 1)
        windows.forEach { w ->
            assertTrue("window over budget: ${realCount(w)}", realCount(w) <= 512)
            assertFalse(Character.isLowSurrogate(w.first()))
            assertFalse(Character.isHighSurrogate(w.last()))
        }
        // The pieces put back together are exactly the text, and the windows
        // are consecutive overlapping runs of them from the first to the last.
        val pieces = ScannerQueryWindows.pieces(text)
        assertEquals(text, pieces.joinToString(""))
        val runs = runs(windows, pieces)
        assertEquals(0, runs.first().first)
        assertEquals(pieces.lastIndex, runs.last().last)
        runs.zipWithNext().forEach { (a, b) ->
            assertTrue("windows must overlap: $a then $b", b.first in a && b.last > a.last)
        }
    }

    @Test fun aPieceOverTheBudgetIsHalvedUntilItFits() {
        // 48 code points of 3- or 4-byte characters is about 170 tokens; a
        // 40-token allowance forces every piece to be halved, repeatedly.
        val text = denseText(300)
        val windows = ScannerQueryWindows.split(text, 40, realCount)
        windows.forEach { w ->
            assertTrue("window over budget: ${realCount(w)}", realCount(w) <= 40)
            assertFalse(Character.isLowSurrogate(w.first()))
            assertFalse(Character.isHighSurrogate(w.last()))
        }
        // Nothing skipped: walking the windows in order, each one starts
        // inside the text already covered and the last reaches the end.
        var covered = 0
        for (w in windows) {
            val joined = w.replace(" ", "")
            val at = text.indexOf(joined, startIndex = maxOf(0, covered - joined.length))
            assertTrue("window not found in order", at in 0..covered)
            covered = maxOf(covered, at + joined.length)
        }
        assertEquals(text.length, covered)
    }

    @Test fun combiningMarksAndJoinedEmojiAreKeptWhole() {
        // Accented letters written with combining marks, and family emoji
        // joined by zero-width joiners, repeated with no spaces.
        // Drawn at random from the sequences so the text never repeats and
        // each window can be located exactly.
        val units = listOf(
            "e\u0301", "a\u0308", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
            "\u0915\u094D\u0937", "\uD83D\uDC4D\uD83C\uDFFD", "\u4E2D", "\u6587"
        )
        val random = java.util.Random(3)
        val text = buildString { repeat(600) { append(units[random.nextInt(units.size)]) } }
        val windows = ScannerQueryWindows.split(text, 64, realCount)
        assertTrue(windows.size > 1)
        windows.forEach { w ->
            assertTrue(realCount(w) <= 64)
            assertFalse(Character.isLowSurrogate(w.first()))
            assertFalse(Character.isHighSurrogate(w.last()))
        }
        // Every code point arrives, in order: nothing is lost even where a cut
        // falls inside a combined character.
        var covered = 0
        for (w in windows) {
            val joined = w.replace(" ", "")
            val at = text.indexOf(joined, startIndex = maxOf(0, covered - joined.length))
            assertTrue(at in 0..covered)
            covered = maxOf(covered, at + joined.length)
        }
        assertEquals(text.length, covered)
        assertTrue(text.endsWith(windows.last().replace(" ", "")))
    }

    @Test fun anAllowanceSmallerThanThePrefixStillEndsAndCoversTheText() {
        // Nothing fits; every code point becomes its own window and the
        // split still finishes without dropping text.
        val windows = ScannerQueryWindows.split("abc def", 1, realCount)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), windows)
    }

    @Test fun aWindowAlwaysMakesProgress() {
        // A counter that says even one word is too long must not loop forever.
        val windows = ScannerQueryWindows.split("a b c d", 3) { if (it.contains(" ")) 99 else 50 }
        assertEquals(listOf("a", "b", "c", "d"), windows)
    }
}
