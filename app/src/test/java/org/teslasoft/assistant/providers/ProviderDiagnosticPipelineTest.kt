package org.teslasoft.assistant.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenerationErrorClassifier

class ProviderDiagnosticPipelineTest {

    private fun classify(
        snapshot: ProviderDiagnosticSnapshot,
        message: String = "provider request failed"
    ) = GenerationErrorClassifier.classify(RuntimeException(message), snapshot)

    @Test fun non2xxHttpErrorKeepsExactProviderBodyAndOuterStatus() = runBlocking {
        val attempt = ProviderUsageAttempt("model", "configured fallback", "https://example")
        attempt.noteHttpResponse(
            401,
            """{"error":{"type":"authentication_error","message":"Exact key rejection"}}"""
        )
        val snapshot = attempt.diagnosticSnapshot()
        assertEquals(401, snapshot.outerHttpStatus)
        assertEquals(listOf("Exact key rejection"), snapshot.errorMessages)
        assertEquals(GenErrorCode.A1, classify(snapshot).code)
    }

    @Test fun http200SseErrorPreservesAtlasFixtureAndSeparatesStatuses() = runBlocking {
        val inspector = RawSseInspector()
        inspector.acceptLine(
            "data: {\"id\":\"gen-atlas\",\"provider\":\"AtlasCloud\"," +
                "\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\",\"content\":\"partial\"}," +
                "\"finish_reason\":null}]}"
        )
        inspector.acceptLine(
            "data: {\"id\":\"gen-atlas\",\"error\":{\"code\":502," +
                "\"message\":\"Output data may contain inappropriate content\"," +
                "\"metadata\":{\"error_type\":\"provider_unavailable\"," +
                "\"provider_name\":\"AtlasCloud\"}}," +
                "\"choices\":[{\"delta\":{},\"finish_reason\":\"error\"}]}"
        )
        val attempt = ProviderUsageAttempt("requested", "configured fallback", "https://openrouter.ai")
        attempt.beginObservation(200)
        attempt.noteRawObservation(inspector.finishNormally())
        attempt.finishObservation()
        val snapshot = attempt.diagnosticSnapshot()

        assertEquals(200, snapshot.outerHttpStatus)
        assertEquals(502, snapshot.embeddedHttpStatus)
        assertEquals("AtlasCloud", snapshot.actualServingProvider)
        assertEquals("error", snapshot.finishReason)
        assertEquals("gen-atlas", snapshot.generationId)
        assertEquals(ContentFilterSide.OUTPUT, snapshot.contentFilterSide)
        assertEquals(listOf("Output data may contain inappropriate content"), snapshot.errorMessages)
        assertTrue(snapshot.reasoningCharacters > 0)
        assertEquals(GenErrorCode.S4, classify(snapshot).code)
    }

    @Test fun inputFilterIsNotConfusedWithOutputFilter() {
        val event = ProviderDiagnosticParser.parseHttpBody(
            """{"error":{"code":"input_blocked","message":"Prompt rejected"}}""",
            400
        ).single()
        val snapshot = ProviderDiagnosticSnapshot("input", 400, events = listOf(event))
        assertEquals(ContentFilterSide.INPUT, snapshot.contentFilterSide)
        assertEquals(GenErrorCode.S3, classify(snapshot).code)
    }

    @Test fun ambiguousFilterDoesNotAccuseEitherSide() {
        val event = ProviderDiagnosticParser.parseHttpBody(
            """{"error":{"code":"content_filter","message":"Policy filter triggered"}}""",
            400
        ).single()
        val snapshot = ProviderDiagnosticSnapshot("ambiguous", 400, events = listOf(event))
        assertEquals(ContentFilterSide.AMBIGUOUS, snapshot.contentFilterSide)
        assertEquals(GenErrorCode.S5, classify(snapshot).code)
    }

    @Test fun anthropicAndGeminiShapesNormalizeWithoutUiSpecialCases() {
        val anthropic = ProviderDiagnosticParser.parseHttpBody(
            """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens is invalid"}}""",
            400
        ).single()
        assertEquals("invalid_request_error", anthropic.type)
        assertEquals("max_tokens is invalid", anthropic.message)
        assertEquals(
            GenErrorCode.M4,
            classify(ProviderDiagnosticSnapshot("anthropic", 400, events = listOf(anthropic))).code
        )

        val gemini = ProviderDiagnosticParser.parseSsePayload(
            """{"candidates":[{"finishReason":"SAFETY"}]}"""
        ).single()
        assertEquals(ContentFilterSide.OUTPUT, gemini.contentFilterSide)
        assertEquals("SAFETY", gemini.message)
    }

