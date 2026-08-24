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

import kotlinx.coroutines.CancellationException
import org.teslasoft.assistant.providers.ContentFilterSide
import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
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
    C1("C1", false), // client/app cancellation wrapped by a transport/client exception
    A1("A1", false), // API key rejected
    A2("A2", false), // authenticated but forbidden / access denied
    M1("M1", false), // no model set on the request
    M2("M2", false), // named model not available on the endpoint
    M3("M3", false), // context length exceeded
    M4("M4", false), // unsupported/invalid request parameter
    Q1("Q1", false), // quota / usage limit reached
    S1("S1", false), // bare HTTP 404 / Not Found
    S2("S2", true),  // response could not be read as the expected stream
    S3("S3", false), // request rejected as inappropriate content
    S4("S4", false), // generated output stopped by a content filter
    S5("S5", false), // content filter fired but provider did not identify side
    S6("S6", false), // provider/upstream/routing service failure
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
    val isVisionRejection: Boolean = false,
    val embeddedProviderStatus: Int? = null,
    val contentFilterSide: ContentFilterSide = ContentFilterSide.NONE,
    val providerResponseReceived: Boolean = false
)

/**
 * Pure, framework-free classifier that maps a generation failure to a
 * [GenErrorCode]. Deliberately free of Android and of the OpenAI client's types:
 * it is unit-tested on a plain JVM, and must not fail to load if a client
 * exception class is renamed in a dependency bump.
 *
 * It follows the hybrid strategy in ERROR_CODES.md section 7. Concrete server
 * evidence outranks client-wrapper guesses: a status exposed by the client
 * exception (such as openai-client's `statusCode`) wins before status-like prose,
 * then provider code/body evidence, then raw error text as the fallback.
 */
object GenerationErrorClassifier {

