package org.teslasoft.assistant.conversation.index

import java.text.BreakIterator
import java.util.Locale

/** One passage: the characters [start, end) of the message text. */
data class PassageSpan(val start: Int, val end: Int)

/** The passages of one message, in order, and the cutting rules that made them. */
data class PassageCut(val cutterVersion: Int, val passages: List<PassageSpan>)

/**
 * Cuts one message's text into passages for the conversation index
 * (memory development plan G.5b, cutter rules 1–3 and Versions).
 *
 * - Every character of the text is in exactly one passage, in order; the
 *   text itself is never changed. Empty text has no passages.
 * - A piece is split only when it is larger than [cut]'s target size:
 *   first at paragraphs (blank lines), then at lines, then between words,
 *   then between two user-perceived characters, and last between two code
 *   points. Periods are never cut points.
 * - Neighbouring pieces are then joined, in order, while the joined text
 *   still fits the target size.
 * - Sizes are always measured on the actual text of a candidate passage
 *   with the supplied function, because token counts are not additive.
 *   The only passage that can exceed the target is a single code point
 *   that alone measures larger than it.
 *
 * The same text, target and size function always give the same passages.
 */
object PassageCutter {

    /** Raise whenever a change to these rules can change any passage. */
    const val CUTTER_VERSION = 1

    private const val PARAGRAPHS = 0
    private const val LINES = 1
    private const val WORDS = 2
    private const val CHARACTERS = 3
    private const val CODE_POINTS = 4

    fun cut(text: String, targetSize: Int, measure: (String) -> Int): PassageCut {
        require(targetSize >= 1) { "targetSize must be at least 1" }
        if (text.isEmpty()) return PassageCut(CUTTER_VERSION, emptyList())
        val fits = { start: Int, end: Int -> measure(text.substring(start, end)) <= targetSize }
        val pieces = ArrayList<PassageSpan>()
        split(text, 0, text.length, PARAGRAPHS, fits, pieces)
        return PassageCut(CUTTER_VERSION, join(pieces, fits))
    }

    /** Appends pieces covering [start, end), each fitting the target where possible. */
    private fun split(
        text: String,
        start: Int,
        end: Int,
        level: Int,
        fits: (Int, Int) -> Boolean,
        out: MutableList<PassageSpan>
    ) {
        if (fits(start, end)) {
            out.add(PassageSpan(start, end))
            return
        }
        var currentLevel = level
        var cuts = cutPoints(text, start, end, currentLevel)
        while (cuts.isEmpty() && currentLevel < CODE_POINTS) {
            currentLevel++
            cuts = cutPoints(text, start, end, currentLevel)
        }
        if (cuts.isEmpty()) {
            // A single code point larger than the target travels alone.
            out.add(PassageSpan(start, end))
            return
        }
        var pieceStart = start
        for (cut in cuts + end) {
            if (currentLevel == CODE_POINTS) out.add(PassageSpan(pieceStart, cut))
            else split(text, pieceStart, cut, currentLevel + 1, fits, out)
            pieceStart = cut
        }
    }

    /** Joins neighbouring pieces greedily, measuring each candidate passage. */
    private fun join(pieces: List<PassageSpan>, fits: (Int, Int) -> Boolean): List<PassageSpan> {
        val passages = ArrayList<PassageSpan>()
        var first = 0
        while (first < pieces.size) {
            val start = pieces[first].start
            // Gallop, then binary-search, for the last piece that still fits.
            var good = first
            var step = 1
            var bad = pieces.size
            while (good + step < pieces.size) {
                if (fits(start, pieces[good + step].end)) {
                    good += step
                    step *= 2
                } else {
                    bad = good + step
                    break
                }
            }
            while (bad - good > 1) {
                val middle = (good + bad) ushr 1
                if (fits(start, pieces[middle].end)) good = middle else bad = middle
            }
            passages.add(PassageSpan(start, pieces[good].end))
            first = good + 1
        }
        return passages
    }