    @Test fun malformedSsePayloadIsParserFailureNotProviderFailure() {
        val inspector = RawSseInspector()
        inspector.acceptLine("data: {not-json")
        val raw = inspector.finishNormally()
        val snapshot = ProviderDiagnosticSnapshot(
            attemptId = "malformed",
            outerHttpStatus = 200,
            malformedPayloadCount = raw.malformedDataEvents
        )
        assertEquals(GenErrorCode.S2, classify(snapshot, "typed parser failed").code)
        assertTrue(snapshot.errorEvents.isEmpty())
    }

    @Test fun wrappedCancellationNeverBecomesProviderError() {
        val providerEvent = ProviderDiagnosticEvent(
            source = ProviderDiagnosticSource.SSE_EVENT,
            isError = true,
            isWarning = false,
            message = "provider-looking message"
        )
        val snapshot = ProviderDiagnosticSnapshot("cancel", 200, events = listOf(providerEvent))
        val result = GenerationErrorClassifier.classify(
            RuntimeException("wrapped", CancellationException("client cancelled")),
            snapshot
        )
        assertEquals(GenErrorCode.C1, result.code)
        assertNull(result.httpStatus)
        assertFalse(result.providerResponseReceived)
    }

    @Test fun unknownServingProviderRemainsNotReportedInsteadOfUsingFallback() = runBlocking {
        val attempt = ProviderUsageAttempt("requested", "Configured Provider", "https://direct")
        attempt.beginObservation(200)
        attempt.noteRawObservation(RawSseInspector().finishNormally())
        attempt.finishObservation()
        assertNull(attempt.diagnosticSnapshot().actualServingProvider)
        // Accounting may still use its separate fallback; diagnostics may not.
        assertEquals("Configured Provider", attempt.snapshot().provider)
    }

    @Test fun concurrentAttemptsCannotLeakProviderOrErrorEvidence() = runBlocking {
        val a = ProviderUsageAttempt("a", "fallback-a", "https://a")
        val b = ProviderUsageAttempt("b", "fallback-b", "https://b")
        val first = async {
            a.noteHttpResponse(
                502,
                """{"error":{"message":"A failed","metadata":{"provider_name":"Provider A"}}}"""
            )
            a.diagnosticSnapshot()
        }
        val second = async {
            b.noteHttpResponse(
                429,
                """{"error":{"message":"B throttled","metadata":{"provider_name":"Provider B"}}}"""
            )
            b.diagnosticSnapshot()
        }
        val sa = first.await()
        val sb = second.await()
        assertEquals("Provider A", sa.actualServingProvider)
        assertEquals(listOf("A failed"), sa.errorMessages)
        assertEquals("Provider B", sb.actualServingProvider)
        assertEquals(listOf("B throttled"), sb.errorMessages)
        assertFalse(sa.events.any { it.message == "B throttled" })
        assertFalse(sb.events.any { it.message == "A failed" })
    }

    @Test fun nonFatalWarningsArePreservedExactly() {
        val events = ProviderDiagnosticParser.parseSsePayload(
            """{"warning":{"message":"Exact provider warning"},"choices":[]}"""
        )
        val snapshot = ProviderDiagnosticSnapshot("warning", 200, events = events)
        assertEquals(listOf("Exact provider warning"), snapshot.warningMessages)
        assertTrue(snapshot.errorEvents.isEmpty())
    }

    @Test fun u0IsReservedForEvidenceWithNoKnownClassification() {
        assertEquals(
            GenErrorCode.U0,
            GenerationErrorClassifier.classify(RuntimeException("entirely novel local bug")).code
        )
        val upstream = ProviderDiagnosticEvent(
            source = ProviderDiagnosticSource.SSE_EVENT,
            isError = true,
            isWarning = false,
            errorType = "provider_unavailable",
            embeddedHttpStatus = 502,
            message = "upstream unavailable"
        )
        assertEquals(
            GenErrorCode.S6,
            classify(ProviderDiagnosticSnapshot("known", 200, events = listOf(upstream))).code
        )
    }
}
