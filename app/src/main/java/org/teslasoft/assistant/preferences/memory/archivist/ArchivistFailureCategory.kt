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

import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.ProviderLimitKind

/**
 * Maps an Archivist full-failure to the owner-approved failure category
 * (plan, Aug 1 2026), reusing the same [GenErrorResult] the chat funnel produces
 * so causes are split as reliably. Pure and framework-free — unit-tested on a
 * plain JVM (see ArchivistFailureCategoryTest).
 *
 * Two categories the chat classifier cannot see come from the Archivist's own
 * tags: a parse failure (the model answered, but the reply was not a usable
 * analysis result) is INVALID_RESULT, and a store failure is UNKNOWN. Everything
 * else is derived from the provider/transport signal. "Lorebook Analysis
 * Unavailable" is deliberately absent — there is no reliable signal for it
 * (owner: leave it out).
 *
 * The category keys line up with the mem_arch_fail_* string pairs. Interrupted
 * and cancelled are run OUTCOMES, not categories, and are handled by the caller.
 */
object ArchivistFailureCategory {

    const val CONNECTION = "connection"
    const val TIMEOUT = "timeout"
    const val REJECTED = "rejected"
    const val USAGE_LIMIT = "usage_limit"
    const val CREDITS = "credits"
    const val MODEL_UNAVAILABLE = "model_unavailable"
    const val CONFIG = "config"
    const val UNREADABLE = "unreadable"
    const val INVALID_RESULT = "invalid_result"
    const val UNKNOWN = "unknown"

    /**
     * @param reason the dominant Archivist failure tag (captures parse/store
     *   distinctions the transport classifier cannot).
     * @param gen the dominant classified transport/provider result, or null.
     */
    fun of(reason: ArchivistFailure?, gen: GenErrorResult?): String {
        // Archivist-specific tags win: the model answered but the result was
        // unusable, or the app could not store what it produced.
        when (reason) {
            ArchivistFailure.UNREADABLE -> return INVALID_RESULT
            ArchivistFailure.SAVE_FAILED -> return UNKNOWN
            else -> { /* fall through to the transport/provider signal */ }
        }
        if (gen == null) return UNKNOWN
        // Provider limits (from status or body) are the most specific signal.
        when (gen.providerLimit) {
            ProviderLimitKind.OUT_OF_CREDITS -> return CREDITS
            ProviderLimitKind.RATE_OR_THROUGHPUT,
            ProviderLimitKind.QUOTA_OR_SPENDING,
            ProviderLimitKind.UNIDENTIFIED -> return USAGE_LIMIT
            // A prompt too large for the model's context or the provider's
            // request-size limit is a configuration problem, per owner judgement.
            ProviderLimitKind.MODEL_CONTEXT,
            ProviderLimitKind.MODEL_INPUT,
            ProviderLimitKind.REQUEST_BODY -> return CONFIG
            null -> { /* not a limit */ }
        }
        // 403: the credentials are valid but not allowed here — folded into the
        // single "Request Rejected" state the owner approved.
        if (gen.httpStatus == 403) return REJECTED
        return when (gen.code) {
            GenErrorCode.N1, GenErrorCode.N3 -> CONNECTION
            GenErrorCode.N2, GenErrorCode.N4 -> TIMEOUT
            GenErrorCode.A1 -> REJECTED
            GenErrorCode.S3 -> REJECTED
            GenErrorCode.S2 -> UNREADABLE
            GenErrorCode.M1 -> CONFIG
            GenErrorCode.M2, GenErrorCode.S1 -> MODEL_UNAVAILABLE
            GenErrorCode.M3 -> CONFIG
            GenErrorCode.Q1 -> USAGE_LIMIT
            GenErrorCode.U0 -> UNKNOWN
        }
    }
}
