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

fun GenErrorResult.chatMessage(context: Context): String =
    "[${code.code}] " + context.getString(code.messageRes())

private val NO_RESPONSE_CODES = setOf(
    GenErrorCode.N1, GenErrorCode.N2, GenErrorCode.N3, GenErrorCode.N4
)

/**
 * Whether there is evidence that a server actually answered.
 *
 * The old rule treated every non-network error code as a provider response.
 * That made local response-parser failures (S2) and wholly unknown client-side
 * failures (U0) look like provider failures even when no HTTP status or provider
 * limit evidence existed. Those two ambiguous buckets now require affirmative
 * response evidence instead of being attributed to a server by elimination.
 */
fun GenErrorResult.reachedServer(): Boolean = when {
    httpStatus != null -> true
    providerLimit != null -> true
    code in NO_RESPONSE_CODES -> false
    code == GenErrorCode.S2 || code == GenErrorCode.U0 -> false
    else -> true
}

fun GenErrorResult.providerDetailBlock(
    context: Context,
    exceptionMessage: String?,
    apiProvider: String,
    modelServiceProvider: String,
    model: String,
    function: String,
    rawProviderMessage: String? = null
): String {
    val serverAnswered = reachedServer()
    val detail: String = if (!serverAnswered) {
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
    val outboundFields = if (serverAnswered) {
        OutboundRequestDiagnostics.latestFieldNamesText()
    } else {
        null
    }
    return context.getString(R.string.provider_error_line, detail) +
        "\n" + context.getString(R.string.provider_api_provider_line, apiProvider) +
        "\n" + context.getString(R.string.provider_model_service_line, modelServiceProvider) +
        "\n" + context.getString(R.string.provider_model_line, model) +
        "\n" + context.getString(R.string.provider_function_line, function) +
        (outboundFields?.let { "\nOutbound request fields: $it" } ?: "")
}

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
