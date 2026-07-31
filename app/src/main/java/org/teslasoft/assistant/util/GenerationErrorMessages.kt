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
    GenErrorCode.N4 -> R.string.gen_error_n4
    GenErrorCode.A1 -> R.string.gen_error_a1
    GenErrorCode.M1 -> R.string.gen_error_m1
    GenErrorCode.M2 -> R.string.gen_error_m2
    GenErrorCode.M3 -> R.string.gen_error_m3
    GenErrorCode.Q1 -> R.string.gen_error_q1
    GenErrorCode.S1 -> R.string.gen_error_s1
    GenErrorCode.S2 -> R.string.gen_error_s2
    GenErrorCode.S3 -> R.string.gen_error_s3
    GenErrorCode.U0 -> R.string.gen_error_u0
}

/** The exact text shown in chat: `[N1] <neutral sentence>`. No profile, Base
 *  URL, model, or stack trace — those go only to the Error Log. */
fun GenErrorResult.chatMessage(context: Context): String =
    "[${code.code}] " + context.getString(code.messageRes())

/** Transport codes mean the request never reached a server, so there is no
 *  provider response to quote — the detail block says so explicitly rather than
 *  inventing one. */
private val NO_RESPONSE_CODES = setOf(
    GenErrorCode.N1, GenErrorCode.N2, GenErrorCode.N3, GenErrorCode.N4
)

/**
 * The raw provider detail shown beneath the app's own failure explanation
 * (owner ruling, July 31 2026): two always-present lines —
 *
 *   Provider Error: <the server's status and message, verbatim>
 *   Provider: <the upstream provider name>
 *
 * Each line falls back to a truthful placeholder rather than a blank: a request
 * that never reached a server says so, and an absent provider name says so.
 * [rawProviderName] and a parsed body are supplied by the network-capture layer;
 * until then the name shows its placeholder and the message falls back to the
 * exception text.
 */
fun GenErrorResult.providerDetailBlock(
    context: Context,
    exceptionMessage: String?,
    rawProviderName: String? = null,
    rawProviderMessage: String? = null
): String {
    val detail: String = if (code in NO_RESPONSE_CODES) {
        context.getString(R.string.provider_error_no_response)
    } else {
        val message = rawProviderMessage?.trim()?.ifBlank { null }
            ?: exceptionMessage?.trim()?.ifBlank { null }
        when {
            httpStatus != null && message != null -> "$httpStatus $message"
            message != null -> message
            httpStatus != null -> httpStatus.toString()
            else -> context.getString(R.string.provider_error_none)
        }
    }
    val providerName = rawProviderName?.trim()?.ifBlank { null }
        ?: context.getString(R.string.provider_name_none)
    return context.getString(R.string.provider_error_line, detail) +
        "\n" + context.getString(R.string.provider_name_line, providerName)
}

/** Exact explanation for a provider-confirmed capacity system. */
fun GenErrorResult.providerLimitMessage(context: Context): String? =
    when (providerLimit) {
        ProviderLimitKind.MODEL_CONTEXT ->
            context.getString(R.string.provider_context_overflow)
        ProviderLimitKind.MODEL_INPUT ->
            context.getString(R.string.provider_input_limit)
        ProviderLimitKind.REQUEST_BODY ->
            context.getString(R.string.provider_request_body_limit)
        ProviderLimitKind.RATE_OR_THROUGHPUT ->
            context.getString(R.string.provider_rate_limit)
        ProviderLimitKind.QUOTA_OR_SPENDING ->
            context.getString(R.string.provider_quota_limit)
        ProviderLimitKind.OUT_OF_CREDITS ->
            context.getString(R.string.provider_out_of_credits)
        ProviderLimitKind.UNIDENTIFIED ->
            context.getString(R.string.provider_unknown_limit)
        null -> null
    }
