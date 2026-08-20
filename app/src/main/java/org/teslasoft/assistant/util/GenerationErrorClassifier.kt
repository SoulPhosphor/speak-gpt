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
    N1("N1", false),
    N2("N2", false),
    N3("N3", false),
    N4("N4", false),
    A1("A1", false),
    M1("M1", false),
    M2("M2", false),
    M3("M3", false),
    Q1("Q1", false),
    S1("S1", false),
    S2("S2", true),
    S3("S3", false),
    U0("U0", true);
}

enum class ProviderLimitKind {
    MODEL_CONTEXT,
    MODEL_INPUT,
    REQUEST_BODY,
    RATE_OR_THROUGHPUT,
    QUOTA_OR_SPENDING,
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
 * Strongest evidence wins. A concrete status exposed by the client exception
 * (for example openai-client's `statusCode`) outranks status-like prose in the
 * exception message. Structured provider code/body evidence comes next; class
 * names and raw prose are fallbacks only. This prevents a wrapper name or an
 * upstream string such as "400 ERROR" from overriding the HTTP status the client
 * actually received.
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
        val status = extractStructuredHttpStatus(structured.httpStatuses)
            ?: extractHttpStatus(text)
        val lower = text.lowercase()

        // 1. Auth. A typed AuthenticationException is fallback evidence only;
        // if the exception exposes a different concrete HTTP status, trust it.
        if (status == 401 || lower.contains("incorrect api key") ||
            (status == null && hasType(chain, "AuthenticationException"))
        ) {
            return GenErrorResult(GenErrorCode.A1, status)
        }

        // 2. Network / transport. These conditions mean no usable HTTP response
        // exists for the failed operation, so status is intentionally null.
        if (chain.any { it is UnknownHostException } || hasType(chain, "UnknownHostException") ||
            lower.contains("no address associated with hostname")) {
            return GenErrorResult(GenErrorCode.N3, null)
        }
        if (lower.contains("software caused connection abort")) {
            return GenErrorResult(GenErrorCode.N1, null)
        }
        if (lower.contains("connect timeout has expired") ||
            hasType(chain, "ConnectTimeoutException")
        ) {
            return GenErrorResult(GenErrorCode.N2, null)
        }
        if (chain.any { it is SocketTimeoutException } ||
            lower.contains("socket timeout has expired") ||
            lower.contains("sockettimeoutexception") ||
            hasType(chain, "HttpRequestTimeoutException")
        ) {
            return GenErrorResult(GenErrorCode.N4, null)
        }

        // 3. Explicit protocol statuses.
        if (status == 413) {
            return providerLimitResult(ProviderLimitKind.REQUEST_BODY, status)
        }
        if (status == 402) {
            return providerLimitResult(ProviderLimitKind.OUT_OF_CREDITS, status)
        }

        // Structured provider evidence outranks prose. Strong, specific text
        // markers are still useful even on a 400/422; what is forbidden is the
        // old generic "contains the word limit/maximum" guess.
        providerLimitFromEvidence(structuredCodes)?.let {
            return providerLimitResult(it, status)
        }
        providerLimitFromEvidence(structuredBodies)?.let {
            return providerLimitResult(it, status)
        }
        providerLimitFromEvidence(lower)?.let {
            return providerLimitResult(it, status)
        }

        // HTTP 429 is authoritative rate-limit evidence. A RateLimitException
        // class name is only a fallback when the client exposed no status at all.
        if (status == 429 || (status == null && hasType(chain, "RateLimitException"))) {
            return providerLimitResult(ProviderLimitKind.RATE_OR_THROUGHPUT, status)
        }

        // 4. Model-specific.
        if (lower.contains("invalid model") || lower.contains("you must provide a model")) {
            return GenErrorResult(GenErrorCode.M1, status)
        }
        if (lower.contains("does not exist") || lower.contains("model not found")) {
            return GenErrorResult(GenErrorCode.M2, status)
        }

        // 5. Bare HTTP 404. Text-only "not found" is fallback evidence only.
        if (status == 404 || (status == null && lower.contains("not found"))) {
            return GenErrorResult(GenErrorCode.S1, status ?: 404)
        }

        // 6. Response-shape failure / content rejection.
        if (lower.contains("notransformationfoundexception") ||
            lower.contains("expected response body of the type")
        ) {
            return GenErrorResult(GenErrorCode.S2, status)
        }
        if (lower.contains("your request was rejected")) {
            return GenErrorResult(
                GenErrorCode.S3,
                status,
                isVisionRejection = looksLikeVisionRejection(lower)
            )
        }

        // 7. Unknown catch-all. An otherwise-unidentified 400/422 stays unknown
        // so the provider/client detail remains the diagnostic authority.
        return GenErrorResult(
            GenErrorCode.U0,
            status,
            isVisionRejection = looksLikeVisionRejection(lower)
        )
    }

