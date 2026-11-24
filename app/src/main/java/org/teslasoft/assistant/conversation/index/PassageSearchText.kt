/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import org.teslasoft.assistant.preferences.memory.librarian.HfTokenizer
import org.teslasoft.assistant.preferences.memory.librarian.KeywordText

/**
 * How a passage's search text is handed to the embedding model and measured
 * (memory development plan G.5b cutter rules 3, 4 and 7).
 *
 * The model input is the stored-text instruction prefix with the passage's
 * title (by default the speaker label, [SpeakerTitlePolicy]) in the model's
 * title slot, followed by the search text. [count]
 * measures that complete input, special tokens included, with the model's
 * own tokenizer. [hardLimit] is the model's stored-text limit.
 */
class SearchTextReader(
    val vectorModel: String,
    val vectorDimensions: Int,
    val hardLimit: Int,
    private val prefixFor: (String) -> String,
    private val countModelInput: (String) -> Int
) {
    init {
        require(hardLimit >= 1) { "hardLimit must be at least 1" }
    }

    /** The complete model input: the prefix with [title] in the title slot, then the search text. */
    fun modelInput(title: String, searchText: String): String = prefixFor(checkedTitle(title)) + searchText

    fun count(title: String, searchText: String): Int = countModelInput(modelInput(title, searchText))

    /** Changes whenever the model, its limit or the prefix form change, so passages are cut again. */
    val key: String = keyOf(vectorModel, vectorDimensions, hardLimit, prefixFor)

    companion object {
        private const val EMPTY_TITLE = "title: none"
        private const val KEY_PROBE = "\u0000"

        private fun keyOf(vectorModel: String, vectorDimensions: Int, hardLimit: Int, prefixFor: (String) -> String) =
            listOf(
                vectorModel,
                vectorDimensions.toString(),
                hardLimit.toString(),
                IndexFingerprint.of(prefixFor(KEY_PROBE))
            ).joinToString("|")

        private fun prefixOf(model: EmbeddingModels.Model): (String) -> String =
            { title -> model.documentPrefix.replaceFirst(EMPTY_TITLE, "title: $title") }

        /**
         * The [key] of [forEmbeddingModel]'s reader for [model], worked out
         * without loading its tokenizer (the settings status, Step 11).
         */
        fun keyFor(model: EmbeddingModels.Model): String =
            keyOf(model.embeddingTag, model.dimensions, model.storedTextMaxTokens, prefixOf(model))

        /** A title is one plain line without the slot separator. */
        fun checkedTitle(title: String): String {
            require(title.isNotBlank() && title.none { it == '|' || it == '\n' || it == '\r' }) {
                "speaker title must be one plain line without '|'"
            }
            return title
        }

        /**
         * The real reader for an embedding model: its tokenizer, its
         * stored-text limit, and its document prefix with each passage's
         * title in the title slot.
         */
        fun forEmbeddingModel(model: EmbeddingModels.Model, tokenizer: HfTokenizer): SearchTextReader {
            require(model.documentPrefix.contains(EMPTY_TITLE)) {
                "${model.id} has no title slot in its document prefix"
            }
            return SearchTextReader(
                vectorModel = model.embeddingTag,
                vectorDimensions = model.dimensions,
                hardLimit = model.storedTextMaxTokens,
                prefixFor = prefixOf(model),
                countModelInput = tokenizer::countTokens
            )
        }

        /** The reader for the installed embedding model, or null when none is installed. Off the main thread. */
        fun forInstalledModel(context: Context): SearchTextReader? {
            val model = EmbeddingModelStorage.activeModel(context) ?: return null
            return forEmbeddingModel(model, EmbeddingTokenCounter.tokenizer(context, model))
        }
    }
}

