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
enum class ProviderLimitKind {
    MODEL_CONTEXT,
    MODEL_INPUT,
    REQUEST_BODY,
    RATE_OR_THROUGHPUT,
    QUOTA_OR_SPENDING,
    // The account has no spendable credits (HTTP 402 / "insufficient credits").
    // Kept DISTINCT from RATE_OR_THROUGHPUT and QUOTA_OR_SPENDING so a temporary
    // throttle, a usage cap, and an empty balance can never be confused in the
    // message the user reads (owner ruling, July 31 2026).
    OUT_OF_CREDITS,
    UNIDENTIFIED
}

data class GenErrorResult(
    val code: GenErrorCode,
    val httpStatus: Int?,
    val providerLimit: ProviderLimitKind? = null,
    val isVisionRejection: Boolean = false
)

/**
 * Pure, framework-free classifier that maps a generation failure to a
 * [GenErrorCode]. Deliberately free of Android and of the OpenAI client's types:
 * it is unit-tested on a plain JVM, and must not fail to load if a client
 * exception class is renamed in a dependency bump.
 *
 * It follows the hybrid strategy in ERROR_CODES.md section 7. Concrete server
 * evidence outranks client-wrapper guesses: an explicit HTTP status and
 * structured provider code/body are authoritative, while exception class names
 * and prose are only fallback evidence. This matters for OpenAI-compatible
 * clients that may wrap a 400 Bad Request in a RateLimitException even though
 * the provider did not return HTTP 429.
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
        val structured = extractStructuredEvidence(chain)
        val structuredCodes = structured.codesAndTypes.lowercase()
        val structuredBodies = structured.bodies.lowercase()
        val status = extractHttpStatus(text) ?: extractStructuredHttpStatus(structuredCodes)
        val lower = text.lowercase()

        // 1. Auth.
        if (status == 401 || lower.contains("incorrect api key") ||
            hasType(chain, "AuthenticationException")
        ) {
            return GenErrorResult(GenErrorCode.A1, status)
        }
        // 2. Network / transport. No HTTP response exists for these, so status is
        //    forced null even if a stray number appeared in the trace.
        if (chain.any { it is UnknownHostException } || hasType(chain, "UnknownHostException") ||
            lower.contains("no address associated with hostname")) {
            return GenErrorResult(GenErrorCode.N3, null)
        }
        if (lower.contains("software caused connection abort")) {
            return GenErrorResult(GenErrorCode.N1, null)
        }
        // Connect timeout — the app could not establish the connection in time.
        // Ktor's ConnectTimeoutException carries "Connect timeout has expired";
        // it is NOT a java.net.SocketTimeoutException, so it is matched first and
        // separately from the read timeout below.
        if (lower.contains("connect timeout has expired") ||
            hasType(chain, "ConnectTimeoutException")
        ) {
            return GenErrorResult(GenErrorCode.N2, null)
        }
        // Read / response timeout — connected, but no response arrived in time.
        // Ktor's SocketTimeoutException extends java.net's and carries "Socket
        // timeout has expired"; a plain read timeout surfaces as either.
        if (chain.any { it is SocketTimeoutException } ||
            lower.contains("socket timeout has expired") ||
            lower.contains("sockettimeoutexception") ||
            hasType(chain, "HttpRequestTimeoutException")
        ) {
            return GenErrorResult(GenErrorCode.N4, null)
        }

        // 3. Provider limits. HTTP 413 and 402 are explicit protocol evidence.
        if (status == 413) {
            return providerLimitResult(ProviderLimitKind.REQUEST_BODY, status)
        }
        if (status == 402) {
            return providerLimitResult(ProviderLimitKind.OUT_OF_CREDITS, status)
        }

        // Structured provider codes/types/bodies are stronger evidence than the
        // SDK wrapper type. Preserve that even when a provider chooses an odd
        // HTTP status (for example a 400 body that explicitly says
        // context_length_exceeded).
        providerLimitFromEvidence(structuredCodes)?.let {
            return providerLimitResult(it, status)
        }
        providerLimitFromEvidence(structuredBodies)?.let {
            return providerLimitResult(it, status)
        }

        // A 400/422 is a request rejection unless the structured provider data
        // above explicitly identified a limit. Do not mine generic exception
        // prose such as "maximum" or an SDK RateLimitException class name and
        // turn the server's Bad Request into a made-up rate/throughput failure.
        if (status != 400 && status != 422) {
            providerLimitFromEvidence(lower)?.let {
                return providerLimitResult(it, status)
            }
        }
        if (status == 429 || (status == null && hasType(chain, "RateLimitException"))) {
            return providerLimitResult(ProviderLimitKind.RATE_OR_THROUGHPUT, status)
        }

        // 4. Model-specific. A model-not-found body is M2 even when the HTTP
        //    status is 404, so this is checked before the bare-404 rule below.
        if (lower.contains("invalid model") || lower.contains("you must provide a model")) {
            return GenErrorResult(GenErrorCode.M1, status)
        }
        if (lower.contains("does not exist")) {
            return GenErrorResult(GenErrorCode.M2, status)
        }
        // 5. Bare HTTP 404 with no model-specific body.
        if (status == 404 || lower.contains("not found")) {
            return GenErrorResult(GenErrorCode.S1, 404)
        }
        // 6. Response-shape failure / content rejection.
        if (lower.contains("notransformationfoundexception") ||
            lower.contains("expected response body of the type")
        ) {
            return GenErrorResult(GenErrorCode.S2, status)
        }
        if (lower.contains("your request was rejected")) {
            return GenErrorResult(GenErrorCode.S3, status,
                isVisionRejection = looksLikeVisionRejection(lower))
        }

        // 7. Unknown catch-all. In particular, an otherwise-unidentified 400 or
        // 422 stays unknown so the UI can show the provider's captured response
        // verbatim instead of inventing a limit category.
        return GenErrorResult(GenErrorCode.U0, status,
            isVisionRejection = looksLikeVisionRejection(lower))
    }

    private fun looksLikeVisionRejection(lower: String): Boolean =
        containsAny(lower,
            "does not support image",
            "does not support vision",
            "image_not_supported",
            "vision is not supported",
            "not support multimodal",
            "does not accept image",
            "image input is not supported",
            "does not support multi-modal",
            "image_input_not_supported",
            "content type is not supported"
        )

    private fun containsAny(text: String, vararg values: String): Boolean =
        values.any(text::contains)

    private fun providerLimitFromEvidence(evidence: String): ProviderLimitKind? {
        if (evidence.isBlank()) return null
        return when {
            // Checked before the quota/spending markers: an empty balance is a
            // distinct cause from a usage cap, and OpenRouter's 402 body reads
            // "Insufficient credits. Add more using …".
            containsAny(
                evidence,
                "insufficient_credits",
                "insufficient credit",
                "not enough credit",
                "no credits remaining",
                "negative balance",
                "payment required"
            ) -> ProviderLimitKind.OUT_OF_CREDITS
            containsAny(
                evidence,
                "request_body_too_large",
                "request_too_large",
                "request_entity_too_large",
                "payload_too_large",
                "body_size_limit_exceeded",
                "content length exceeded",
                "request body is too large",
                "request body was larger",
                "maximum request size",
                "entity too large"
            ) -> ProviderLimitKind.REQUEST_BODY
            containsAny(
                evidence,
                "context_length_exceeded",
                "maximum_context_length",
                "input_too_long",
                "this model's maximum context",
                "maximum context length"
            ) -> ProviderLimitKind.MODEL_CONTEXT
            containsAny(
                evidence,
                "max_input_tokens",
                "maximum_input_tokens",
                "input_limit_exceeded",
                "maximum input length",
                "input token limit"
            ) -> ProviderLimitKind.MODEL_INPUT
            containsAny(
                evidence,
                "insufficient_quota",
                "billing_hard_limit",
                "spending_limit",
                "billing limit",
                "current quota",
                "account quota"
            ) -> ProviderLimitKind.QUOTA_OR_SPENDING
            containsAny(
                evidence,
                "rate_limit_exceeded",
                "tokens per minute",
                "tokens-per-minute",
                "requests per minute",
                "requests-per-minute",
                " tpm ",
                " rpm ",
                "too many requests"
            ) -> ProviderLimitKind.RATE_OR_THROUGHPUT
            else -> null
        }
    }

    private fun providerLimitResult(
        kind: ProviderLimitKind,
        status: Int?
    ): GenErrorResult {
        val code = when (kind) {
            ProviderLimitKind.REQUEST_BODY -> GenErrorCode.S2
            ProviderLimitKind.MODEL_CONTEXT,
            ProviderLimitKind.MODEL_INPUT -> GenErrorCode.M3
            ProviderLimitKind.RATE_OR_THROUGHPUT,
            ProviderLimitKind.QUOTA_OR_SPENDING,
            ProviderLimitKind.OUT_OF_CREDITS -> GenErrorCode.Q1
            ProviderLimitKind.UNIDENTIFIED -> GenErrorCode.U0
        }
        return GenErrorResult(code, status, kind)
    }

    /**
     * Common SDK exceptions expose provider code/type/body as no-argument
     * accessors or fields. Read those conservatively so an explicit provider
     * code wins even when the exception's prose is localized or generic.
     */
    private data class StructuredEvidence(
        val codesAndTypes: String,
        val bodies: String
    )

    private fun extractStructuredEvidence(chain: List<Throwable>): StructuredEvidence {
        val codesAndTypes = StringBuilder()
        val bodies = StringBuilder()
        val codeNames = setOf("code", "errorcode", "type", "errortype")
        val bodyNames = setOf("body", "responsebody", "errorbody")

        fun append(name: String, value: Any?) {
            when (name) {
                in codeNames -> codesAndTypes.append(value).append('\n')
                in bodyNames -> bodies.append(value).append('\n')
            }
        }

        for (throwable in chain) {
            for (method in throwable.javaClass.methods) {
                if (method.parameterCount != 0) continue
                val normalized = method.name
                    .removePrefix("get")
                    .lowercase()
                if (normalized !in codeNames && normalized !in bodyNames) continue
                runCatching { method.invoke(throwable) }
                    .getOrNull()
                    ?.let { append(normalized, it) }
            }
            for (field in throwable.javaClass.declaredFields) {
                val normalized = field.name.lowercase()
                if (normalized !in codeNames && normalized !in bodyNames) continue
                runCatching {
                    field.isAccessible = true
                    field.get(throwable)
                }.getOrNull()?.let { append(normalized, it) }
            }
        }
        return StructuredEvidence(codesAndTypes.toString(), bodies.toString())
    }

    private fun extractStructuredHttpStatus(codesAndTypes: String): Int? {
        val matches = Regex("""\b([1-5]\d{2})\b""").findAll(codesAndTypes)
        for (match in matches) {
            val code = match.groupValues[1].toIntOrNull() ?: continue
            if (code in 100..599) return code
        }
        return null
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
     * "HTTP/1.1 404", "429 Too Many Requests", or client-generic "400 ERROR").
     * Conservative on purpose: a status is a bonus for the log and for
     * disambiguation, but classification never depends on it alone.
     */
    private fun extractHttpStatus(text: String): Int? {
        val patterns = listOf(
            Regex("""status\s*code[ =:]*\s*(\d{3})""", RegexOption.IGNORE_CASE),
            Regex("""\bHTTP/?\d?(?:\.\d)?\s+(\d{3})\b"""),
            Regex("""\b(\d{3})\s+ERROR\b""", RegexOption.IGNORE_CASE),
            Regex(
                """\b(\d{3})\s+(?:Unauthorized|Payment Required|Forbidden|Not Found|Bad Request|Unprocessable Entity|Too Many Requests|""" +
                    """Payload Too Large|Request Entity Too Large|Internal Server Error|""" +
                    """Bad Gateway|Service Unavailable|Gateway Timeout)\b"""
            )
        )
        for (p in patterns) {
            val code = p.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (code in 100..599) return code
        }
        return null
    }
}
