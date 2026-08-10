/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.memory.archivist

import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import org.teslasoft.assistant.preferences.memory.TranscriptRecord
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.util.ProviderLimitKind

/** Approved transcript-token choices for one Memory Assistant request. */
object ArchivistRequestBudget {
    const val CHOICE_AUTO = "auto"
    const val CHOICE_SMALL = "small"
    const val CHOICE_STANDARD = "standard"
    const val CHOICE_LARGE = "large"
    const val CHOICE_CUSTOM = "custom"

    const val SMALL_TOKENS = 4_000
    const val STANDARD_TOKENS = 8_000
    const val LARGE_TOKENS = 16_000
    const val CUSTOM_MIN_TOKENS = 1_000
    const val CUSTOM_SUGGESTED_TOKENS = 8_000

    /** A verified small context may require less than the selectable minimum. */
    const val MIN_RUNTIME_TRANSCRIPT_TOKENS = 256

    /** Space that is not part of the transcript target. The complete system
     * prompt passed to [headroomTokens] already contains Archivist instructions,
     * current Memory Types, target catalog, existing-memory context,
     * same-run candidates, Analysis Note, and response-schema instructions. */
    const val EXPECTED_OUTPUT_TOKENS = 2_048
    const val SAFETY_MARGIN_TOKENS = 1_024

    fun normalizeChoice(value: String?): String = when (value) {
        CHOICE_SMALL, CHOICE_STANDARD, CHOICE_LARGE, CHOICE_CUSTOM -> value
        else -> CHOICE_AUTO
    }

    fun validateCustomTarget(value: Int?): Int? =
        value?.takeIf { it >= CUSTOM_MIN_TOKENS }

    /** Resolve the effective transcript target. A missing verified context
     * never creates a hidden giant limit: Auto falls back to Standard. */
    fun effectiveTranscriptTarget(
        choice: String,
        customTokens: Int?,
        verifiedContextTokens: Int?,
        requestHeadroomTokens: Int
    ): Int {
        val normalized = normalizeChoice(choice)
        val selected = when (normalized) {
            CHOICE_SMALL -> SMALL_TOKENS
            CHOICE_LARGE -> LARGE_TOKENS
            CHOICE_CUSTOM -> validateCustomTarget(customTokens) ?: CUSTOM_SUGGESTED_TOKENS
            CHOICE_AUTO -> if (verifiedContextTokens == null) {
                STANDARD_TOKENS
            } else {
                (verifiedContextTokens - requestHeadroomTokens)
                    .coerceIn(MIN_RUNTIME_TRANSCRIPT_TOKENS, LARGE_TOKENS)
            }
            else -> STANDARD_TOKENS
        }
        if (verifiedContextTokens == null) return selected
        val safe = (verifiedContextTokens - requestHeadroomTokens)
            .coerceAtLeast(MIN_RUNTIME_TRANSCRIPT_TOKENS)
        return minOf(selected, safe)
    }

    fun headroomTokens(completeSystemPrompt: String): Int =
        IncludeTextPolicy.estimateTokens(completeSystemPrompt) +
            EXPECTED_OUTPUT_TOKENS + SAFETY_MARGIN_TOKENS
}

data class ArchivistRequestChunk(
    val transcripts: List<TranscriptRecord>,
    val estimatedTranscriptTokens: Int
)

/**
 * Token-targeted request chunks. Whole messages remain intact whenever they
 * fit. An individually oversized message is split first at paragraph breaks,
 * then sentence breaks, with a hard token-bounded cut only when the text has no
 * usable natural boundary. Captured scene changes always close the chunk.
 */
object ArchivistConversationChunker {
    private const val MESSAGE_OVERHEAD_TOKENS = 4

    private data class RowFragment(
        val transcript: TranscriptRecord,
        val tokens: Int
    )

    fun split(
        transcripts: List<TranscriptRecord>,
        targetTokens: Int
    ): List<ArchivistRequestChunk> {
        if (transcripts.isEmpty()) return emptyList()
        val budget = targetTokens.coerceAtLeast(ArchivistRequestBudget.MIN_RUNTIME_TRANSCRIPT_TOKENS)
        val fragments = transcripts.flatMap { splitRow(it, budget) }
        val out = ArrayList<ArchivistRequestChunk>()
        var current = ArrayList<TranscriptRecord>()
        var currentTokens = 0
        var currentScene: ArchivistSceneContext? = null

        fun flush() {
            if (current.isEmpty()) return
            out.add(ArchivistRequestChunk(current, currentTokens))
            current = ArrayList()
            currentTokens = 0
            currentScene = null
        }

        for (fragment in fragments) {
            val scene = ArchivistSceneContext.from(fragment.transcript)
            if (current.isNotEmpty() &&
                (scene != currentScene || currentTokens + fragment.tokens > budget)
            ) {
                flush()
            }
            current.add(fragment.transcript)
            currentTokens += fragment.tokens
            currentScene = scene
        }
        flush()
        return out
    }

