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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GenerationErrorClassifierTest {

    private fun code(t: Throwable) = GenerationErrorClassifier.classify(t).code

    private class StructuredProviderException(
        val code: String,
        message: String
    ) : RuntimeException(message)

    private class StructuredStatusProviderException(
        val statusCode: Int,
        val code: String,
        message: String
    ) : RuntimeException(message)

    // Mirrors the evidence shape exposed by openai-client 3.8.2 without taking
    // a unit-test dependency on that concrete exception class.
    private class RateLimitException(
        val statusCode: Int?,
        message: String
    ) : RuntimeException(message)

    // ---- transport / network (no HTTP status) --------------------------

    @Test fun connectionAbortIsN1() {
        assertEquals(GenErrorCode.N1, code(IOException("Software caused connection abort")))
    }

    @Test fun unknownHostIsN3() {
        assertEquals(GenErrorCode.N3, code(UnknownHostException("No address associated with hostname api.z.ai")))
    }

    @Test fun connectTimeoutIsN2() {
        assertEquals(GenErrorCode.N2, code(RuntimeException("Connect timeout has expired [url=..., connect_timeout=30000 ms]")))
    }

    @Test fun socketTimeoutIsN4() {
        assertEquals(GenErrorCode.N4, code(SocketTimeoutException("timeout")))
    }

    @Test fun readTimeoutMessageIsN4() {
        assertEquals(GenErrorCode.N4, code(RuntimeException("Socket timeout has expired [url=..., socket_timeout=600000 ms]")))
    }

    @Test fun transportFailuresHaveNoHttpStatus() {
        assertNull(GenerationErrorClassifier.classify(IOException("Software caused connection abort")).httpStatus)
    }

    // ---- auth / quota --------------------------------------------------

    @Test fun incorrectKeyIsA1() {
        assertEquals(GenErrorCode.A1, code(RuntimeException("Incorrect API key provided: sk-***")))
    }

    @Test fun http401IsA1() {
        assertEquals(GenErrorCode.A1, code(RuntimeException("Client request invalid: 401 Unauthorized")))
    }

    @Test fun quotaIsQ1() {
        assertEquals(GenErrorCode.Q1, code(RuntimeException("You exceeded your current quota, please check your plan")))
    }

    // ---- model / request ----------------------------------------------

    @Test fun maxTokensIsM3() {
        assertEquals(GenErrorCode.M3, code(RuntimeException("This model's maximum context length is 8192 tokens")))
    }

    @Test fun structuredContextCodeIsModelContext() {
        assertEquals(
            ProviderLimitKind.MODEL_CONTEXT,
            GenerationErrorClassifier.classify(
                RuntimeException("""{"error":{"code":"context_length_exceeded"}}""")
            ).providerLimit
        )
    }

    @Test fun explicitInputLimitStaysSeparateFromContext() {
        assertEquals(
            ProviderLimitKind.MODEL_INPUT,
            GenerationErrorClassifier.classify(
                RuntimeException("""{"error":{"code":"maximum_input_tokens"}}""")
            ).providerLimit
        )
    }

    @Test fun http413IsRequestBodyNotContext() {
        val result = GenerationErrorClassifier.classify(
            RuntimeException("HTTP 413 Payload Too Large")
        )
        assertEquals(ProviderLimitKind.REQUEST_BODY, result.providerLimit)
        assertEquals(413, result.httpStatus)
    }

    @Test fun throughputAndQuotaAreDistinguished() {
        assertEquals(
            ProviderLimitKind.RATE_OR_THROUGHPUT,
            GenerationErrorClassifier.classify(
                RuntimeException("429 Too Many Requests: tokens per minute exceeded")
            ).providerLimit
        )
        assertEquals(
            ProviderLimitKind.QUOTA_OR_SPENDING,
            GenerationErrorClassifier.classify(
                RuntimeException("""{"error":{"code":"insufficient_quota"}}""")
            ).providerLimit
        )
    }

    @Test fun http402IsOutOfCredits() {
        // HTTP 402 Payment Required is the unambiguous "no credits" case and
        // must never be reported as a rate limit or a generic quota cap.
        val result = GenerationErrorClassifier.classify(
            RuntimeException("Client request invalid: 402 Payment Required")
        )
        assertEquals(ProviderLimitKind.OUT_OF_CREDITS, result.providerLimit)
        assertEquals(402, result.httpStatus)
    }

    @Test fun insufficientCreditsBodyIsOutOfCreditsNotRate() {
        // OpenRouter's out-of-credits body, whose status is not cleanly scraped,
        // is still separated from a 429 throttle by its text.
        assertEquals(
            ProviderLimitKind.OUT_OF_CREDITS,
            GenerationErrorClassifier.classify(
                RuntimeException("""{"error":{"code":402,"message":"Insufficient credits. Add more using https://openrouter.ai/credits"}}""")
            ).providerLimit
        )
    }

    @Test fun structuredCodeWinsOverConflictingExceptionProse() {
        val result = GenerationErrorClassifier.classify(
            StructuredProviderException(
                "insufficient_quota",
                "wrapper mentioned maximum context length"
            )
        )
        assertEquals(ProviderLimitKind.QUOTA_OR_SPENDING, result.providerLimit)
    }

    @Test fun invalidModelIsM1() {
        assertEquals(GenErrorCode.M1, code(RuntimeException("you must provide a model parameter")))
    }

    @Test fun modelNotFoundIsM2EvenWith404() {
        // Priority ladder: a model-not-found body wins over the bare-404 rule.
        assertEquals(GenErrorCode.M2, code(RuntimeException("The model 'glm-4.7' does not exist (HTTP 404 Not Found)")))
    }

    // ---- server responses ---------------------------------------------

    @Test fun bare404IsS1() {
        assertEquals(GenErrorCode.S1, code(RuntimeException("404 Not Found")))
    }

    @Test fun explicit400StatusPreventsRateLimitClassGuess() {
        // The exception class says rate limit; the concrete 400 says otherwise.
        // It must not become Q1 or carry a provider limit. A client-error status
        // is reported as a refusal (M5), never as a rate limit by class name.
        val result = GenerationErrorClassifier.classify(
            RateLimitException(statusCode = 400, message = "ERROR")
        )
        assertEquals(400, result.httpStatus)
        assertEquals(GenErrorCode.M5, result.code)
        assertNull(result.providerLimit)
    }

    @Test fun client429StatusBeatsUpstream400ErrorText() {
        // OpenRouter can expose an outer HTTP status while metadata.raw names a
        // different upstream provider error. The client's concrete status wins
        // classification; the raw upstream detail remains separately displayable.
        val result = GenerationErrorClassifier.classify(
            RateLimitException(statusCode = 429, message = "400 ERROR")
        )
        assertEquals(429, result.httpStatus)
        assertEquals(ProviderLimitKind.RATE_OR_THROUGHPUT, result.providerLimit)
        assertEquals(GenErrorCode.Q1, result.code)
    }

    @Test fun literal400ErrorStillWorksAsLastResortWhenNoStatusPropertyExists() {
        // The status must still be recovered from bare text, and the recovered
        // client-error status is enough to report a refusal rather than an
        // unexplained failure. No limit may be invented from it.
        val result = GenerationErrorClassifier.classify(
            RuntimeException("400 ERROR")
        )
        assertEquals(400, result.httpStatus)
        assertEquals(GenErrorCode.M5, result.code)
        assertNull(result.providerLimit)
    }

    @Test fun http400GenericMaximumTextDoesNotBecomeProviderLimit() {
        // "maximum" is common in actionable validation errors and is not, by
        // itself, evidence of quota/context/rate limiting.
        val result = GenerationErrorClassifier.classify(
            RuntimeException("400 Bad Request: maximum value for temperature is 2")
        )
        assertEquals(400, result.httpStatus)
        assertEquals(GenErrorCode.M5, result.code)
        assertNull(result.providerLimit)
    }

    @Test fun structuredLimitCodeCanStillExplainHttp400() {
        val result = GenerationErrorClassifier.classify(
            StructuredStatusProviderException(
                statusCode = 400,
                code = "context_length_exceeded",
                message = "Bad Request"
            )
        )
        assertEquals(400, result.httpStatus)
        assertEquals(ProviderLimitKind.MODEL_CONTEXT, result.providerLimit)
        assertEquals(GenErrorCode.M3, result.code)
    }

    @Test fun rateLimitWrapperWithoutHttpStatusRemainsFallbackEvidence() {
        val result = GenerationErrorClassifier.classify(
            RateLimitException(statusCode = null, message = "request throttled by client wrapper")
        )
        assertNull(result.httpStatus)
        assertEquals(ProviderLimitKind.RATE_OR_THROUGHPUT, result.providerLimit)
        assertEquals(GenErrorCode.Q1, result.code)
    }

    @Test fun streamShapeIsS2() {
        assertEquals(GenErrorCode.S2, code(RuntimeException("io.ktor.client.call.NoTransformationFoundException: ...")))
    }

    @Test fun s2KeepsStackTrace() {
        assertEquals(true, GenErrorCode.S2.includeStackTrace)
    }

    @Test fun rejectedContentIsS3() {
        assertEquals(GenErrorCode.S3, code(RuntimeException("Your request was rejected as a result of our safety system")))
    }

    // ---- catch-all & cause unwrapping ---------------------------------

    @Test fun unknownFallsBackToU0() {
        assertEquals(GenErrorCode.U0, code(RuntimeException("some entirely novel failure")))
    }

    @Test fun u0KeepsStackTrace() {
        assertEquals(true, GenErrorCode.U0.includeStackTrace)
    }

    @Test fun wrappedCauseIsUnwrapped() {
        // The real cause is usually buried under a wrapper exception.
        assertEquals(GenErrorCode.N1, code(RuntimeException("generation failed", IOException("Software caused connection abort"))))
    }
}
