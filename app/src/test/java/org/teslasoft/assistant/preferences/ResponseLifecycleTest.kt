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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression matrix for generation termination semantics. */
class ResponseLifecycleTest {

    @Test fun zeroCharsNoFinishIsPrematureNotEmpty() {
        val r = ResponseLifecycle.classifyNormalCompletion(null, 0)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE, r.termination)
        assertEquals("missing", r.finishReasonDisplay)
        assertTrue(r.streamClosed)
    }

    @Test fun zeroCharsCleanStopIsEmpty() {
        val r = ResponseLifecycle.classifyNormalCompletion("stop", 0)
        assertEquals(ResponseLifecycle.Outcome.EMPTY, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
        assertFalse(r.streamClosed)
    }

    @Test fun charsNoFinishIsPremature() {
        val r = ResponseLifecycle.classifyNormalCompletion(null, 42)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE, r.termination)
    }

    @Test fun charsAndStopAreComplete() {
        val r = ResponseLifecycle.classifyNormalCompletion("stop", 42)
        assertEquals(ResponseLifecycle.Outcome.COMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
    }

    @Test fun lengthIsProviderDoneButIncomplete() {
        val r = ResponseLifecycle.classifyNormalCompletion("length", 42)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
        assertEquals("length", r.finishReasonDisplay)
    }

    @Test fun contentFilterIsProviderDoneButIncompleteWithText() {
        val r = ResponseLifecycle.classifyNormalCompletion("content_filter", 42)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
        assertEquals("content_filter", r.finishReasonDisplay)
        assertFalse(r.streamClosed)
    }

    @Test fun contentFilterIsNeverEmptyWithoutText() {
        val r = ResponseLifecycle.classifyNormalCompletion("content_filter", 0)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
    }

    @Test fun explicitProviderErrorFinishBeatsZeroContent() {
        val r = ResponseLifecycle.classifyNormalCompletion("error", 0)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_ERROR, r.termination)
    }

    @Test fun toolCallWithNoTextIsNormalCompletion() {
        val r = ResponseLifecycle.classifyNormalCompletion("tool_calls", 0)
        assertEquals(ResponseLifecycle.Outcome.COMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
    }

    @Test fun legacyFunctionCallWithNoTextIsNormalCompletion() {
        val r = ResponseLifecycle.classifyNormalCompletion("function_call", 0)
        assertEquals(ResponseLifecycle.Outcome.COMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
        assertFalse(r.streamClosed)
    }

    @Test fun networkExceptionClassification() {
        assertEquals(
            ResponseLifecycle.Termination.NETWORK_ERROR,
            ResponseLifecycle.classifyTerminalFailure(requestDispatched = true, networkError = true)
        )
    }

    @Test fun parserExceptionClassification() {
        assertEquals(
            ResponseLifecycle.Termination.PARSER_ERROR,
            ResponseLifecycle.classifyTerminalFailure(requestDispatched = true, parserError = true)
        )
    }

    @Test fun timeoutClassification() {
        assertEquals(
            ResponseLifecycle.Termination.CLIENT_TIMEOUT,
            ResponseLifecycle.classifyTerminalFailure(requestDispatched = true, clientTimeout = true)
        )
    }

    @Test fun userStopClassification() {
        assertEquals(
            ResponseLifecycle.Termination.USER_STOP,
            ResponseLifecycle.classifyTerminalFailure(
                requestDispatched = true,
                userStop = true,
                networkError = true
            )
        )
    }

    @Test fun appCancellationClassification() {
        assertEquals(
            ResponseLifecycle.Termination.APP_CANCEL,
            ResponseLifecycle.classifyTerminalFailure(
                requestDispatched = true,
                appCancel = true,
                providerError = true
            )
        )
    }

    @Test fun preDispatchTerminationIsNeverAttributedToProvider() {
        assertEquals(
            ResponseLifecycle.Termination.REQUEST_NOT_SENT,
            ResponseLifecycle.classifyTerminalFailure(
                requestDispatched = false,
                providerError = true,
                networkError = true,
                parserError = true,
                clientTimeout = true
            )
        )
    }

    @Test fun providerErrorClassificationAfterDispatch() {
        assertEquals(
            ResponseLifecycle.Termination.PROVIDER_ERROR,
            ResponseLifecycle.classifyTerminalFailure(requestDispatched = true, providerError = true)
        )
    }

    @Test fun actualProviderStartsUnknownAndDiagnosticEnvelopeCannotBecomeProviderName() {
        val recorder = recorder("T-provider")
        assertNull(recorder.actualModelProvider)
        recorder.noteActualModelProvider("  Open Inference  ")
        assertEquals("Open Inference", recorder.actualModelProvider)

        recorder.noteActualModelProvider(
            RawStreamObservationCodec.encode(
                RawStreamObservation(sseDataEvents = 1, flowEndedNormally = true)
            )
        )
        assertEquals("Open Inference", recorder.actualModelProvider)
        LifecycleDiagnosticEvidenceStore.takeAndClose(recorder.attemptId)
    }

    @Test fun prematureCloseNeverLogsErrorNoneReported() {
        val body = ResponseLifecycle.format(
            turnId = "T-premature", phase = ResponseLifecycle.PHASE_PRIMARY,
            apiProvider = "OpenRouter", apiEndpoint = "https://openrouter.ai/api/v1/",
            actualModelProvider = "StreamLake", model = "moonshotai/kimi-k2.5",
            outcome = ResponseLifecycle.Outcome.INCOMPLETE, finishReasonDisplay = "missing",
            streamClosed = true, termination = ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE,
            requestedMaxOutput = 8000, promptTokens = null, completionTokens = null,
            totalTokens = null, receivedCharacters = 0, durationMs = 37007,
            generationId = "gen-123", errorText = null
        )
        assertTrue(body.contains("Outcome: Incomplete"))
        assertTrue(body.contains("Termination Source: premature_stream_close"))
        assertTrue(body.contains("Generation ID Received: true"))
        assertFalse(body.contains("Error: none reported"))
        assertTrue(body.contains("typed stream ended without provider finish_reason"))
    }

    @Test fun cleanEmptyCompletionMayLegitimatelyHaveNoError() {
        val body = ResponseLifecycle.format(
            turnId = "T-empty", phase = ResponseLifecycle.PHASE_PRIMARY,
            apiProvider = "OpenRouter", apiEndpoint = "https://openrouter.ai/api/v1/",
            actualModelProvider = null, model = "model",
            outcome = ResponseLifecycle.Outcome.EMPTY, finishReasonDisplay = "stop",
            streamClosed = false, termination = ResponseLifecycle.Termination.PROVIDER_DONE,
            requestedMaxOutput = null, promptTokens = 10, completionTokens = 0,
            totalTokens = 10, receivedCharacters = 0, durationMs = 12,
            generationId = "gen-empty", errorText = null
        )
        assertTrue(body.contains("Outcome: Empty"))
        assertTrue(body.contains("Finish Reason: stop"))
        assertTrue(body.contains("Error: none reported"))
    }

    @Test fun rawProviderErrorOverridesTypedNormalEof() {
        val recorder = recorder("T-sse-error")
        recorder.beginProviderObservation()
        recorder.noteActualModelProvider(
            RawStreamObservationCodec.encode(
                RawStreamObservation(
                    sseDataEvents = 2,
                    providerErrorReceived = true,
                    providerErrorSummary = "code=502; upstream disconnected",
                    finishReason = "error",
                    flowEndedNormally = true
                )
            )
        )
        recorder.finishProviderObservation()

        val body = ResponseLifecycle.format(
            turnId = "T-sse-error", phase = ResponseLifecycle.PHASE_PRIMARY,
            apiProvider = "OpenRouter", apiEndpoint = "https://openrouter.ai/api/v1/",
            actualModelProvider = null, model = "model",
            outcome = ResponseLifecycle.Outcome.INCOMPLETE, finishReasonDisplay = "missing",
            streamClosed = true, termination = ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE,
            requestedMaxOutput = null, promptTokens = null, completionTokens = null,
            totalTokens = null, receivedCharacters = 0, durationMs = 20,
            generationId = null, errorText = null, attemptId = recorder.attemptId
        )
        assertTrue(body.contains("Attempt ID: ${recorder.attemptId}"))
        assertTrue(body.contains("HTTP Status Successful: true"))
        assertTrue(body.contains("Provider SSE Error Received: true"))
        assertTrue(body.contains("Termination Source: provider_error"))
        assertTrue(body.contains("Error: code=502; upstream disconnected"))
    }

    @Test fun rawFinishAndDoneCanRecoverTerminalFactsTypedClientMissed() {
        val recorder = recorder("T-raw-stop")
        recorder.beginProviderObservation()
        recorder.noteActualModelProvider(
            RawStreamObservationCodec.encode(
                RawStreamObservation(
                    sseDataEvents = 3,
                    finishReason = "stop",
                    receivedDone = true,
                    protocolTerminalMarker = "[DONE]",
                    usageReceived = true,
                    promptTokens = 11,
                    completionTokens = 0,
                    totalTokens = 11,
                    generationId = "gen-from-raw",
                    flowEndedNormally = true
                )
            )
        )
        recorder.finishProviderObservation()

        val body = ResponseLifecycle.format(
            turnId = "T-raw-stop", phase = ResponseLifecycle.PHASE_PRIMARY,
            apiProvider = "OpenRouter", apiEndpoint = "https://openrouter.ai/api/v1/",
            actualModelProvider = null, model = "model",
            outcome = ResponseLifecycle.Outcome.INCOMPLETE, finishReasonDisplay = "missing",
            streamClosed = true, termination = ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE,
            requestedMaxOutput = null, promptTokens = recorder.promptTokens,
            completionTokens = recorder.completionTokens, totalTokens = recorder.totalTokens,
            receivedCharacters = 0, durationMs = 20,
            generationId = recorder.generationId, errorText = null, attemptId = recorder.attemptId
        )
        assertTrue(body.contains("Outcome: Empty"))
        assertTrue(body.contains("Finish Reason: stop"))
        assertTrue(body.contains("Received Done: true"))
        assertTrue(body.contains("Usage Metadata Received: true"))
        assertTrue(body.contains("Generation ID: gen-from-raw"))
    }

    @Test fun responseEntryStillContainsExistingUsefulFields() {
        val body = ResponseLifecycle.format(
            turnId = "T-fields", phase = ResponseLifecycle.PHASE_TOOL_CONTINUATION,
            apiProvider = "OpenAI", apiEndpoint = "https://api.openai.com/v1/",
            actualModelProvider = null, model = "model",
            outcome = ResponseLifecycle.Outcome.COMPLETE, finishReasonDisplay = "stop",
            streamClosed = false, termination = ResponseLifecycle.Termination.PROVIDER_DONE,
            requestedMaxOutput = 8000, promptTokens = 30, completionTokens = 40,
            totalTokens = 70, receivedCharacters = 210, durationMs = 900,
            generationId = "gen-xyz", errorText = null
        )
        assertTrue(body.contains("Outcome: Complete"))
        assertTrue(body.contains("Termination Source: provider_done"))
        assertTrue(body.contains("Configured API Provider: OpenAI"))
        assertTrue(body.contains("Actual Model Provider (API response): not reported by API"))
        assertTrue(body.contains("Reasoning Tokens: not reported"))
        assertTrue(body.contains("Provider Cost: not reported"))
        assertTrue(Regex("(?m)^Outcome: Complete$").containsMatchIn(body))
    }

    private fun recorder(turnId: String) = ResponseLifecycleRecorder(
        turnId = turnId,
        phase = ResponseLifecycle.PHASE_PRIMARY,
        apiProvider = "OpenRouter",
        apiEndpoint = "https://openrouter.ai/api/v1/",
        model = "model",
        requestedMaxOutput = 8000,
        startUptimeMs = 0L
    )
}
