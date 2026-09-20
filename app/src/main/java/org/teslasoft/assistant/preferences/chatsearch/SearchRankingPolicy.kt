package org.teslasoft.assistant.preferences.chatsearch

import java.util.Locale

object SearchRankingPolicy {
    const val EXACT_FULL_TITLE = 0
    const val EXACT_TITLE_TOKEN = 1
    const val TITLE_PREFIX = 2
    const val EXACT_MESSAGE_TOKEN = 3
    const val MESSAGE_PREFIX = 4

    fun rankClass(
        kind: SearchDocumentKind,
        text: String,
        query: String,
        options: SearchOptions,
        match: SearchMatch,
        locale: Locale = Locale.getDefault()
    ): Int {
        if (kind == SearchDocumentKind.TITLE) {
            val fullTitle = SearchTextPolicy.normalizedComparable(text.trim(), options.matchCase)
            val fullQuery = SearchTextPolicy.normalizedComparable(query.trim(), options.matchCase)
            if (fullTitle == fullQuery) return EXACT_FULL_TITLE
            return if (match.allTokensExact) EXACT_TITLE_TOKEN else TITLE_PREFIX
        }
        return if (match.allTokensExact) EXACT_MESSAGE_TOKEN else MESSAGE_PREFIX
    }

    val comparator = compareBy<SearchResult> { it.rankClass }
        .thenBy { it.relevance }
        .thenByDescending { it.messageTimestamp ?: it.chatTimestamp }
        .thenBy { it.rowId }
}

