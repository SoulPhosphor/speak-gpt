package org.teslasoft.assistant.preferences

import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseLifecycleWrappedCauseTest {

    @Test fun wrappedParserFallbackIsLoggedAsParserErrorNotNetwork() {
        val body = formatNetworkFallback(
            turnId = "T-wrapped-parser",
            error = "NoTransformationFoundException: Expected response body of the type ChatCompletionChunk"
        )
        assertTrue(body.contains("Termination Source: parser_error"))
    }

    @Test fun wrappedTimeoutFallbackIsLoggedAsClientTimeoutNotNetwork() {
        val body = formatNetworkFallback(
            turnId = "T-wrapped-timeout",
            error = "IOException: caused by SocketTimeoutException: timeout while reading stream"
        )
        assertTrue(body.contains("Termination Source: client_timeout"))
    }

    private fun formatNetworkFallback(turnId: String, error: String): String =
        ResponseLifecycle.format(
            turnId = turnId,
            phase = ResponseLifecycle.PHASE_PRIMARY,
            apiProvider = "OpenRouter",
            apiEndpoint = "https://openrouter.ai/api/v1/",
            actualModelProvider = null,
            model = "model",
            outcome = ResponseLifecycle.Outcome.INCOMPLETE,
            finishReasonDisplay = "error",
            streamClosed = true,
            termination = ResponseLifecycle.Termination.NETWORK_ERROR,
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
