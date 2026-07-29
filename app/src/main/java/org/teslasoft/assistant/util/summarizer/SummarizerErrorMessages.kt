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

import android.content.Context
import org.teslasoft.assistant.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android-side mapping from a [SummarizerErrorCategory] to its exact
 * user-facing title and message (conversation-summary-errors.md §2), plus
 * the rendering of stored entries for the Summarizer Errors dialog and its
 * Copy action. Kept separate so the classification/log policy stays pure.
 */
object SummarizerErrorMessages {

    fun titleRes(category: SummarizerErrorCategory): Int = when (category) {
        SummarizerErrorCategory.MODEL_MISSING -> R.string.summarizer_err_model_missing_title
        SummarizerErrorCategory.SERVICE_UNREACHABLE -> R.string.summarizer_err_unreachable_title
        SummarizerErrorCategory.CONNECT_TIMEOUT -> R.string.summarizer_err_connect_timeout_title
        SummarizerErrorCategory.RESPONSE_TIMEOUT -> R.string.summarizer_err_response_timeout_title
        SummarizerErrorCategory.ACCESS_REJECTED -> R.string.summarizer_err_access_rejected_title
        SummarizerErrorCategory.MODEL_UNAVAILABLE -> R.string.summarizer_err_model_unavailable_title
        SummarizerErrorCategory.RATE_LIMIT -> R.string.summarizer_err_rate_limit_title
        SummarizerErrorCategory.QUOTA -> R.string.summarizer_err_quota_title
        SummarizerErrorCategory.REQUEST_TOO_LARGE -> R.string.summarizer_err_too_large_title
        SummarizerErrorCategory.CONTENT_REJECTED -> R.string.summarizer_err_rejected_title
        SummarizerErrorCategory.SERVICE_ERROR -> R.string.summarizer_err_service_title
        SummarizerErrorCategory.RESPONSE_UNREADABLE -> R.string.summarizer_err_unreadable_title
        SummarizerErrorCategory.SAVE_FAILED -> R.string.summarizer_err_save_title
        SummarizerErrorCategory.UNEXPECTED -> R.string.summarizer_err_unexpected_title
    }

    fun messageRes(category: SummarizerErrorCategory): Int = when (category) {
        SummarizerErrorCategory.MODEL_MISSING -> R.string.summarizer_err_model_missing
        SummarizerErrorCategory.SERVICE_UNREACHABLE -> R.string.summarizer_err_unreachable
        SummarizerErrorCategory.CONNECT_TIMEOUT -> R.string.summarizer_err_connect_timeout
        SummarizerErrorCategory.RESPONSE_TIMEOUT -> R.string.summarizer_err_response_timeout
        SummarizerErrorCategory.ACCESS_REJECTED -> R.string.summarizer_err_access_rejected
        SummarizerErrorCategory.MODEL_UNAVAILABLE -> R.string.summarizer_err_model_unavailable
        SummarizerErrorCategory.RATE_LIMIT -> R.string.summarizer_err_rate_limit
        SummarizerErrorCategory.QUOTA -> R.string.summarizer_err_quota
        SummarizerErrorCategory.REQUEST_TOO_LARGE -> R.string.summarizer_err_too_large
        SummarizerErrorCategory.CONTENT_REJECTED -> R.string.summarizer_err_rejected
        SummarizerErrorCategory.SERVICE_ERROR -> R.string.summarizer_err_service
        SummarizerErrorCategory.RESPONSE_UNREADABLE -> R.string.summarizer_err_unreadable
        SummarizerErrorCategory.SAVE_FAILED -> R.string.summarizer_err_save
        SummarizerErrorCategory.UNEXPECTED -> R.string.summarizer_err_unexpected
    }

    /** Date and 12-hour time without seconds (errors addendum §1). */
    fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.getDefault()).format(Date(timestamp))

    /**
     * One entry rendered as plain text, in the normative order: timestamp,
     * Title Caps title, exact message, endpoint profile and model, sanitized
     * detail, repetition count.
     */
    fun renderEntry(context: Context, entry: SummarizerErrorEntry): String {
        val category = entry.categoryEnum() ?: SummarizerErrorCategory.UNEXPECTED
        val sb = StringBuilder()
        sb.append(formatTimestamp(entry.timestamp)).append('\n')
        sb.append(context.getString(titleRes(category))).append('\n')
        sb.append(context.getString(messageRes(category))).append('\n')
        sb.append(context.getString(R.string.summarizer_errors_profile_line, entry.profile)).append('\n')
        sb.append(context.getString(R.string.summarizer_errors_model_line, entry.model))
        entry.detail?.takeIf { it.isNotBlank() }?.let { sb.append('\n').append(it) }
        if (entry.count > 1) {
            sb.append('\n').append(context.getString(R.string.summarizer_errors_repeated, entry.count))
        }
        return sb.toString()
    }

    /** The full dialog content as copyable text: status paragraph + entries. */
    fun renderLog(
        context: Context,
        statusParagraph: String,
        entries: List<SummarizerErrorEntry>
    ): String {
        val sb = StringBuilder(statusParagraph)
        for (entry in entries) {
            sb.append("\n\n").append(renderEntry(context, entry))
        }
        return sb.toString()
    }
}
