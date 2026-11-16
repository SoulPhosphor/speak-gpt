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

package org.teslasoft.assistant.preferences.memory.context

import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.ProviderLimitKind

/**
 * Why the API helper gave nothing for a message, with the evidence behind it
 * (conversation_recall_plan.md M.4 item 4). The category is chosen only from
 * what the app observed; anything it cannot place is [Category.UNEXPECTED],
 * with the real error underneath. Nothing here holds conversation text or
 * credentials: provider text is sanitized before it is stored.
 */
data class HelperFailure(
    val category: Category,
    /** The API profile's name and the model, when the helper had them. */
    val profile: String? = null,
    val model: String? = null,
    /** What the app itself observed, when no provider evidence exists. */
    val local: Local? = null,
    /** The provider's or the client's own error, sanitized. */
    val provider: ProviderDetail? = null
) {
    enum class Category {
        CONFIGURATION_NEEDED, TIME_ALLOWANCE, UNREACHABLE, CONNECT_TIMEOUT, RESPONSE_TIMEOUT,
        ACCESS_REJECTED, MODEL_UNAVAILABLE, RATE_LIMIT, QUOTA, TOO_LARGE, REJECTED,
        SERVICE_ERROR, UNUSABLE, UNEXPECTED
    }

    /** A failure the app detected itself, with the number it shows. */
    sealed class Local {
        object NoModel : Local()
        object ProfileGone : Local()
        object RoutingNotSetUp : Local()
        data class NoReplyWithin(val seconds: Long) : Local()
        object ReplyEmpty : Local()
        object ReplyMultipleLines : Local()
        data class ReplyTooLong(val limitTokens: Int?) : Local()
    }

    data class ProviderDetail(
        val result: GenErrorResult,
        /** The exception's own message, sanitized. */
        val exceptionMessage: String?,
        /** The provider's response body, sanitized. */
        val providerMessage: String?,
        val evidence: ProviderDiagnosticSnapshot?,
        val modelServiceProvider: String?
    )

    companion object {
        /** The category a classified provider or client failure belongs to. */
        fun categoryOf(result: GenErrorResult): Category {
            when (result.providerLimit) {
                ProviderLimitKind.MODEL_CONTEXT, ProviderLimitKind.MODEL_INPUT,
                ProviderLimitKind.REQUEST_BODY -> return Category.TOO_LARGE
                ProviderLimitKind.RATE_OR_THROUGHPUT -> return Category.RATE_LIMIT
                ProviderLimitKind.QUOTA_OR_SPENDING, ProviderLimitKind.OUT_OF_CREDITS -> return Category.QUOTA
                else -> Unit
            }
            return when (result.code) {
                GenErrorCode.N2 -> Category.CONNECT_TIMEOUT
                GenErrorCode.N3 -> Category.UNREACHABLE
                GenErrorCode.N4 -> Category.RESPONSE_TIMEOUT
                GenErrorCode.A1, GenErrorCode.A2 -> Category.ACCESS_REJECTED
                GenErrorCode.M2 -> Category.MODEL_UNAVAILABLE
                GenErrorCode.M3 -> Category.TOO_LARGE
                GenErrorCode.M4, GenErrorCode.M5, GenErrorCode.S3, GenErrorCode.S4,
                GenErrorCode.S5 -> Category.REJECTED
                GenErrorCode.Q1 -> Category.QUOTA
                GenErrorCode.S1, GenErrorCode.S6 -> Category.SERVICE_ERROR
                GenErrorCode.S2 -> Category.UNUSABLE
                GenErrorCode.U0 -> if (result.httpStatus != null) Category.SERVICE_ERROR else Category.UNEXPECTED
                else -> Category.UNEXPECTED
            }
        }
    }
}
