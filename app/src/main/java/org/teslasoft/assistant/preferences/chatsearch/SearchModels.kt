package org.teslasoft.assistant.preferences.chatsearch

enum class SearchDocumentKind { TITLE, MESSAGE }

data class SearchOptions(
    val wholeWords: Boolean = false,
    val matchCase: Boolean = false
)

data class SearchMatch(
    val ranges: List<IntRange>,
    val allTokensExact: Boolean
)

data class SearchDocument(
    val chatId: String,
    val documentKey: String,
    val kind: SearchDocumentKind,
    val rawText: String,
    val indexText: String,
    val contentFingerprint: String,
    val sourceRevision: String?,
    val chatTimestamp: Long,
    val messageTimestamp: Long?,
    val messageId: String?,
    val legacyOrdinal: Int?,
    val legacyRole: String?
)

data class SearchCandidate(
    val rowId: Long,
    val document: SearchDocument,
    val relevance: Double
)

data class SearchResult(
    val rowId: Long,
    val chatId: String,
    val chatName: String,
    val chatTitle: String,
    val chatPinned: Boolean,
    val kind: SearchDocumentKind,
    val matchedText: String,
    val highlightRanges: List<IntRange>,
    val messageTimestamp: Long?,
    val messageId: String?,
    val legacyOrdinal: Int?,
    val legacyRole: String?,
    val contentFingerprint: String,
    val rankClass: Int,
    val relevance: Double,
    val chatTimestamp: Long
)

enum class SearchCorpusState { PREPARING, READY, INCOMPLETE, UNAVAILABLE }

data class SearchHealth(
    val state: SearchCorpusState,
    val skippedChatCount: Int = 0,
    val detail: String? = null
)

data class SearchPage(
    val results: List<SearchResult>,
    val hasMore: Boolean,
    val health: SearchHealth,
    val nextCandidateOffset: Int = 0
)
