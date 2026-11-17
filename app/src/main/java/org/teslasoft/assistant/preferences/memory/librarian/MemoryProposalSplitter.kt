package org.teslasoft.assistant.preferences.memory.librarian

/** Splits generated proposals only. No rewriting, trimming, overlap or discarded suffix. */
object MemoryProposalSplitter {
    data class Part(val content: String, val tags: List<String>)

    fun split(content: String, tags: List<String>, reader: MemoryTokenBudget.Reader): List<Part> {
        val result = ArrayList<Part>()
        fun fits(text: String, labels: List<String>) =
            reader.measure(MemoryTokenBudget.Input(text, tags = labels)).fits

        fun divide(text: String, labels: List<String>) {
            if (fits(text, labels)) {
                result.add(Part(text, labels))
                return
            }
            // Metadata can consume most of the budget too. Partition oversized
            // tag sets without dropping labels; each group retains the content.
            val firstEnd = if (text.isEmpty()) 0 else Character.charCount(text.codePointAt(0))
            if (!fits(text.substring(0, firstEnd), labels) && labels.size > 1) {
                val middle = labels.size / 2
                divide(text, labels.take(middle))
                divide(text, labels.drop(middle))
                return
            }
            require(text.codePointCount(0, text.length) > 1) {
                "Embedding model cannot read a proposal character with its metadata"
            }
            var middle = text.offsetByCodePoints(0, text.codePointCount(0, text.length) / 2)
            // Prefer a word boundary, retaining the separator on the first part.
            val boundary = (middle downTo maxOf(1, middle / 2)).firstOrNull {
                text[it - 1].isWhitespace()
            }
            if (boundary != null) middle = boundary
            divide(text.substring(0, middle), labels)
            divide(text.substring(middle), labels)
        }
        divide(content, tags.toList())
        return result
    }
}
