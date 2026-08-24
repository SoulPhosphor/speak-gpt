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

package org.teslasoft.assistant.util.summarizer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.ProviderLimitKind

/**
 * The summarizer's failure categories — the normative list from
 * conversation-summary-errors.md §2. Each category has exact user-facing
 * wording (title + message in strings.xml, mapped in SummarizerErrorMessages)
 * and is a stable contract: once assigned to a cause it is never reused.
 * Kept free of Android types so classification and the log policy are
 * unit-tested on a plain JVM.
 */
enum class SummarizerErrorCategory {
    MODEL_MISSING,        // 2.1  Summary Model Missing (detected locally)
    SERVICE_UNREACHABLE,  // 2.2  AI Service Unreachable
    CONNECT_TIMEOUT,      // 2.3  Connection Timed Out
    RESPONSE_TIMEOUT,     // 2.4  Response Timed Out
    ACCESS_REJECTED,      // 2.5  Access Rejected
    MODEL_UNAVAILABLE,    // 2.6  Model Unavailable (reported by the endpoint)
    RATE_LIMIT,           // 2.7  Rate Limit Reached
    QUOTA,                // 2.8  Quota Reached
    REQUEST_TOO_LARGE,    // 2.9  Summary Request Too Large
    CONTENT_REJECTED,     // 2.10 Summary Request Rejected
    SERVICE_ERROR,        // 2.11 AI Service Error
    RESPONSE_UNREADABLE,  // 2.12 Summary Response Unreadable
    SAVE_FAILED,          // 2.13 Summary Couldn't Be Saved
    UNEXPECTED            // 2.14 Unexpected Summarizer Error
}

/**
 * One stored entry of a chat's Summarizer Errors log (at most five per chat).
 * [count] is the repetition count for an ongoing failure episode — repeated
 * retries of the same category update the newest matching entry instead of
 * filling the log with duplicates (errors doc §3).
 */
data class SummarizerErrorEntry(
    val category: String,
    val timestamp: Long,
    val count: Int,
    val profile: String,
    val model: String,
    val detail: String?
) {
    fun categoryEnum(): SummarizerErrorCategory? = try {
        SummarizerErrorCategory.valueOf(category)
    } catch (_: Exception) {
        null
    }
}

/**
 * Pure policy for the per-chat error log and the failure-episode rules
 * (errors doc §3): five entries, newest first; while the same category keeps
 * failing without a successful fold-in in between, the newest entry of that
 * category is updated (timestamp + repetition count) instead of duplicated;
 * the dedicated sound plays only when an episode starts.
 */
object SummarizerErrorLog {

    const val MAX_ENTRIES = 5

    private val gson = Gson()

    fun fromJson(json: String?): List<SummarizerErrorEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = TypeToken.getParameterized(
                ArrayList::class.java, SummarizerErrorEntry::class.java
            ).type
            gson.fromJson<ArrayList<SummarizerErrorEntry>>(json, type) ?: arrayListOf()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toJson(entries: List<SummarizerErrorEntry>): String = gson.toJson(entries)

    data class RecordResult(
        val entries: List<SummarizerErrorEntry>,
        /** True when this failure starts a new episode — play the sound once. */
        val newEpisode: Boolean
    )

    /**
     * Records a failure. [activeEpisode] is the category name of the ongoing
     * failure episode ("" when the last fold-in succeeded). A failure of the
     * same category merges into the newest matching entry; a different
     * category — or the first failure after a success — begins a new episode
     * and a new entry, evicting the oldest entry beyond [MAX_ENTRIES].
     */
    fun record(
        current: List<SummarizerErrorEntry>,
        activeEpisode: String,
        category: SummarizerErrorCategory,
        timestamp: Long,
        profile: String,
        model: String,
        detail: String?
    ): RecordResult {
        val sameEpisode = activeEpisode == category.name
        if (sameEpisode) {
            val index = current.indexOfFirst { it.category == category.name }
            if (index >= 0) {
                val updated = current.toMutableList()
                val entry = updated[index]
                updated[index] = entry.copy(
                    timestamp = timestamp,
                    count = entry.count + 1,
                    profile = profile,
                    model = model,
                    detail = detail ?: entry.detail
                )
                return RecordResult(updated, newEpisode = false)
            }
            // The episode's entry was deleted by the user; a fresh failure
            // creates a new entry and may play the sound again (§3).
        }
        val entry = SummarizerErrorEntry(
            category = category.name,
            timestamp = timestamp,
            count = 1,
            profile = profile,
            model = model,
            detail = detail
        )
        val updated = (listOf(entry) + current).take(MAX_ENTRIES)
        return RecordResult(updated, newEpisode = true)
    }
}