    private fun looksLikeVisionRejection(lower: String): Boolean =
        containsAny(
            lower,
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
     * Common SDK exceptions expose status/code/type/body as public no-argument
     * accessors or fields. Read them conservatively so concrete client evidence
     * survives even when the exception message is generic.
     */
    private data class StructuredEvidence(
        val codesAndTypes: String,
        val bodies: String,
        val httpStatuses: String
    )

    private fun extractStructuredEvidence(chain: List<Throwable>): StructuredEvidence {
        val codesAndTypes = StringBuilder()
        val bodies = StringBuilder()
        val httpStatuses = StringBuilder()
        val codeNames = setOf("code", "errorcode", "type", "errortype")
        val bodyNames = setOf("body", "responsebody", "errorbody")
        val statusNames = setOf(
            "statuscode",
            "httpstatuscode",
            "httpstatus",
            "responsestatuscode"
        )

        fun append(name: String, value: Any?) {
            when (name) {
                in codeNames -> codesAndTypes.append(value).append('\n')
                in bodyNames -> bodies.append(value).append('\n')
                in statusNames -> httpStatuses.append(value).append('\n')
            }
        }

        for (throwable in chain) {
            for (method in throwable.javaClass.methods) {
                if (method.parameterCount != 0) continue
                val normalized = method.name.removePrefix("get").lowercase()
                if (normalized !in codeNames &&
                    normalized !in bodyNames &&
                    normalized !in statusNames
                ) continue
                runCatching { method.invoke(throwable) }
                    .getOrNull()
                    ?.let { append(normalized, it) }
            }
            for (field in throwable.javaClass.declaredFields) {
                val normalized = field.name.lowercase()
                if (normalized !in codeNames &&
                    normalized !in bodyNames &&
                    normalized !in statusNames
                ) continue
                runCatching {
                    field.isAccessible = true
                    field.get(throwable)
                }.getOrNull()?.let { append(normalized, it) }
            }
        }
        return StructuredEvidence(
            codesAndTypes.toString(),
            bodies.toString(),
            httpStatuses.toString()
        )
    }

    private fun extractStructuredHttpStatus(statuses: String): Int? {
        val matches = Regex("""\b([1-5]\d{2})\b""").findAll(statuses)
        for (match in matches) {
            val code = match.groupValues[1].toIntOrNull() ?: continue
            if (code in 100..599) return code
        }
        return null
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var cur: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        while (cur != null && seen.add(cur)) {
            out.add(cur)
            cur = cur.cause
        }
        return out
    }

    private fun hasType(chain: List<Throwable>, simpleName: String): Boolean =
        chain.any { (it::class.simpleName ?: "").contains(simpleName) }

    /**
     * Last-resort HTTP status extraction from exception prose. This deliberately
     * runs after structured status accessors such as `statusCode`, so an upstream
     * message like "400 ERROR" cannot replace a concrete outer HTTP 429.
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
        for (pattern in patterns) {
            val code = pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (code in 100..599) return code
        }
        return null
    }
}
