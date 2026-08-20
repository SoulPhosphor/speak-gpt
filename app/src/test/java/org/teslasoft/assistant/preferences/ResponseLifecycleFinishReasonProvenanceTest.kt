package org.teslasoft.assistant.preferences

import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseLifecycleFinishReasonProvenanceTest {
    @Test fun networkFailureWithoutProviderFinishReasonReportsMissing() {
        val body = format("missing", ResponseLifecycle.Termination.NETWORK_ERROR,
            "SocketException: Software caused connection abort")
        assertTrue(body.contains("Finish Reason: missing"))
        assertTrue(body.contains("Finish Reason Received: false"))
    }

    @Test fun typedProviderErrorFinishReasonIsStillReceived() {
        val body = format("error", ResponseLifecycle.Termination.PROVIDER_ERROR, "provider stream terminated")
        assertTrue(body.contains("Finish Reason: error"))
        assertTrue(body.contains("Finish Reason Received: true"))
    }

    @Test fun rawProviderErrorWithoutFinishReasonDoesNotInventOne() {
        val recorder = recorder("T-no-finish")
        recorder.beginProviderObservation()
        recorder.noteActualModelProvider(RawStreamObservationCodec.encode(RawStreamObservation(
            sseDataEvents = 2, providerErrorReceived = true,
            providerErrorSummary = "upstream disconnected", flowEndedNormally = true)))
        recorder.finishProviderObservation()
        val body = format("missing", ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE, null, recorder.attemptId)
        assertTrue(body.contains("Termination Source: provider_error"))
        assertTrue(body.contains("Finish Reason: missing"))
        assertTrue(body.contains("Finish Reason Received: false"))
    }

    @Test fun rawProviderFinishReasonIsReceived() {
        val recorder = recorder("T-with-finish")
        recorder.beginProviderObservation()
        recorder.noteActualModelProvider(RawStreamObservationCodec.encode(RawStreamObservation(
            sseDataEvents = 2, providerErrorReceived = true, providerErrorSummary = "upstream disconnected",
            finishReason = "error", flowEndedNormally = true)))
        recorder.finishProviderObservation()
        val body = format("missing", ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE, null, recorder.attemptId)
        assertTrue(body.contains("Finish Reason: error"))
        assertTrue(body.contains("Finish Reason Received: true"))
    }

    private fun format(finish: String, termination: ResponseLifecycle.Termination, error: String?, attemptId: String? = null) =
        ResponseLifecycle.format(
            turnId = "T-finish", phase = ResponseLifecycle.PHASE_PRIMARY,
            apiProvider = "OpenRouter", apiEndpoint = "https://openrouter.ai/api/v1/",
            actualModelProvider = null, model = "model", outcome = ResponseLifecycle.Outcome.INCOMPLETE,
            finishReasonDisplay = finish, streamClosed = true, termination = termination,
            requestedMaxOutput = null, promptTokens = null, completionTokens = null, totalTokens = null,
            receivedCharacters = 0, durationMs = 10L, generationId = null, errorText = error, attemptId = attemptId)

    private fun recorder(turnId: String) = ResponseLifecycleRecorder(
        turnId, ResponseLifecycle.PHASE_PRIMARY, "OpenRouter", "https://openrouter.ai/api/v1/",
        "model", null, 0L)
}
