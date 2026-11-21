/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import java.text.BreakIterator

/**
 * Lossless splitting of one long message for the Memory Scanner (memory
 * development plan G.5b and M.2 Stage 6A). A message that does not fit is cut
 * at the last paragraph break that fits, then the last line break, then the
 * last space between words, and only then between two whole characters
 * (grapheme clusters, so a surrogate pair or combining mark is never cut).
 * Periods are not used: dictated text often has none.
 *
 * The returned ranges are contiguous and cover the whole text exactly, so the
 * fragments joined in order are the original message. Every fragment holds at
 * least one character, so every retry makes progress even when a single
 * character is larger than the limit.
 */
object ScanTextSplitter {

    data class Piece(val start: Int, val end: Int)

    fun split(
        text: String,
        maximumTokens: Int,
        estimate: (String) -> Int = IncludeTextPolicy::estimateTokens
    ): List<Piece> {
        if (text.isEmpty()) return emptyList()
        val maximum = maximumTokens.coerceAtLeast(1)
        if (estimate(text) <= maximum) return listOf(Piece(0, text.length))
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        val out = ArrayList<Piece>()
        var start = 0
        while (start < text.length) {
            if (estimate(text.substring(start)) <= maximum) {
                out.add(Piece(start, text.length))
                break
            }
            val fit = longestFittingEnd(text, start, maximum, estimate)
            val end = boundaryEnd(text, start, fit, "\n\n")
                ?: boundaryEnd(text, start, fit, "\n")
                ?: spaceEnd(text, start, fit)
                ?: graphemeEnd(graphemes, start, fit)
            out.add(Piece(start, end))
            start = end
        }
        return out
    }

    /** The largest end (exclusive) whose text from [start] fits [maximum];
     *  the token estimate grows with length, so a binary search applies. */
    private fun longestFittingEnd(
        text: String,
        start: Int,
        maximum: Int,
        estimate: (String) -> Int
    ): Int {
        var low = start
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (estimate(text.substring(start, mid)) <= maximum) low = mid else high = mid - 1
        }
        return low
    }

    /** End just after the last [separator] that lies inside (start, fit]. */
    private fun boundaryEnd(text: String, start: Int, fit: Int, separator: String): Int? {
        if (fit <= start) return null
        val at = text.lastIndexOf(separator, fit - separator.length)
        if (at < start) return null
        val end = at + separator.length
        return end.takeIf { it in (start + 1)..fit }
    }

    private fun spaceEnd(text: String, start: Int, fit: Int): Int? {
        var i = fit - 1
        while (i > start) {
            if (text[i].isWhitespace()) return i + 1
            i--
        }
        return null
    }

    /** The last whole-character end at or before [fit]; at least one
     *  character so the split always moves forward. */
    private fun graphemeEnd(graphemes: BreakIterator, start: Int, fit: Int): Int {
        val first = graphemes.following(start).takeIf { it != BreakIterator.DONE } ?: (start + 1)
        if (fit <= first) return first
        val before = if (graphemes.isBoundary(fit)) fit else graphemes.preceding(fit)
        return if (before == BreakIterator.DONE || before <= start) first else before
    }
}
