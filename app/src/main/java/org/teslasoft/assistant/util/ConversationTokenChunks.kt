package org.teslasoft.assistant.util

import java.text.BreakIterator
import java.util.Locale
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy

/** Shared source-text rules for compaction and the Memory Scanner. */
object ConversationTokenChunks {
    const val DEFAULT_MAXIMUM_TOKENS = 100_000
    const val EXCHANGE_ALLOWANCE_TOKENS = 2_000

    /** Largest sentence-aligned pieces, covering every character exactly once.
     * A single oversized sentence travels alone, intact. */
    fun splitSentences(text: String, maximumTokens: Int): List<String> {
        val maximum = maximumTokens.coerceAtLeast(1)
        if (IncludeTextPolicy.estimateTokens(text) <= maximum) return listOf(text)
        val sentences = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
        val chunks = ArrayList<String>()
        var chunkStart = 0
        var sentenceStart = sentences.first()
        var ascii = 0L
        var other = 0L
        var sentenceEnd = sentences.next()
        while (sentenceEnd != BreakIterator.DONE) {
            var sentenceAscii = 0L
            var sentenceOther = 0L
            var offset = sentenceStart
            while (offset < sentenceEnd) {
                val codePoint = text.codePointAt(offset)
                if (codePoint <= 0x7F) sentenceAscii++
                else sentenceOther += Character.charCount(codePoint)
                offset += Character.charCount(codePoint)
            }
            val candidateTokens = (ascii + sentenceAscii + 3) / 4 + other + sentenceOther
            if (candidateTokens > maximum && sentenceStart > chunkStart) {
                chunks.add(text.substring(chunkStart, sentenceStart))
                chunkStart = sentenceStart
                ascii = 0
                other = 0
            }
            ascii += sentenceAscii
            other += sentenceOther
            if ((ascii + 3) / 4 + other > maximum) {
                // One sentence alone is oversized. Never cut it or add more to it.
                chunks.add(text.substring(chunkStart, sentenceEnd))
                chunkStart = sentenceEnd
                ascii = 0
                other = 0
            }
            sentenceStart = sentenceEnd
            sentenceEnd = sentences.next()
        }
        if (chunkStart < text.length) chunks.add(text.substring(chunkStart))
        return chunks
    }
}
