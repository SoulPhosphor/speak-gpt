/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

/**
 * Overlapping search windows over scanner text (Stage 6B.2). Each window fits
 * the embedding model's query allowance as counted by the model's own
 * tokenizer (prefix and special tokens included), consecutive windows
 * overlap, and the last window always ends at the end of the text, so the end
 * of a long turn is searched too. Windows break between words; a "word"
 * longer than [MAX_PIECE_CHARS] (text without spaces) is cut into pieces at
 * code-point boundaries so it cannot hide the rest of the text, and a piece
 * that alone exceeds the allowance is halved again at a code-point boundary.
 * Joining a window's pieces with spaces adds a space between pieces of one
 * unbroken word; only the search query sees it, never the saved text.
 * Code-point boundaries can fall inside a multi-code-point emoji or an
 * accented letter written with a combining mark.
 */
object ScannerQueryWindows {

    const val MAX_PIECE_CHARS = 48
    /** Share of a window repeated at the start of the next. */
    private const val OVERLAP_DIVISOR = 4

    fun split(text: String, tokenLimit: Int, countTokens: (String) -> Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (tokenLimit <= 0) return emptyList()
        if (countTokens(trimmed) <= tokenLimit) return listOf(trimmed)

        val pieces = ArrayList(pieces(trimmed))
        val windows = ArrayList<String>()
        var start = 0
        while (start < pieces.size) {
            var end = longestFitting(pieces, start, tokenLimit, countTokens)
            if (end <= start + 1 && countTokens(pieces[start]) > tokenLimit) {
                // One piece alone is over the allowance (dense text without
                // spaces): halve it at a code-point boundary and try again,
                // so no window is cut short by the model. A single code
                // point that still does not fit is sent as it is.
                val halves = halve(pieces[start])
                if (halves != null) {
                    pieces.removeAt(start)
                    pieces.addAll(start, halves)
                    continue
                }
            }
            if (end <= start) end = start + 1 // a single piece always makes progress
            windows.add(join(pieces, start, end))
            if (end >= pieces.size) break
            val size = end - start
            val step = (size - size / OVERLAP_DIVISOR).coerceAtLeast(1)
            start += step
        }
        return windows
    }

    /** The largest end (exclusive) such that pieces[start, end) fits, found
     *  by bisection so the tokenizer runs O(log n) times per window. */
    private fun longestFitting(
        pieces: List<String>, start: Int, tokenLimit: Int, countTokens: (String) -> Int
    ): Int {
        var lo = start + 1
        // Every piece is at least one token, so a window never holds more
        // pieces than the limit; this keeps each count short.
        var hi = minOf(pieces.size, start + tokenLimit)
        if (countTokens(join(pieces, start, hi)) <= tokenLimit) return hi
        if (countTokens(join(pieces, start, lo)) > tokenLimit) return lo
        // Invariant: [start, lo) fits, [start, hi) does not.
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (countTokens(join(pieces, start, mid)) <= tokenLimit) lo = mid else hi = mid
        }
        return lo
    }

    private fun join(pieces: List<String>, from: Int, to: Int): String =
        pieces.subList(from, to).joinToString(" ")

    /** [piece] split into two at the code-point boundary nearest its middle,
     *  or null when it is a single code point. */
    private fun halve(piece: String): List<String>? {
        val codePoints = piece.codePointCount(0, piece.length)
        if (codePoints < 2) return null
        val middle = piece.offsetByCodePoints(0, codePoints / 2)
        return listOf(piece.substring(0, middle), piece.substring(middle))
    }

    internal fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            if (word.length <= MAX_PIECE_CHARS) {
                out.add(word)
                continue
            }
            var i = 0
            while (i < word.length) {
                var end = minOf(word.length, i + MAX_PIECE_CHARS)
                // Never split a surrogate pair.
                if (end < word.length && Character.isHighSurrogate(word[end - 1])) end--
                out.add(word.substring(i, end))
                i = end
            }
        }
        return out
    }
}
