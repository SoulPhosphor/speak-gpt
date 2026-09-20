package org.teslasoft.assistant.preferences.chatsearch

import android.icu.lang.UCharacter
import android.icu.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/** The sole Unicode token/match authority used by indexing, verification and highlights. */
object SearchTextPolicy {
    const val POLICY_VERSION = 1

    data class Token(val raw: String, val comparable: String, val start: Int, val end: Int)

    fun queryTokens(query: String, options: SearchOptions, locale: Locale): List<String> =
        tokenize(query, options.matchCase, locale).map { it.comparable }

    fun indexText(text: String, locale: Locale): String =
        tokenize(text, matchCase = false, locale).joinToString(" ") { it.comparable }

    fun match(
        query: String,
        document: String,
        options: SearchOptions,
        locale: Locale = Locale.getDefault()
    ): SearchMatch? {
        val wanted = queryTokens(query, options, locale)
        if (wanted.isEmpty()) return null
        val available = tokenize(document, options.matchCase, locale)
        if (available.isEmpty()) return null

        val ranges = LinkedHashSet<IntRange>()
        var allExact = true
        for (queryToken in wanted) {
            val matches = available.filter { token ->
                if (options.wholeWords) token.comparable == queryToken
                else token.comparable.startsWith(queryToken)
            }
            if (matches.isEmpty()) return null
            if (matches.none { it.comparable == queryToken }) allExact = false
            matches.forEach { ranges += it.start until it.end }
        }
        return SearchMatch(ranges.sortedBy { it.first }, allExact)
    }

    fun normalizedComparable(text: String, matchCase: Boolean): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        return if (matchCase) normalized else UCharacter.foldCase(normalized, true)
    }

    private fun tokenize(text: String, matchCase: Boolean, locale: Locale): List<Token> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        val result = ArrayList<Token>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val raw = text.substring(start, end)
            if (raw.codePoints().anyMatch { Character.isLetterOrDigit(it) }) {
                result += Token(raw, normalizedComparable(raw, matchCase), start, end)
            }
            start = end
            end = iterator.next()
        }
        return result
    }
}
