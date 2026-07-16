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

package org.teslasoft.assistant.util

import android.content.Context
import org.teslasoft.assistant.R

/**
 * Android-side mapping from a classified [GenErrorCode] to its user-facing chat
 * message. Kept separate from [GenerationErrorClassifier] so the classifier
 * stays pure and unit-testable; this half is the only part that touches `R` and
 * a [Context]. The neutral sentences live in strings.xml; the stable `[code]`
 * prefix is prepended here so the wording and the code never drift apart.
 */
fun GenErrorCode.messageRes(): Int = when (this) {
    GenErrorCode.N1 -> R.string.gen_error_n1
    GenErrorCode.N2 -> R.string.gen_error_n2
    GenErrorCode.N3 -> R.string.gen_error_n3
    GenErrorCode.A1 -> R.string.gen_error_a1
    GenErrorCode.M1 -> R.string.gen_error_m1
    GenErrorCode.M2 -> R.string.gen_error_m2
    GenErrorCode.M3 -> R.string.gen_error_m3
    GenErrorCode.Q1 -> R.string.gen_error_q1
    GenErrorCode.S1 -> R.string.gen_error_s1
    GenErrorCode.S2 -> R.string.gen_error_s2
    GenErrorCode.S3 -> R.string.gen_error_s3
    GenErrorCode.S4 -> R.string.gen_error_s4
    GenErrorCode.U0 -> R.string.gen_error_u0
}

/** The exact text shown in chat: `[N1] <neutral sentence>`. No profile, Base
 *  URL, model, or stack trace — those go only to the Error Log. */
fun GenErrorResult.chatMessage(context: Context): String {
    val timeout = configuredTimeoutMillis?.let(::formatTimeoutDuration)
    val message = when (kind) {
        GenFailureKind.CONNECT_TIMEOUT -> if (timeout != null) {
            context.getString(R.string.gen_error_n2_connect_duration, timeout)
        } else {
            context.getString(R.string.gen_error_n2_connect)
        }
        GenFailureKind.NO_RESPONSE_DATA_TIMEOUT -> if (timeout != null) {
            context.getString(R.string.gen_error_n2_read_duration, timeout)
        } else {
            context.getString(R.string.gen_error_n2_read)
        }
        GenFailureKind.STREAM_INACTIVITY_TIMEOUT -> if (timeout != null) {
            context.getString(R.string.gen_error_n2_stream_duration, timeout)
        } else {
            context.getString(R.string.gen_error_n2_stream)
        }
        GenFailureKind.OVERALL_TIMEOUT -> if (timeout != null) {
            context.getString(R.string.gen_error_n2_overall_duration, timeout)
        } else {
            context.getString(R.string.gen_error_n2_overall)
        }
        GenFailureKind.HTTP_RESPONSE -> {
            val status = httpStatus
            if (status != null) {
                context.getString(R.string.gen_error_http_status, status, httpReason(status))
            } else {
                context.getString(code.messageRes())
            }
        }
        GenFailureKind.OTHER -> context.getString(code.messageRes())
    }
    return "[${code.code}] $message"
}

private fun formatTimeoutDuration(millis: Long): String = when {
    millis % 60_000L == 0L -> "${millis / 60_000L}-minute"
    millis % 1_000L == 0L -> "${millis / 1_000L}-second"
    else -> "$millis-millisecond"
}

private fun httpReason(status: Int): String = when (status) {
    408 -> "request timeout"
    429 -> "too many requests"
    502 -> "bad gateway"
    503 -> "service unavailable"
    504 -> "gateway timeout"
    else -> "HTTP error"
}
