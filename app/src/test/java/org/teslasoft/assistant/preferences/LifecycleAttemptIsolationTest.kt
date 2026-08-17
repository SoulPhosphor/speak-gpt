package org.teslasoft.assistant.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request provenance regressions: same turn/phase retries must never share raw evidence. */
class LifecycleAttemptIsolationTest {

    @Test fun delayedObserverFromAttemptACannotWriteIntoAttemptB() {
        val attemptA = recorder("T-retry")
        attemptA.beginProviderObservation()
        assertTrue(LifecycleDiagnosticEvidenceStore.isOpen(attemptA.attemptId))

        // Attempt A's typed side finishes and its bounded observer grace expires.
        // Formatting is the atomic close/consume boundary for A's evidence.
        val bodyA = formatPremature(attemptA)
        assertTrue(bodyA.contains("Attempt ID: ${attemptA.attemptId}"))
        assertFalse(LifecycleDiagnosticEvidenceStore.isOpen(attemptA.attemptId))

        // A retry reuses the SAME turn and phase, but receives a distinct request identity.
        val attemptB = recorder("T-retry")
        assertNotEquals(attemptA.attemptId, attemptB.attemptId)
        attemptB.beginProviderObservation()

        // The old HTTP observer finally drains after B has begun. This used to
        // recreate the shared turn+phase bucket and poison B with A's terminal facts.
        attemptA.noteActualModelProvider(
            RawStreamObservationCodec.encode(
                RawStreamObservation(
                    sseDataEvents = 4,
                    providerErrorReceived = true,
                    providerErrorSummary = "ATTEMPT_A_UPSTREAM_FAILURE",
                    finishReason = "error",
                    generationId = "gen-attempt-a",
                    flowEndedNormally = true
                )
            )
        )
        attemptA.finishProviderObservation()

        // B receives its own clean terminal wire evidence.
        attemptB.noteActualModelProvider(
            RawStreamObservationCodec.encode(
                RawStreamObservation(
                    sseDataEvents = 2,
                    finishReason = "stop",
                    receivedDone = true,
                    protocolTerminalMarker = "[DONE]",
                    generationId = "gen-attempt-b",
                    flowEndedNormally = true
                )
            )
        )
        attemptB.finishProviderObservation()

        val bodyB = formatPremature(attemptB)
        assertTrue(bodyB.contains("Attempt ID: ${attemptB.attemptId}"))
        assertTrue(bodyB.contains("Generation ID: gen-attempt-b"))
        assertTrue(bodyB.contains("Finish Reason: stop"))
        assertTrue(bodyB.contains("Received Done: true"))
        assertFalse(bodyB.contains("gen-attempt-a"))
        assertFalse(bodyB.contains("ATTEMPT_A_UPSTREAM_FAILURE"))
        assertFalse(bodyB.contains("Provider SSE Error Received: true"))
    }

    @Test fun finalizedRecorderStillAcceptsRawEvidenceUntilAtomicClose() {
        val recorder = recorder("T-grace")
        recorder.beginProviderObservation()
        recorder.markFinalized()

        // markFinalized() happens before ChatActivity's bounded observer wait.
        // The attempt slot, not this boolean, is the forensic ownership gate.
        assertTrue(recorder.finalized)
        assertTrue(LifecycleDiagnosticEvidenceStore.isOpen(recorder.attemptId))
        recorder.noteActualModelProvider(
            RawStreamObservationCodec.encode(
                RawStreamObservation(
                    sseDataEvents = 2,
                    finishReason = "stop",
                    receivedDone = true,
                    protocolTerminalMarker = "[DONE]",
                    generationId = "gen-within-grace",
                    flowEndedNormally = true
                )
            )
        )
        recorder.finishProviderObservation()

        val body = formatPremature(recorder)
        assertTrue(body.contains("Generation ID: gen-within-grace"))
        assertTrue(body.contains("Finish Reason: stop"))
        assertTrue(body.contains("Received Done: true"))
        assertFalse(LifecycleDiagnosticEvidenceStore.isOpen(recorder.attemptId))
    }

    @Test fun closedAttemptRejectsLateRawEvidenceRatherThanReopening() {
        val recorder = recorder("T-closed")
        val id = recorder.attemptId
        assertTrue(LifecycleDiagnosticEvidenceStore.isOpen(id))
        LifecycleDiagnosticEvidenceStore.takeAndClose(id)
        assertFalse(LifecycleDiagnosticEvidenceStore.isOpen(id))

        recorder.noteActualModelProvider(
            RawStreamObservationCodec.encode(
                RawStreamObservation(
                    providerErrorReceived = true,
                    finishReason = "error",
                    generationId = "late-id",
                    flowEndedNormally = true
                )
            )
        )

        assertFalse(LifecycleDiagnosticEvidenceStore.isOpen(id))
        assertFalse(recorder.generationId == "late-id")
        assertFalse(recorder.lastFinishReason == "error")
    }

    private fun formatPremature(recorder: ResponseLifecycleRecorder): String =
        ResponseLifecycle.format(
            turnId = recorder.turnId,
            phase = recorder.phase,
            apiProvider = recorder.apiProvider,
            apiEndpoint = recorder.apiEndpoint,
            actualModelProvider = recorder.actualModelProvider,
            model = recorder.model,
            outcome = ResponseLifecycle.Outcome.INCOMPLETE,
            finishReasonDisplay = "missing",
            streamClosed = true,
            termination = ResponseLifecycle.Termination.PREMATURE_STREAM_CLOSE,
            requestedMaxOutput = recorder.requestedMaxOutput,
            promptTokens = recorder.promptTokens,
            completionTokens = recorder.completionTokens,
            totalTokens = recorder.totalTokens,
            receivedCharacters = recorder.receivedCharacters,
            durationMs = 1,
            generationId = recorder.generationId,
            errorText = null,
            attemptId = recorder.attemptId
        )

    private fun recorder(turnId: String) = ResponseLifecycleRecorder(
        turnId = turnId,
        phase = ResponseLifecycle.PHASE_PRIMARY,
        apiProvider = "OpenRouter",
        apiEndpoint = "https://openrouter.ai/api/v1/",
        model = "moonshotai/kimi-k2.5",
        requestedMaxOutput = 8000,
        startUptimeMs = 0L
    )
}