    fun classify(
        error: Throwable,
        providerEvidence: ProviderDiagnosticSnapshot? = null
    ): GenErrorResult {
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
        val status = providerEvidence?.outerHttpStatus
            ?: extractStructuredHttpStatus(structured.httpStatuses)
            ?: extractHttpStatus(text)
        val lower = text.lowercase()
        val providerText = providerEvidence?.events.orEmpty().joinToString("\n") { event ->
            (listOfNotNull(event.code, event.type, event.errorType, event.message) +
                event.additionalMessages + listOfNotNull(event.rawPayload))
                .joinToString("\n")
        }.lowercase()
        val allEvidenceText = "$lower\n$providerText"
        val embeddedStatus = providerEvidence?.embeddedHttpStatus
        val responseReceived = providerEvidence?.providerResponded == true || status != null

        fun result(
            code: GenErrorCode,
            providerLimit: ProviderLimitKind? = null,
            vision: Boolean = false,
            filterSide: ContentFilterSide = providerEvidence?.contentFilterSide
                ?: ContentFilterSide.NONE
        ) = GenErrorResult(
            code = code,
            httpStatus = status,
            providerLimit = providerLimit,
            isVisionRejection = vision,
            embeddedProviderStatus = embeddedStatus,
            contentFilterSide = filterSide,
            providerResponseReceived = responseReceived
        )

        // A cancellation remains a client/app event even when a wrapper adds
        // provider-looking prose. It must never become a provider failure.
        if (chain.any { it is CancellationException }) {
            return GenErrorResult(GenErrorCode.C1, null)
        }

        // Structured side attribution outranks generic provider status/type.
        // In particular, OpenRouter may call an AtlasCloud filtered-output
        // termination `provider_unavailable`; the output-side evidence is still
        // the honest user-facing classification.
        when (providerEvidence?.contentFilterSide) {
            ContentFilterSide.INPUT -> return result(GenErrorCode.S3)
            ContentFilterSide.OUTPUT -> return result(GenErrorCode.S4)
            ContentFilterSide.AMBIGUOUS -> return result(GenErrorCode.S5)
            else -> Unit
        }

        // 1. Auth.
        if (status == 401 || allEvidenceText.contains("incorrect api key") ||
            (status == null && hasType(chain, "AuthenticationException"))
        ) {
            return result(GenErrorCode.A1)
        }
        if (status == 403 && !providerText.contains("content_filter") &&
            !providerText.contains("content policy")
        ) {
            return result(GenErrorCode.A2)
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

        // 3. Provider limits. HTTP 413 is explicit request-body evidence.
        // Otherwise a structured provider code/type/body wins before any
        // fallback inspection of exception prose.
        if (status == 413) {
            return providerLimitResult(ProviderLimitKind.REQUEST_BODY, status, providerEvidence)
        }
        // HTTP 402 Payment Required is the unambiguous "no credits" signal —
        // kept ahead of the throttle/quota checks so an empty balance is never
        // reported as a rate limit.
        if (status == 402) {
            return providerLimitResult(ProviderLimitKind.OUT_OF_CREDITS, status, providerEvidence)
        }
        providerLimitFromEvidence(providerText)?.let {
            return providerLimitResult(it, status, providerEvidence)
        }
        providerLimitFromEvidence(structuredCodes)?.let {
            return providerLimitResult(it, status, providerEvidence)
        }
        providerLimitFromEvidence(structuredBodies)?.let {
            return providerLimitResult(it, status, providerEvidence)
        }
        providerLimitFromEvidence(lower)?.let {
            return providerLimitResult(it, status, providerEvidence)
        }
        // The concrete HTTP status wins over the SDK wrapper class. A class-name
        // match is useful only when the client exposed no status at all.
        if (status == 429 || (status == null && hasType(chain, "RateLimitException"))) {
            return providerLimitResult(ProviderLimitKind.RATE_OR_THROUGHPUT, status, providerEvidence)
        }

        // 4. Model-specific. A model-not-found body is M2 even when the HTTP
        //    status is 404, so this is checked before the bare-404 rule below.
        if (allEvidenceText.contains("invalid model") ||
            allEvidenceText.contains("you must provide a model")
        ) {
            return result(GenErrorCode.M1)
        }
        if (allEvidenceText.contains("does not exist")) {
            return result(GenErrorCode.M2)
        }
        // 5. Bare HTTP 404 with no model-specific body. Text is fallback evidence
        // only when the client did not expose a concrete status.
        if (status == 404 || (status == null && lower.contains("not found"))) {
            return result(GenErrorCode.S1).copy(httpStatus = status ?: 404)
        }
        // 6. Response-shape failure / content rejection.
        if (lower.contains("notransformationfoundexception") ||
            lower.contains("expected response body of the type")
        ) {
            return result(GenErrorCode.S2)
        }
        if (allEvidenceText.contains("your request was rejected")) {
            return result(GenErrorCode.S3, vision = looksLikeVisionRejection(allEvidenceText),
                filterSide = ContentFilterSide.INPUT)
        }
        if (providerEvidence?.malformedPayloadCount?.let { it > 0 } == true &&
            providerEvidence.errorEvents.isEmpty()
        ) return result(GenErrorCode.S2)

        val normalizedProviderTypes = providerEvidence?.errorEvents.orEmpty()
            .flatMap { listOfNotNull(it.code, it.type, it.errorType) }
            .map { it.lowercase() }
        if (normalizedProviderTypes.any {
                it in setOf(
                    "invalid_request_error", "invalid_argument", "unsupported_parameter",
                    "unsupported_value", "unknown_parameter", "parameter_not_supported"
                )
            }
        ) return result(GenErrorCode.M4)

        if (normalizedProviderTypes.any {
                it in setOf(
                    "provider_unavailable", "provider_error", "upstream_error",
                    "routing_error", "no_available_providers", "service_unavailable"
                )
            } || (status != null && status in 500..599) ||
            (embeddedStatus != null && embeddedStatus in 500..599)
        ) return result(GenErrorCode.S6)
        // Do not infer a generic provider limit from vague words such as
        // "limit" or "maximum" in a 400/422. If no specific recognized provider
        // evidence exists, preserve the request rejection as the unknown bucket
        // and let the raw provider/client detail explain it.
        // 7. Unknown catch-all.
        return result(GenErrorCode.U0, vision = looksLikeVisionRejection(allEvidenceText))
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
        status: Int?,
        providerEvidence: ProviderDiagnosticSnapshot? = null
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
        return GenErrorResult(
            code = code,
            httpStatus = status,
            providerLimit = kind,
            embeddedProviderStatus = providerEvidence?.embeddedHttpStatus,
            contentFilterSide = providerEvidence?.contentFilterSide ?: ContentFilterSide.NONE,
            providerResponseReceived = providerEvidence?.providerResponded == true || status != null
        )
    }

    /**
     * Common SDK exceptions expose provider code/type/body/status as no-argument
     * accessors or fields. Read those conservatively so concrete client evidence
     * wins even when the exception's prose is localized or generic.
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
                val normalized = method.name
                    .removePrefix("get")
                    .lowercase()
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
     * This runs only after structured status accessors such as `statusCode`, so
     * an embedded upstream error cannot replace the HTTP status the client saw.
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