/**
 * Maps a transport/provider failure — classified by the app's shared
 * [org.teslasoft.assistant.util.GenerationErrorClassifier] — onto the
 * summarizer's failure categories. Local conditions (missing model, blank
 * response, failed save) are produced directly by the engine and never pass
 * through here.
 */
object SummarizerErrorClassifier {

    fun categorize(result: GenErrorResult): SummarizerErrorCategory {
        // Provider-confirmed limits carry the finest signal — use them first.
        when (result.providerLimit) {
            ProviderLimitKind.MODEL_CONTEXT,
            ProviderLimitKind.MODEL_INPUT,
            ProviderLimitKind.REQUEST_BODY -> return SummarizerErrorCategory.REQUEST_TOO_LARGE
            ProviderLimitKind.RATE_OR_THROUGHPUT -> return SummarizerErrorCategory.RATE_LIMIT
            ProviderLimitKind.QUOTA_OR_SPENDING,
            ProviderLimitKind.OUT_OF_CREDITS -> return SummarizerErrorCategory.QUOTA
            ProviderLimitKind.UNIDENTIFIED, null -> { /* fall through */ }
        }
        // A 403 is a permission refusal even when the shared classifier could
        // not name it more precisely.
        if (result.httpStatus == 403) return SummarizerErrorCategory.ACCESS_REJECTED

        return when (result.code) {
            GenErrorCode.N1, GenErrorCode.N3 -> SummarizerErrorCategory.SERVICE_UNREACHABLE
            GenErrorCode.N2 -> SummarizerErrorCategory.CONNECT_TIMEOUT
            GenErrorCode.N4 -> SummarizerErrorCategory.RESPONSE_TIMEOUT
            GenErrorCode.C1 -> SummarizerErrorCategory.UNEXPECTED
            GenErrorCode.A1, GenErrorCode.A2 -> SummarizerErrorCategory.ACCESS_REJECTED
            GenErrorCode.M1, GenErrorCode.M2 -> SummarizerErrorCategory.MODEL_UNAVAILABLE
            GenErrorCode.M3 -> SummarizerErrorCategory.REQUEST_TOO_LARGE
            GenErrorCode.M4 -> SummarizerErrorCategory.SERVICE_ERROR
            GenErrorCode.Q1 -> SummarizerErrorCategory.RATE_LIMIT
            GenErrorCode.S1 -> SummarizerErrorCategory.SERVICE_ERROR
            GenErrorCode.S2 -> SummarizerErrorCategory.RESPONSE_UNREADABLE
            GenErrorCode.S3, GenErrorCode.S4, GenErrorCode.S5 ->
                SummarizerErrorCategory.CONTENT_REJECTED
            GenErrorCode.S6 -> SummarizerErrorCategory.SERVICE_ERROR
            GenErrorCode.U0 ->
                if (result.httpStatus != null && result.httpStatus >= 400) {
                    SummarizerErrorCategory.SERVICE_ERROR
                } else {
                    SummarizerErrorCategory.UNEXPECTED
                }
        }
    }
}

/**
 * Word-count policy for saved summaries (owner ruling, July 29 2026): the
 * configured Summary Length is not a hard limit because models count words
 * unreliably. The app counts the returned words itself, tolerates up to 10%
 * over, and beyond that saves the text unchanged but flags it over-length so
 * the next regular fold-in compresses it back toward the limit. Never a
 * separate corrective call, never truncation, never a discard.
 */
object SummarizerLengthPolicy {

    fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    fun allowedWords(configuredLength: Int): Int =
        configuredLength + configuredLength / 10

    fun isOverLength(text: String, configuredLength: Int): Boolean =
        wordCount(text) > allowedWords(configuredLength)
}

/**
 * Strips credential-shaped material from provider/technical detail before it
 * is stored in a Summarizer Errors entry or the app-wide Error Log (errors
 * doc §1: never display or copy an API key, authorization header, complete
 * request body, conversation text, or summary text).
 */
object SummarizerDetailSanitizer {

    private const val MAX_DETAIL_CHARS = 4000

    private val patterns = listOf(
        Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]{8,}"""),
        Regex("""(?i)(authorization|x-api-key|api-key)\s*[:=]\s*\S+"""),
        Regex("""sk-[A-Za-z0-9._\-]{8,}""")
    )

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var out: String = raw
        for (p in patterns) out = out.replace(p, "[removed]")
        if (out.length > MAX_DETAIL_CHARS) out = out.take(MAX_DETAIL_CHARS) + "…"
        return out.trim().ifBlank { null }
    }
}
