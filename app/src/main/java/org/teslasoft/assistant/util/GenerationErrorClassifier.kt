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

import com.aallam.openai.api.exception.OpenAIAPIException
import com.openai.errors.OpenAIServiceException
import io.ktor.client.plugins.ResponseException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Stable generation-error codes. See ERROR_CODES.md for the full design: each
 * code is a permanent contract (once assigned to a cause it is never reused),
 * the chat shows the code plus a short neutral message, and the Error Log keeps
 * the technical detail. `includeStackTrace` marks the two ambiguous/unknown
 * codes whose Error Log entry must carry the full trace (everything else would
 * only be noise).
 */
enum class GenErrorCode(val code: String, val includeStackTrace: Boolean) {
    N1("N1", false), // connection dropped mid-response (transport abort)
    N2("N2", false), // connect / socket timeout
    N3("N3", false), // host unreachable / DNS / offline
    A1("A1", false), // API key rejected
    M1("M1", false), // no model set on the request
    M2("M2", false), // named model not available on the endpoint
    M3("M3", false), // context length exceeded
    Q1("Q1", false), // quota / usage limit reached
    S1("S1", false), // bare HTTP 404 / Not Found
    S2("S2", true),  // response could not be read as the expected stream
    S3("S3", false), // request rejected as inappropriate content
    S4("S4", false), // explicit transient HTTP response (408/502/503/504)
    U0("U0", true);  // anything unmatched
}

enum class GenFailureKind {
    CONNECT_TIMEOUT,
    NO_RESPONSE_DATA_TIMEOUT,
    STREAM_INACTIVITY_TIMEOUT,
    OVERALL_TIMEOUT,
    HTTP_RESPONSE,
    OTHER,
}

/** Result of classifying a failure, retaining typed HTTP and timeout evidence. */
data class GenErrorResult(
    val code: GenErrorCode,
    val httpStatus: Int?,
    val kind: GenFailureKind = GenFailureKind.OTHER,
    val configuredTimeoutMillis: Long? = null,
)

/**
 * Android-free classifier that maps a generation failure to a [GenErrorCode].
 * It reads typed status fields from both pinned HTTP clients and is unit-tested
 * on a plain JVM.
 *
 * It follows the hybrid strategy in ERROR_CODES.md section 7 — strongest signal
 * first: exception **type** (the `java.net` transport types are matched directly;
 * client/Ktor types by class name so no import is needed), then server **status
 * / body**, then raw error **text** as the fallback. The fixed evaluation order
 * is the priority ladder from the doc, so overlapping cases (a model-not-found
 * returned as an HTTP 404, say) always resolve the same way.
 */
object GenerationErrorClassifier {

    private const val KTOR_ANDROID_DEFAULT_CONNECT_TIMEOUT_MS = 100_000L
    private const val OPENAI_JAVA_DEFAULT_CONNECT_TIMEOUT_MS = 60_000L
    private const val OPENAI_JAVA_DEFAULT_REQUEST_TIMEOUT_MS = 600_000L

