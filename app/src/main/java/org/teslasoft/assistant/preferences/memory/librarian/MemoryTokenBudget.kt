package org.teslasoft.assistant.preferences.memory.librarian

/** The same complete retrieval document is measured for display, saving and proposals. */
object MemoryTokenBudget {
    data class Input(val content: String, val embeddingText: String? = null, val tags: List<String> = emptyList()) {
        fun document(): String = RetrievalDocument.semanticDocument(content, embeddingText, tags)

        companion object {
            fun from(record: org.teslasoft.assistant.preferences.memory.MemoryRecord): Input =
                Input(record.content, record.embeddingText, RetrievalDocument.parseTags(record.tagsJson))
        }
    }

    data class Usage(val tokens: Int, val limit: Int) {
        init { require(tokens >= 0 && limit > 0) }
        val fits: Boolean get() = tokens <= limit
        val percent: Int get() = (tokens.toLong() * 100 / limit).coerceIn(0, 100).toInt()
    }

    /** [countDocument] includes the model prefix and tokenizer's special tokens. */
    class Reader(val modelKey: String, val limit: Int, private val countDocument: (String) -> Int) {
        fun measure(input: Input): Usage = Usage(countDocument(input.document()), limit)
    }

    /** Informational only: whitespace-delimited words, including Unicode whitespace. */
    fun words(content: String): Int {
        var count = 0
        var inWord = false
        content.codePoints().forEach { code ->
            val space = Character.isWhitespace(code) || Character.isSpaceChar(code)
            if (!space && !inWord) count++
            inWord = !space
        }
        return count
    }
}