    /** Cut points strictly inside (start, end) for one level, in order. */
    private fun cutPoints(text: String, start: Int, end: Int, level: Int): List<Int> = when (level) {
        PARAGRAPHS -> paragraphCuts(text, start, end)
        LINES -> lineCuts(text, start, end)
        WORDS -> wordCuts(text, start, end)
        CHARACTERS -> characterCuts(text, start, end)
        else -> codePointCuts(text, start, end)
    }

    /** After each line break; a CR LF pair is one break. */
    private fun lineCuts(text: String, start: Int, end: Int): List<Int> {
        val cuts = ArrayList<Int>()
        var index = start
        while (index < end) {
            val char = text[index]
            val breakEnd = when {
                char == '\r' && index + 1 < end && text[index + 1] == '\n' -> index + 2
                isLineBreak(char) -> index + 1
                else -> -1
            }
            if (breakEnd > 0) {
                if (breakEnd < end) cuts.add(breakEnd)
                index = breakEnd
            } else {
                index++
            }
        }
        return cuts
    }

    /** Where a non-blank line follows a blank one; blank lines stay with the paragraph before. */
    private fun paragraphCuts(text: String, start: Int, end: Int): List<Int> {
        val lineStarts = listOf(start) + lineCuts(text, start, end)
        val cuts = ArrayList<Int>()
        for (index in 1 until lineStarts.size) {
            val previousEnd = lineStarts[index]
            val nextEnd = if (index + 1 < lineStarts.size) lineStarts[index + 1] else end
            if (isBlank(text, lineStarts[index - 1], previousEnd) && !isBlank(text, previousEnd, nextEnd)) {
                cuts.add(previousEnd)
            }
        }
        return cuts
    }

    /**
     * Before a word that follows white space, so the space stays with the
     * word before it; and, for writing without spaces, at the platform's
     * word boundaries between two letters or digits.
     */
    private fun wordCuts(text: String, start: Int, end: Int): List<Int> {
        val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
        val cuts = ArrayList<Int>()
        var boundary = words.following(start)
        while (boundary != BreakIterator.DONE && boundary < end) {
            val before = text.codePointBefore(boundary)
            val after = text.codePointAt(boundary)
            val afterSpace = Character.isWhitespace(before) && !Character.isWhitespace(after)
            val betweenLetters = Character.isLetterOrDigit(before) && Character.isLetterOrDigit(after)
            if (afterSpace || betweenLetters) cuts.add(boundary)
            boundary = words.next()
        }
        return cuts
    }

    /** Between user-perceived characters, never inside a surrogate pair. */
    private fun characterCuts(text: String, start: Int, end: Int): List<Int> {
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val cuts = ArrayList<Int>()
        var boundary = characters.following(start)
        while (boundary != BreakIterator.DONE && boundary < end) {
            if (!splitsSurrogatePair(text, boundary)) cuts.add(boundary)
            boundary = characters.next()
        }
        return cuts
    }

    private fun codePointCuts(text: String, start: Int, end: Int): List<Int> {
        val cuts = ArrayList<Int>()
        var index = start + Character.charCount(text.codePointAt(start))
        while (index < end) {
            cuts.add(index)
            index += Character.charCount(text.codePointAt(index))
        }
        return cuts
    }

    private fun splitsSurrogatePair(text: String, index: Int): Boolean =
        index > 0 && index < text.length &&
            Character.isHighSurrogate(text[index - 1]) && Character.isLowSurrogate(text[index])

    private fun isLineBreak(char: Char): Boolean =
        char == '\n' || char == '\r' || char == ' ' || char == ' ' || char == '\u0085'

    private fun isBlank(text: String, start: Int, end: Int): Boolean {
        for (index in start until end) {
            val char = text[index]
            if (!Character.isWhitespace(char) && !isLineBreak(char)) return false
        }
        return true
    }
}
