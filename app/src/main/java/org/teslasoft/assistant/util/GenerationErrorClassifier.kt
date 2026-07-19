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
    N2("N2", false), // connect timeout — could not establish the connection in time
    N3("N3", false), // host unreachable / DNS / offline
    N4("N4", false), // response (read/socket) timeout — connected, but no reply in time
    A1("A1", false), // API key rejected
    M1("M1", false), // no model set on the request
    M2("M2", false), // named model not available on the endpoint
    M3("M3", false), // context length exceeded
    Q1("Q1", false), // quota / usage limit reached
    S1("S1", false), // bare HTTP 404 / Not Found
    S2("S2", true),  // response could not be read as the expected stream
    S3("S3", false), // request rejected as inappropriate content
    U0("U0", true);  // anything unmatched
}

/** Result of classifying a failure: the code, plus the HTTP status when the
 *  server actually answered (null for transport drops, which have no status). */
data class GenErrorResult(val code: GenErrorCode, val httpStatus: Int?)

/**
 * Pure, framework-free classifier that maps a generation failure to a
 * [GenErrorCode]. Deliberately free of Android and of the OpenAI client's types:
 * it is unit-tested on a plain JVM, and must not fail to load if a client
 * exception class is renamed in a dependency bump.
 *
 * It follows the hybrid strategy in ERROR_CODES.md section 7 — strongest signal
 * first: exception **type** (the `java.net` transport types are matched directly;
 * client/Ktor types by class name so no import is needed), then server **status
 * / body**, then raw error **text** as the fallback. The fixed evaluation order
 * is the priority ladder from the doc, so overlapping cases (a model-not-found
 * returned as an HTTP 404, say) always resolve the same way.
 */
object GenerationErrorClassifier {

    fun classify(error: Throwable): GenErrorResult {
        val chain = causeChain(error)
        val text = buildString {
            for (t in chain) {
                append(t::class.qualifiedName ?: ""); append('\n')
                append(t.message ?: ""); append('\n')
            }
            append(error.stackTraceToString())
        }
        val status = extractHttpStatus(text)

        // 1. Auth.
        if (status == 401 || text.contains("Incorrect API key") || hasType(chain, "AuthenticationException")) {
            return GenErrorResult(GenErrorCode.A1, status)
        }
        // 2. Quota.
        if (status == 429 || text.contains("You exceeded your current quota") || hasType(chain, "RateLimitException")) {
            return GenErrorResult(GenErrorCode.Q1, status)
        }
        // 3. Network / transport. No HTTP response exists for these, so status is
        //    forced null even if a stray number appeared in the trace.
        if (chain.any { it is UnknownHostException } || hasType(chain, "UnknownHostException") ||
            text.contains("No address associated with hostname")) {
            return GenErrorResult(GenErrorCode.N3, null)
        }
        if (text.contains("Software caused connection abort")) {
            return GenErrorResult(GenErrorCode.N1, null)
        }
        // Connect timeout — the app could not establish the connection in time.
        // Ktor's ConnectTimeoutException carries "Connect timeout has expired";
        // it is NOT a java.net.SocketTimeoutException, so it is matched first and
        // separately from the read timeout below.
        if (text.contains("Connect timeout has expired") || hasType(chain, "ConnectTimeoutException")) {
            return GenErrorResult(GenErrorCode.N2, null)
        }
        // Read / response timeout — connected, but no response arrived in time.
        // Ktor's SocketTimeoutException extends java.net's and carries "Socket
        // timeout has expired"; a plain read timeout surfaces as either.
        if (chain.any { it is SocketTimeoutException } || text.contains("Socket timeout has expired") ||
            text.contains("SocketTimeoutException") || hasType(chain, "HttpRequestTimeoutException")) {
            return GenErrorResult(GenErrorCode.N4, null)
        }
        // 4. Context length.
        if (text.contains("This model's maximum")) {
            return GenErrorResult(GenErrorCode.M3, status)
        }
        // 5. Model-specific. A model-not-found body is M2 even when the HTTP
        //    status is 404, so this is checked before the bare-404 rule below.
        if (text.contains("invalid model") || text.contains("you must provide a model")) {
            return GenErrorResult(GenErrorCode.M1, status)
        }
        if (text.contains("does not exist")) {
            return GenErrorResult(GenErrorCode.M2, status)
        }
        // 6. Bare HTTP 404 with no model-specific body.
        if (status == 404 || text.contains("Not Found")) {
            return GenErrorResult(GenErrorCode.S1, 404)
        }
        // 7. Response-shape failure / content rejection.
        if (text.contains("NoTransformationFoundException") || text.contains("Expected response body of the type")) {
            return GenErrorResult(GenErrorCode.S2, status)
        }
        if (text.contains("Your request was rejected")) {
            return GenErrorResult(GenErrorCode.S3, status)
        }
        // 8. Unknown catch-all.
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
                    """Internal Server Error|Bad Gateway|Service Unavailable|Gateway Timeout)\b"""
            )
        )
        for (p in patterns) {
            val code = p.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (code in 100..599) return code
        }
        return null
    }
}
