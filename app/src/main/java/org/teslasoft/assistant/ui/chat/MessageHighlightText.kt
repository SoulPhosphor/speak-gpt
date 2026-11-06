/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.text.SpannableStringBuilder
import java.text.BreakIterator

/**
 * Text rules for the chat's own message highlighting: which word a press
 * lands on, how a dragged edge snaps to whole words while the highlight
 * grows, and what Copy puts on the clipboard.
 */
internal object MessageHighlightText {

    /**
     * The word at [offset], as start and end offsets. On a space or symbol
     * the nearest word before it is used, then the nearest after it.
     */
    fun wordAt(text: CharSequence, offset: Int): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val source = text.toString()
        val position = offset.coerceIn(0, source.length)
        val words = wordIterator(source)

        val segmentStart = when {
            position >= source.length -> words.preceding(source.length)
            words.isBoundary(position) -> position
            else -> words.preceding(position)
        }
        val segmentEnd = words.following(segmentStart)
        if (isWord(source, segmentStart, segmentEnd)) return segmentStart to segmentEnd

        if (segmentStart > 0) {
            val before = words.preceding(segmentStart)
            if (before != BreakIterator.DONE && isWord(source, before, segmentStart)) {
                return before to segmentStart
            }
        }
        if (segmentEnd != BreakIterator.DONE && segmentEnd < source.length) {
            val after = words.following(segmentEnd)
            if (after != BreakIterator.DONE && isWord(source, segmentEnd, after)) {
                return segmentEnd to after
            }
        }
        return if (position < source.length) position to position + 1 else position - 1 to position
    }

    /** A growing end edge inside a word moves to that word's end. */
    fun endOfWordAt(text: CharSequence, offset: Int): Int {
        val source = text.toString()
        val position = offset.coerceIn(0, source.length)
        if (!insideWord(source, position)) return position
        val end = wordIterator(source).following(position)
        return if (end == BreakIterator.DONE) source.length else end
    }

    /** A growing start edge inside a word moves to that word's start. */
    fun startOfWordAt(text: CharSequence, offset: Int): Int {
        val source = text.toString()
        val position = offset.coerceIn(0, source.length)
        if (!insideWord(source, position)) return position
        val start = wordIterator(source).preceding(position)
        return if (start == BreakIterator.DONE) 0 else start
    }

    /**
     * The highlighted text for Copy and Share. Keeps the text's styling and
     * drops the invisible direction marks the portrait layout adds, which are
     * not part of the message.
     */
    fun copyText(text: CharSequence, start: Int, end: Int): CharSequence {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        val builder = SpannableStringBuilder(text, from, to)
        for (i in builder.length - 1 downTo 0) {
            if (builder[i] in LAYOUT_DIRECTION_MARKS) builder.delete(i, i + 1)
        }
        return builder
    }

    private fun wordIterator(source: String): BreakIterator =
        BreakIterator.getWordInstance().apply { setText(source) }

    private fun insideWord(source: String, position: Int): Boolean =
        position > 0 && position < source.length &&
            isWordChar(source[position - 1]) && isWordChar(source[position])

    private fun isWord(source: String, start: Int, end: Int): Boolean {
        if (start == BreakIterator.DONE || end == BreakIterator.DONE || end <= start) return false
        for (i in start until end) if (isWordChar(source[i])) return true
        return false
    }

    private fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c)

    /** Directional isolates inserted by [PortraitAwareMessageTextView]. */
    private val LAYOUT_DIRECTION_MARKS = charArrayOf('⁦', '⁧', '⁨', '⁩')
}
