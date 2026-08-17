package org.teslasoft.assistant.preferences

import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseLifecycleEvidenceTruthTest {

    @Test fun transportFailureWithoutResponseEvidenceDoesNotClaimDispatch() {
        val body = format(
            turnId = "T-network-unconfirmed",
            termination = ResponseLifecycle.Termination.NETWORK_ERROR,
            error = "UnknownHostException: no address associated with hostname"
        )
        assertTrue(body.contains("Request Dispatched: not confirmed"))
        assertTrue(body.contains("HTTP Status Successful: not observed"))
        assertTrue(body.contains("Raw SSE Events Received: not observed"))
        assertTrue(body.contains("Provider SSE Error Received: not observed"))
        assertTrue(body.contains("Raw SSE Flow Exception: not observed"))
    }

    @Test fun preDispatchTerminationIsExplicitlyFalse() {
        val body = format(
            turnId = "T-not-sent",
            termination = ResponseLifecycle.Termination.REQUEST_NOT_SENT,
            error = null
        )
        assertTrue(body.contains("Request Dispatched: false"))
    }

    private fun format(
        turnId: String,
        termination: ResponseLifecycle.Termination,
        error: String?
    ): String = ResponseLifecycle.format(
        turnId = turnId,
        phase = ResponseLifecycle.PHASE_PRIMARY,
        apiProvider = "OpenRouter",
        apiEndpoint = "https://openrouter.ai/api/v1/",
        actualModelProvider = null,
        model = "model",
        outcome = ResponseLifecycle.Outcome.INCOMPLETE,
        finishReasonDisplay = "error",
        streamClosed = true,
        termination = termination,
        requestedMaxOutput = null,
        promptTokens = null,
        completionTokens = null,
        totalTokens = null,
        receivedCharacters = 0,
        durationMs = 10,
        generationId = null,
        errorText = error
    )
}
