package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.util.reachedServer

class ProviderStreamTerminalExceptionTest {

    @Test fun lifecycleRecorderPreservesTerminalReasonWithoutOwningFailureControl() {
        val recorder = ResponseLifecycleRecorder(
            turnId = "T-sse-error-ui",
            phase = ResponseLifecycle.PHASE_PRIMARY,
            apiProvider = "OpenRouter",
            apiEndpoint = "https://openrouter.ai/api/v1/",
            model = "moonshotai/kimi-k2.5",
            requestedMaxOutput = 8000,
            startUptimeMs = 0L
        )
        recorder.noteChunk(
            finishReason = "error",
            id = "gen-error",
            contentLength = 0,
            promptTokens = null,
            completionTokens = null,
            totalTokens = null
        )
        assertEquals("error", recorder.lastFinishReason)
    }

    @Test fun terminalExceptionDoesNotInventAnOuterHttpStatus() {
        val classified = org.teslasoft.assistant.util.GenerationErrorClassifier.classify(
            ProviderStreamTerminalException()
        )
        assertEquals(null, classified.httpStatus)
        assertEquals(org.teslasoft.assistant.util.GenErrorCode.U0, classified.code)
        assertEquals(false, classified.reachedServer())
    }
}
