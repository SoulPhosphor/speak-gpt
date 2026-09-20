package org.teslasoft.assistant.preferences.chatsearch

import java.util.Locale

object SearchQueryCompiler {
    fun compile(query: String, options: SearchOptions, locale: Locale = Locale.getDefault()): String? {
        val tokens = SearchTextPolicy.queryTokens(query, options.copy(matchCase = false), locale)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" AND ") { token ->
            val escaped = token.replace("\"", "\"\"")
            if (options.wholeWords) "\"$escaped\"" else "\"$escaped\"*"
        }
    }
}

