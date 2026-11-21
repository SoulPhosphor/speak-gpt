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

import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRow
import org.teslasoft.assistant.preferences.memory.scan.ScanTextSplitter
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

    /** Actual model capacity, independent of the user's soft average. */
    fun effectiveMaximumTokens(configuredMaximum: Int, verifiedContextTokens: Int?, requestHeadroomTokens: Int): Int {
        if (verifiedContextTokens == null) return configuredMaximum.coerceAtLeast(1)
        return minOf(configuredMaximum, (verifiedContextTokens.toLong() - requestHeadroomTokens)
            .coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    fun headroomTokens(completeSystemPrompt: String): Int =
        IncludeTextPolicy.estimateTokens(completeSystemPrompt) +
            EXPECTED_OUTPUT_TOKENS + SAFETY_MARGIN_TOKENS
}

data class ArchivistRequestChunk(
    val rows: List<ScanSourceRow>,
    val estimatedTranscriptTokens: Int
)

/**
 * Memory Scanner source chunks. The average can stretch by a flat allowance
 * to finish an exchange. Only an individual message above the maximum splits,
 * losslessly, at the last paragraph, line or word boundary that fits and only
 * then between whole characters ([ScanTextSplitter]); each fragment keeps its
 * message UUID and offsets. A change of scene always closes the chunk. The
 * chat's messages are never edited.
 */
object ArchivistConversationChunker {
    private data class RowFragment(val row: ScanSourceRow, val tokens: Int)

    fun split(
        rows: List<ScanSourceRow>,
        targetTokens: Int,
        maximumTokens: Int = targetTokens,
        allowanceTokens: Int = 0
    ): List<ArchivistRequestChunk> {
        if (rows.isEmpty()) return emptyList()
        val budget = targetTokens.coerceAtLeast(1)
        val maximum = maximumTokens.coerceAtLeast(1)
        val requestLimit = minOf(maximum.toLong(), budget.toLong() + allowanceTokens.coerceAtLeast(0))
        val fragments = rows.flatMap { splitRow(it, maximum) }
        val out = ArrayList<ArchivistRequestChunk>()
        var current = ArrayList<ScanSourceRow>()
        var currentTokens = 0L
        var currentScene: ArchivistSceneContext? = null

        fun flush() {
            if (current.isEmpty()) return
            out.add(ArchivistRequestChunk(current, currentTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            current = ArrayList()
            currentTokens = 0
            currentScene = null
        }

        fun append(fragment: RowFragment) {
            current.add(fragment.row)
            currentTokens += fragment.tokens
            currentScene = ArchivistSceneContext.from(fragment.row)
        }

        var from = 0
        while (from < fragments.size) {
            val scene = ArchivistSceneContext.from(fragments[from].row)
            var end = from + 1
            while (end < fragments.size && fragments[end].row.isAssistant &&
                ArchivistSceneContext.from(fragments[end].row) == scene) end++
            val exchangeTokens = (from until end).sumOf { fragments[it].tokens.toLong() }
            if (exchangeTokens <= requestLimit) {
                if (current.isNotEmpty() && (currentScene != scene || currentTokens >= budget ||
                        currentTokens + exchangeTokens > requestLimit)) flush()
                // The pair is admitted together. Do not flush at its user/AI boundary.
                for (i in from until end) append(fragments[i])
            } else {
                // The pair alone exceeds average + allowance: separate between
                // messages. A single whole message may exceed the average.
                for (i in from until end) {
                    val fragment = fragments[i]
                    if (current.isNotEmpty() && (scene != currentScene || currentTokens >= budget ||
                            currentTokens + fragment.tokens > requestLimit)) flush()
                    append(fragment)
                }
            }
            from = end
        }
        flush()
        return out
    }

    private fun splitRow(row: ScanSourceRow, maximum: Int): List<RowFragment> {
        val pieces = ScanTextSplitter.split(row.text, maximum)
        return pieces.mapIndexed { index, piece ->
            val part = row.slice(piece.start, piece.end).let {
                if (pieces.size > 1 || row.partCount > 1) {
                    it.copy(
                        partIndex = (row.partIndex - 1) * pieces.size + index + 1,
                        partCount = row.partCount * pieces.size
                    )
                } else it
            }
            RowFragment(part, if (part.text.isBlank()) 0 else IncludeTextPolicy.estimateTokens(part.text))
        }
    }
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
