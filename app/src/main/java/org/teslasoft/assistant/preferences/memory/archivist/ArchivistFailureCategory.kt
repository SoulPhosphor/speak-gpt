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
    /** Generic fallback within the rejection family: the UI shows this when a
     *  run's rejected requests do not all share the same detectable subtype
     *  below (owner ruling, Aug 3 2026). Never returned directly by [of] —
     *  every rejection path below now resolves to one of the three specific
     *  subtypes; the caller collapses them back to this when they are not
     *  uniform across the run. */
    const val REJECTED = "rejected"
    /** The provider rejected the API key (401 / auth failure). */
    const val API_KEY_REJECTED = "api_key_rejected"
    /** A genuine provider access-denied response (narrow: HTTP 403 only). */
    const val ACCESS_DENIED = "access_denied"
    /** The provider refused the request as inappropriate content. */
    const val CONTENT_REFUSED = "content_refused"
    const val RATE_LIMIT = "rate_limit"
    const val USAGE_LIMIT = "usage_limit"
    const val CREDITS = "credits"
    const val MODEL_UNAVAILABLE = "model_unavailable"
    const val REQUEST_TOO_LARGE = "request_too_large"
    const val CONFIG = "config"
    const val PROVIDER_ERROR = "provider_error"
    const val UNREADABLE = "unreadable"
    const val INVALID_RESULT = "invalid_result"
    const val SAVE_FAILED = "save_failed"
    const val PROCESS_LOCAL = "process_local"
    const val UNKNOWN = "unknown"

    /**
     * @param reason the dominant Archivist failure tag (captures parse/store
     *   distinctions the transport classifier cannot).
     * @param gen the dominant classified transport/provider result, or null.
     */
    fun of(reason: ArchivistFailure?, gen: GenErrorResult?): String {
        // Archivist-specific tags win: the model answered but the result was
        // unusable, or produced suggestions the app could not store.
        when (reason) {
            ArchivistFailure.UNREADABLE -> return INVALID_RESULT
            ArchivistFailure.SAVE_FAILED -> return SAVE_FAILED
            else -> { /* fall through to the transport/provider signal */ }
        }
        if (gen == null) return UNKNOWN
        // Provider limits (from status or body) are the most specific signal.
        // A temporary throttle, a usage/spending cap, and an empty balance are
        // three distinct actions for the user, so they stay separate (owner
        // ruling, Aug 1 2026).
        when (gen.providerLimit) {
            ProviderLimitKind.OUT_OF_CREDITS -> return CREDITS
            ProviderLimitKind.RATE_OR_THROUGHPUT -> return RATE_LIMIT
            ProviderLimitKind.QUOTA_OR_SPENDING,
            ProviderLimitKind.UNIDENTIFIED -> return USAGE_LIMIT
            // The submitted content is too large — a distinct state, NOT
            // Invalid Configuration: the endpoint/model may be correct, the
            // content is simply over the limit (owner ruling, Aug 1 2026).
            ProviderLimitKind.MODEL_CONTEXT,
            ProviderLimitKind.MODEL_INPUT,
            ProviderLimitKind.REQUEST_BODY -> return REQUEST_TOO_LARGE
            null -> { /* not a limit */ }
        }
        // 403: the credentials are valid but not allowed here. Narrow signal —
        // only a genuine HTTP 403 response counts, never a text guess (owner
        // ruling, Aug 3 2026).
        if (gen.httpStatus == 403) return ACCESS_DENIED
        // A gateway timeout is a timeout, not a generic provider error.
        if (gen.httpStatus == 504) return TIMEOUT
        return when (gen.code) {
            GenErrorCode.N1, GenErrorCode.N3 -> CONNECTION
            GenErrorCode.N2, GenErrorCode.N4 -> TIMEOUT
            GenErrorCode.A1 -> API_KEY_REJECTED
            GenErrorCode.S3 -> CONTENT_REFUSED
            GenErrorCode.S2 -> UNREADABLE
            GenErrorCode.M1 -> CONFIG
            // Only a response that names the MODEL as missing is Model
            // Unavailable; a bare 404 is most often a wrong endpoint URL, so it
            // maps to Invalid Configuration (owner ruling, Aug 1 2026).
            GenErrorCode.M2 -> MODEL_UNAVAILABLE
            GenErrorCode.S1 -> CONFIG
            // Context-length exceeded is the content being too large.
            GenErrorCode.M3 -> REQUEST_TOO_LARGE
            GenErrorCode.Q1 -> RATE_LIMIT
            // Unmatched: a 5xx the provider returned is a server-side Provider
            // Error; no HTTP response at all means the app failed locally before
            // reaching the provider; anything else is genuinely unexpected.
            GenErrorCode.U0 -> when {
                gen.httpStatus != null && gen.httpStatus in 500..599 -> PROVIDER_ERROR
                gen.httpStatus == null -> PROCESS_LOCAL
                else -> UNKNOWN
            }
        }
    }
}