    private fun splitRow(row: TranscriptRecord, budget: Int): List<RowFragment> {
        val turns = try {
            JSONArray(row.content)
        } catch (_: Exception) {
            null
        }
        if (turns == null) {
            return splitOversizedText(row.content, budget).map { part ->
                RowFragment(row.copy(content = part), IncludeTextPolicy.estimateTokens(part))
            }
        }

        val pieces = ArrayList<RowFragment>()
        var pending = JSONArray()
        var pendingTokens = 0

        fun flushPending() {
            if (pending.length() == 0) return
            pieces.add(RowFragment(row.copy(content = pending.toString()), pendingTokens))
            pending = JSONArray()
            pendingTokens = 0
        }

        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val content = turn.optString("content")
            val tokenCost = IncludeTextPolicy.estimateTokens(content) + MESSAGE_OVERHEAD_TOKENS
            if (tokenCost <= budget) {
                if (pending.length() > 0 && pendingTokens + tokenCost > budget) flushPending()
                pending.put(JSONObject(turn.toString()))
                pendingTokens += tokenCost
                continue
            }

            flushPending()
            val contentBudget = (budget - MESSAGE_OVERHEAD_TOKENS)
                .coerceAtLeast(ArchivistRequestBudget.MIN_RUNTIME_TRANSCRIPT_TOKENS / 2)
            for (part in splitOversizedText(content, contentBudget)) {
                val splitTurn = JSONObject(turn.toString()).put("content", part)
                pieces.add(
                    RowFragment(
                        row.copy(content = JSONArray().put(splitTurn).toString()),
                        IncludeTextPolicy.estimateTokens(part) + MESSAGE_OVERHEAD_TOKENS
                    )
                )
            }
        }
        flushPending()
        if (pieces.isEmpty()) pieces.add(RowFragment(row.copy(content = "[]"), 0))
        return pieces
    }

    private fun splitOversizedText(text: String, budgetTokens: Int): List<String> {
        if (text.isEmpty() || IncludeTextPolicy.estimateTokens(text) <= budgetTokens) {
            return listOf(text)
        }
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.substring(start)
            if (IncludeTextPolicy.estimateTokens(remaining) <= budgetTokens) {
                out.add(remaining)
                break
            }
            val hardEnd = largestFittingEnd(text, start, budgetTokens)
            val paragraphEnd = lastBoundaryAtOrBefore(text, start, hardEnd, PARAGRAPH_BREAK)
            val sentenceEnd = if (paragraphEnd == null) {
                lastBoundaryAtOrBefore(text, start, hardEnd, SENTENCE_BREAK)
            } else null
            val end = paragraphEnd ?: sentenceEnd ?: hardEnd
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    private fun largestFittingEnd(text: String, start: Int, budgetTokens: Int): Int {
        var low = start + 1
        var high = text.length
        var best = low
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (IncludeTextPolicy.estimateTokens(text.substring(start, middle)) <= budgetTokens) {
                best = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best.coerceAtMost(text.length)
    }

    private fun lastBoundaryAtOrBefore(
        text: String,
        start: Int,
        hardEnd: Int,
        pattern: Regex
    ): Int? = pattern.findAll(text, start)
        .map { it.range.last + 1 }
        .takeWhile { it <= hardEnd }
        .lastOrNull()
        ?.takeIf { it > start }

    private val PARAGRAPH_BREAK = Regex("(?:\\r?\\n){2,}")
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
}

class ArchivistShrinkRequiredException(
    val nextTargetTokens: Int,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

object ArchivistRetryPolicy {
    const val MAX_SHRINK_DEPTH = 2

    fun isVerifiedContextRejection(error: Throwable): Boolean =
        isVerifiedContextRejection(error, null)

    fun isVerifiedContextRejection(error: Throwable, providerBody: String?): Boolean {
        if (error is ArchivistShrinkRequiredException) return true
        val classified = GenerationErrorClassifier.classify(error).providerLimit
        val bodyClassified = providerBody?.takeIf { it.isNotBlank() }?.let {
            GenerationErrorClassifier.classify(IllegalStateException(it)).providerLimit
        }
        return when (classified ?: bodyClassified) {
            ProviderLimitKind.MODEL_CONTEXT,
            ProviderLimitKind.MODEL_INPUT,
            ProviderLimitKind.REQUEST_BODY -> true
            else -> false
        }
    }

    fun looksClearlyTruncated(raw: String, finishReason: String?): Boolean {
        if (finishReason.equals("length", ignoreCase = true)) return true
        val text = raw.trim().removePrefix("```json").removePrefix("```").trim()
        if (!text.startsWith("{") && !text.startsWith("[")) return false
        var braces = 0
        var brackets = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && quoted) {
                escaped = true
                continue
            }
            if (char == '"') {
                quoted = !quoted
                continue
            }
            if (quoted) continue
            when (char) {
                '{' -> braces++
                '}' -> braces--
                '[' -> brackets++
                ']' -> brackets--
            }
        }
        return quoted || braces > 0 || brackets > 0
    }
}

/** Pure bounded shrink executor used by production and deterministic tests. */
object ArchivistBoundedShrinkExecutor {
    private data class Work<C>(val value: C, val depth: Int)

    suspend fun <C, O> execute(
        initial: C,
        maxDepth: Int = ArchivistRetryPolicy.MAX_SHRINK_DEPTH,
        analyze: suspend (C, List<O>) -> O,
        shouldShrink: (Throwable) -> Boolean,
        shrink: (C, Throwable) -> List<C>
    ): List<O> {
        val queue = ArrayDeque<Work<C>>()
        queue.add(Work(initial, 0))
        val outputs = ArrayList<O>()
        while (queue.isNotEmpty()) {
            val work = queue.removeFirst()
            try {
                outputs.add(analyze(work.value, outputs.toList()))
            } catch (error: Exception) {
                if (!shouldShrink(error) || work.depth >= maxDepth) throw error
                val smaller = shrink(work.value, error)
                if (smaller.isEmpty() || (smaller.size == 1 && smaller.first() == work.value)) {
                    throw error
                }
                for (index in smaller.indices.reversed()) {
                    queue.addFirst(Work(smaller[index], work.depth + 1))
                }
            }
        }
        return outputs
    }
}