/** SHA-256 fingerprints of original text and model input. */
object IndexFingerprint {
    fun of(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * One passage of a message with its search text (G.5, "Two texts for every
 * passage"). The original text is [text] `[start, end)` of the message; the
 * search text is `[searchStart, end)` of the same message: the passage itself,
 * preceded by the last words of the passage before it in the same message
 * when they fit (cutter rule 5). The search text is held in memory only;
 * just its fingerprint is stored.
 */
data class SearchPassage(
    val order: Int,
    val start: Int,
    val end: Int,
    val searchStart: Int,
    val searchText: String,
    val modelInput: String,
    val searchFingerprint: String,
    val searchTokens: Int,
    /** The passage's own informative words, space separated, each once. */
    val keywords: String
)

/** The passages of one message with their search texts, and the cutter version that made them. */
data class SearchPassages(val cutterVersion: Int, val passages: List<SearchPassage>)

/** Cuts one message and builds each passage's search text (G.5b cutter rules 5 and 7). */
object PassageSearchText {

    /**
     * @param targetTokens the cutter's target size, measured on the complete
     *   model input; at most [SearchTextReader.hardLimit].
     * @param overlapWords the most words carried from the previous passage of
     *   the same message into a search text. Words never come from another
     *   message (cutter rule 6 is not built).
     */
    fun build(
        text: String,
        title: String,
        reader: SearchTextReader,
        targetTokens: Int,
        overlapWords: Int
    ): SearchPassages {
        require(targetTokens in 1..reader.hardLimit) { "targetTokens must be within the model's limit" }
        require(overlapWords >= 0) { "overlapWords must not be negative" }
        require(reader.count(title, "") < targetTokens) { "targetTokens must leave room after the prefix" }
        val cut = PassageCutter.cut(text, targetTokens) { reader.count(title, it) }
        val passages = cut.passages.mapIndexed { order, span ->
            val searchStart = if (order == 0) span.start else overlapStart(
                text, cut.passages[order - 1], span, title, reader, overlapWords
            )
            val searchText = text.substring(searchStart, span.end)
            val modelInput = reader.modelInput(title, searchText)
            SearchPassage(
                order = order,
                start = span.start,
                end = span.end,
                searchStart = searchStart,
                searchText = searchText,
                modelInput = modelInput,
                searchFingerprint = IndexFingerprint.of(modelInput),
                searchTokens = reader.count(title, searchText),
                keywords = KeywordText.terms(text.substring(span.start, span.end)).distinct().joinToString(" ")
            )
        }
        return SearchPassages(cut.cutterVersion, passages)
    }

    /**
     * Where the overlap begins: the start of the earliest carried word whose
     * search text was measured to fit the limit. The most words that fit are
     * found by halving, so a large overlap costs a few measurements, not one
     * per word; only a measured fit is ever used.
     */
    private fun overlapStart(
        text: String,
        previous: PassageSpan,
        current: PassageSpan,
        title: String,
        reader: SearchTextReader,
        overlapWords: Int
    ): Int {
        if (overlapWords == 0) return current.start
        val starts = wordStarts(text, previous.start, previous.end)
        fun startFor(carried: Int) = starts[starts.size - carried]
        fun fits(carried: Int) =
            reader.count(title, text.substring(startFor(carried), current.end)) <= reader.hardLimit
        var tooMany = minOf(overlapWords, starts.size)
        if (tooMany == 0) return current.start
        if (fits(tooMany)) return startFor(tooMany)
        var fitting = 0
        while (tooMany - fitting > 1) {
            val middle = (fitting + tooMany) ushr 1
            if (fits(middle)) fitting = middle else tooMany = middle
        }
        return if (fitting == 0) current.start else startFor(fitting)
    }

    /** Start of each word (a platform word segment holding a letter or digit) inside [start, end). */
    private fun wordStarts(text: String, start: Int, end: Int): List<Int> {
        val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
        val starts = ArrayList<Int>()
        var segmentStart = if (words.isBoundary(start)) start else words.preceding(start)
        while (segmentStart != BreakIterator.DONE && segmentStart < end) {
            val segmentEnd = words.following(segmentStart).let { if (it == BreakIterator.DONE) text.length else it }
            if (segmentStart >= start && hasLetterOrDigit(text, segmentStart, minOf(segmentEnd, end))) {
                starts.add(segmentStart)
            }
            segmentStart = segmentEnd.takeIf { it < text.length } ?: BreakIterator.DONE
        }
        return starts
    }

    private fun hasLetterOrDigit(text: String, start: Int, end: Int): Boolean {
        var index = start
        while (index < end) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }
}