    fun classify(error: Throwable, requestState: GenerationRequestState? = null): GenErrorResult {
        val chain = causeChain(error)
        val text = buildString {
            for (t in chain) {
                append(t::class.qualifiedName ?: ""); append('\n')
                append(t.message ?: ""); append('\n')
            }
        }
        // Prefer actual status fields carried by the two HTTP clients. Text
        // scraping remains only a compatibility fallback for untyped wrappers.
        val status = extractTypedHttpStatus(chain) ?: extractHttpStatus(text)

        // 1. Auth.
        if (status == 401 || text.contains("Incorrect API key") || hasType(chain, "AuthenticationException")) {
            return GenErrorResult(GenErrorCode.A1, status)
        }
        // 2. Rate/usage limiting. HTTP 429 means "too many requests"; it does
        //    not by itself prove that the account's paid quota is exhausted.
        if (status == 429 || text.contains("You exceeded your current quota") || hasType(chain, "RateLimitException")) {
            return GenErrorResult(
                GenErrorCode.Q1,
                status,
                if (status == 429) GenFailureKind.HTTP_RESPONSE else GenFailureKind.OTHER,
            )
        }
        // 3. Explicit server/service responses. These are deliberately checked
        //    before local timeout types: an HTTP status is proof that the remote
        //    endpoint answered, while a local timeout alone is not.
        if (status in setOf(408, 502, 503, 504)) {
            return GenErrorResult(GenErrorCode.S4, status, GenFailureKind.HTTP_RESPONSE)
        }

        // 4. Network / transport.
        if (chain.any { it is UnknownHostException } || hasType(chain, "UnknownHostException") ||
            text.contains("No address associated with hostname")) {
            return GenErrorResult(GenErrorCode.N3, status)
        }
        if (text.contains("Software caused connection abort")) {
            return GenErrorResult(GenErrorCode.N1, status)
        }

        if (hasType(chain, "ConnectTimeoutException") ||
            text.contains("Connect timeout has expired", ignoreCase = true) ||
            text.contains("connect timed out", ignoreCase = true)) {
            return GenErrorResult(
                GenErrorCode.N2,
                status,
                GenFailureKind.CONNECT_TIMEOUT,
                extractTimeoutMillis(text, "connect_timeout")
                    ?: when {
                        requestState?.operation == "GPT Image generation" -> OPENAI_JAVA_DEFAULT_CONNECT_TIMEOUT_MS
                        hasType(chain, "ConnectTimeoutException") -> KTOR_ANDROID_DEFAULT_CONNECT_TIMEOUT_MS
                        else -> null
                    },
            )
        }

        val openAIJavaCallTimeout = requestState?.operation == "GPT Image generation" &&
            chain.any { it is InterruptedIOException && it !is SocketTimeoutException } &&
            text.contains("timeout", ignoreCase = true)
        if (hasType(chain, "HttpRequestTimeoutException") ||
            hasType(chain, "CallTimeoutException") ||
            text.contains("Request timeout has expired", ignoreCase = true) ||
            openAIJavaCallTimeout) {
            return GenErrorResult(
                GenErrorCode.N2,
                status,
                GenFailureKind.OVERALL_TIMEOUT,
                extractTimeoutMillis(text, "request_timeout")
                    ?: if (requestState?.operation == "GPT Image generation") {
                        OPENAI_JAVA_DEFAULT_REQUEST_TIMEOUT_MS
                    } else null,
            )
        }

        if (chain.any { it is SocketTimeoutException } ||
            hasType(chain, "SocketTimeoutException") ||
            text.contains("Socket timeout has expired", ignoreCase = true) ||
            hasType(chain, "OpenAITimeoutException")) {
            val responseStarted = requestState?.firstStreamEventArrived == true ||
                requestState?.responseTextArrived == true
            return GenErrorResult(
                GenErrorCode.N2,
                status,
                if (responseStarted) GenFailureKind.STREAM_INACTIVITY_TIMEOUT
                else GenFailureKind.NO_RESPONSE_DATA_TIMEOUT,
                extractTimeoutMillis(text, "socket_timeout")
                    ?: if (requestState?.operation == "GPT Image generation") {
                        OPENAI_JAVA_DEFAULT_REQUEST_TIMEOUT_MS
                    } else null,
            )
        }

        // 5. Context length.
        if (text.contains("This model's maximum")) {
            return GenErrorResult(GenErrorCode.M3, status)
        }
        // 6. Model-specific. A model-not-found body is M2 even when the HTTP
        //    status is 404, so this is checked before the bare-404 rule below.
        if (text.contains("invalid model") || text.contains("you must provide a model")) {
            return GenErrorResult(GenErrorCode.M1, status)
        }
        if (text.contains("does not exist")) {
            return GenErrorResult(GenErrorCode.M2, status)
        }
        // 7. Bare HTTP 404 with no model-specific body.
        if (status == 404 || text.contains("Not Found")) {
            return GenErrorResult(GenErrorCode.S1, 404)
        }
        // 8. Response-shape failure / content rejection.
        if (text.contains("NoTransformationFoundException") || text.contains("Expected response body of the type")) {
            return GenErrorResult(GenErrorCode.S2, status)
        }
        if (text.contains("Your request was rejected")) {
            return GenErrorResult(GenErrorCode.S3, status)
        }
        // 9. Unknown catch-all. Preserve a typed status even when the app has
        //    no dedicated user-facing category for it yet.
        return GenErrorResult(GenErrorCode.U0, status)
    }

    /** The throwable and its cause chain, guarding against a cyclic `cause`. */
    private fun causeChain(error: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var cur: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (cur != null && seen.add(cur)) {
            out.add(cur)
            cur = cur.cause
        }
        return out
    }

    /** Matches a client/Ktor exception by class simple name without importing it,
     *  so a dependency rename can never break compilation here. */
    private fun hasType(chain: List<Throwable>, simpleName: String): Boolean =
        chain.any { (it::class.simpleName ?: "").contains(simpleName) }

    private fun extractTypedHttpStatus(chain: List<Throwable>): Int? {
        for (error in chain) {
            val status = when (error) {
                is OpenAIAPIException -> error.statusCode
                is ResponseException -> error.response.status.value
                is OpenAIServiceException -> error.statusCode()
                else -> null
            }
            if (status != null && status in 100..599) return status
        }
        return null
    }

    private fun extractTimeoutMillis(text: String, field: String): Long? =
        Regex("""\b${Regex.escape(field)}\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

    /**
     * Best-effort HTTP status from common phrasings ("status code 401",
     * "HTTP/1.1 404", "429 Too Many Requests"). Conservative on purpose: a status
     * is a bonus for the log and for disambiguation, but classification never
     * depends on it alone, so a missed status simply falls through to text
     * matching rather than risking a wrong number scraped out of a stack trace.
     */
    private fun extractHttpStatus(text: String): Int? {
        val patterns = listOf(
            Regex("""status\s*code[ =:]*\s*(\d{3})""", RegexOption.IGNORE_CASE),
            Regex("""\bHTTP/?\d?(?:\.\d)?\s+(\d{3})\b"""),
            Regex(
                """\b(\d{3})\s+(?:Unauthorized|Forbidden|Not Found|Bad Request|Too Many Requests|""" +
                    """Request Timeout|Internal Server Error|Bad Gateway|Service Unavailable|Gateway Timeout)\b"""
            )
        )
        for (p in patterns) {
            val code = p.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (code in 100..599) return code
        }
        return null
    }
}
