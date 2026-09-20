package org.teslasoft.assistant.preferences.chatsearch

object SearchSnippetPolicy {
    const val CONTEXT_CHARS = 72

    data class Snippet(val text: String, val ranges: List<IntRange>)

    fun create(text: String, ranges: List<IntRange>, contextChars: Int = CONTEXT_CHARS): Snippet {
        if (ranges.isEmpty()) return Snippet(collapse(text), emptyList())
        val first = ranges.first().first
        val last = ranges.maxOf { it.last }
        val start = (first - contextChars).coerceAtLeast(0)
        val endExclusive = (last + 1 + contextChars).coerceAtMost(text.length)
        val raw = text.substring(start, endExclusive)
        val leading = start > 0
        val trailing = endExclusive < text.length
        val prefix = if (leading) "…" else ""

        // Collapse whitespace while preserving an old-offset -> new-offset map.
        val out = StringBuilder(prefix)
        val map = IntArray(raw.length + 1)
        var inWhitespace = false
        raw.forEachIndexed { index, char ->
            map[index] = out.length
            if (char.isWhitespace()) {
                if (!inWhitespace) out.append(' ')
                inWhitespace = true
            } else {
                out.append(char)
                inWhitespace = false
            }
        }
        map[raw.length] = out.length
        if (trailing) out.append('…')
        val untrimmed = out.toString()
        val trimStart = untrimmed.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        val display = untrimmed.trim()
        val adjusted = ranges.mapNotNull { range ->
            val localStart = range.first - start
            val localEnd = range.last + 1 - start
            if (localEnd <= 0 || localStart >= raw.length) null
            else (map[localStart.coerceAtLeast(0)] - trimStart) until
                (map[localEnd.coerceAtMost(raw.length)] - trimStart)
        }.filter { !it.isEmpty() }
            .mapNotNull { range ->
                val first = range.first.coerceAtLeast(0)
                val end = (range.last + 1).coerceAtMost(display.length)
                (first until end).takeUnless { it.isEmpty() }
            }
        return Snippet(display, adjusted)
    }

    private fun collapse(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
