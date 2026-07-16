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

import com.aallam.openai.api.exception.OpenAIError
import com.aallam.openai.api.exception.UnknownAPIException
import com.openai.core.http.Headers
import com.openai.errors.UnexpectedStatusCodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GenerationErrorClassifierTest {

    private fun code(t: Throwable) = GenerationErrorClassifier.classify(t).code

    // ---- transport / network (no HTTP status) --------------------------

    @Test fun connectionAbortIsN1() {
        assertEquals(GenErrorCode.N1, code(IOException("Software caused connection abort")))
    }

    @Test fun unknownHostIsN3() {
        assertEquals(GenErrorCode.N3, code(UnknownHostException("No address associated with hostname api.z.ai")))
    }

    @Test fun socketTimeoutIsN2() {
        assertEquals(GenErrorCode.N2, code(SocketTimeoutException("timeout")))
    }

    @Test fun socketTimeoutBeforeDataIsReadTimeout() {
        val state = requestState()
        val result = GenerationErrorClassifier.classify(SocketTimeoutException("timeout"), state)
        assertEquals(GenFailureKind.NO_RESPONSE_DATA_TIMEOUT, result.kind)
    }

    @Test fun socketTimeoutAfterStreamEventIsStreamingInactivity() {
        val state = requestState().apply { firstStreamEventArrived = true }
        val result = GenerationErrorClassifier.classify(SocketTimeoutException("timeout"), state)
        assertEquals(GenFailureKind.STREAM_INACTIVITY_TIMEOUT, result.kind)
    }

    @Test fun socketTimeoutAfterResponseTextIsStreamingInactivity() {
        val state = requestState().apply { responseTextArrived = true }
        val result = GenerationErrorClassifier.classify(SocketTimeoutException("timeout"), state)
        assertEquals(GenFailureKind.STREAM_INACTIVITY_TIMEOUT, result.kind)
    }

    @Test fun connectTimeoutIsDistinctAndUsesAndroidEngineLimit() {
        val result = GenerationErrorClassifier.classify(TestConnectTimeoutException())
        assertEquals(GenErrorCode.N2, result.code)
        assertEquals(GenFailureKind.CONNECT_TIMEOUT, result.kind)
        assertEquals(100_000L, result.configuredTimeoutMillis)
    }

    @Test fun overallRequestTimeoutIsDistinct() {
        val result = GenerationErrorClassifier.classify(
            TestHttpRequestTimeoutException("request_timeout=45000 ms")
        )
        assertEquals(GenErrorCode.N2, result.code)
        assertEquals(GenFailureKind.OVERALL_TIMEOUT, result.kind)
        assertEquals(45_000L, result.configuredTimeoutMillis)
    }

    @Test fun gptImageCallTimeoutUsesOpenAiJavaOverallLimit() {
        val state = requestState(operation = "GPT Image generation", streaming = false)
        val result = GenerationErrorClassifier.classify(InterruptedIOException("timeout"), state)
        assertEquals(GenFailureKind.OVERALL_TIMEOUT, result.kind)
        assertEquals(600_000L, result.configuredTimeoutMillis)
    }

    @Test fun socketTimeoutDurationIsExtracted() {
        val result = GenerationErrorClassifier.classify(
            SocketTimeoutException("socket_timeout=30000 ms"), requestState()
        )
        assertEquals(30_000L, result.configuredTimeoutMillis)
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

    @Test fun typedHttp429IsRateLimitedHttpResponse() {
        val result = GenerationErrorClassifier.classify(typedHttpError(429))
        assertEquals(GenErrorCode.Q1, result.code)
        assertEquals(429, result.httpStatus)
        assertEquals(GenFailureKind.HTTP_RESPONSE, result.kind)
    }

    // ---- model / request ----------------------------------------------

    @Test fun maxTokensIsM3() {
        assertEquals(GenErrorCode.M3, code(RuntimeException("This model's maximum context length is 8192 tokens")))
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

    @Test fun typedTemporaryHttpStatusesArePreserved() {
        for (status in listOf(408, 502, 503, 504)) {
            val result = GenerationErrorClassifier.classify(typedHttpError(status))
            assertEquals(GenErrorCode.S4, result.code)
            assertEquals(status, result.httpStatus)
            assertEquals(GenFailureKind.HTTP_RESPONSE, result.kind)
        }
    }

    @Test fun openAiJavaTypedStatusIsPreservedForGptImagePath() {
        val error = UnexpectedStatusCodeException.builder()
            .statusCode(503)
            .headers(Headers.builder().build())
            .build()
        val result = GenerationErrorClassifier.classify(error)

        assertEquals(GenErrorCode.S4, result.code)
        assertEquals(503, result.httpStatus)
        assertEquals(GenFailureKind.HTTP_RESPONSE, result.kind)
    }

    @Test fun otherTypedHttpStatusIsStillPreserved() {
        val result = GenerationErrorClassifier.classify(typedHttpError(418))
        assertEquals(GenErrorCode.U0, result.code)
        assertEquals(418, result.httpStatus)
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

    private fun requestState(
        operation: String = "test",
        streaming: Boolean = true,
    ) = GenerationRequestState(
        startedAtMillis = 1L,
        operation = operation,
        provider = "test-provider",
        model = "test-model",
        streaming = streaming,
    )

    private fun typedHttpError(status: Int) = UnknownAPIException(status, OpenAIError())

    private class TestConnectTimeoutException : IOException("Connect timeout has expired")
    private class TestHttpRequestTimeoutException(message: String) : IOException(message)
}
