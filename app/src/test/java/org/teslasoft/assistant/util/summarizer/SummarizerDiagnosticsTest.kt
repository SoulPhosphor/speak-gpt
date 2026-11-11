package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

class SummarizerDiagnosticsTest {
    private fun request(status: Int? = null, body: String? = null) =
        SummarizerDiagnostics.RequestEvidence().also { it.dispatched = true; it.status = status; it.body = body }

    private fun owner(error: Throwable, evidence: SummarizerDiagnostics.RequestEvidence) =
        SummarizerDiagnostics.owner(error, GenerationErrorClassifier.classify(error, evidence.snapshot()), evidence)

    @Test fun httpRejectionsAndProviderFailuresAreExternal() {
        for (status in listOf(401, 403, 404, 413, 429, 500, 502)) {
            val evidence = request(status, """{"error":{"message":"Provider rejected request","code":"provider_error"}}""")
            assertEquals(SummarizerDiagnostics.Owner.EXTERNAL, owner(Exception("HTTP failure"), evidence))
        }
    }

    @Test fun embeddedQuotaAndContentRejectionAreExternalEvenWithHttp200() {
        for (type in listOf("insufficient_quota", "rate_limit_exceeded", "content_filter")) {
            val evidence = request(200, """{"error":{"message":"Rejected","type":"$type"}}""")
            assertEquals(SummarizerDiagnostics.Owner.EXTERNAL, owner(Exception("error"), evidence))
        }
    }

    @Test fun transportTimeoutsAndDnsWithoutResponseAreExternal() {
        assertEquals(SummarizerDiagnostics.Owner.EXTERNAL, owner(SocketTimeoutException("connect timeout"), request()))
        assertEquals(SummarizerDiagnostics.Owner.EXTERNAL, owner(SocketTimeoutException("read timed out"), request()))
        assertEquals(SummarizerDiagnostics.Owner.EXTERNAL, owner(UnknownHostException("offline"), request()))
    }

    @Test fun internalBugIsLocalEvenWhenResponseArrived() {
        assertEquals(SummarizerDiagnostics.Owner.LOCAL, owner(IllegalStateException("broken invariant"), request(200, validResponse)))
        assertEquals(SummarizerDiagnostics.Owner.LOCAL,
            SummarizerDiagnostics.owner(IllegalStateException("app parser bug"), GenErrorResult(GenErrorCode.S2, 200), request(200, validResponse)))
    }

    @Test fun malformedProviderEnvelopeIsExternalWithDecoderEvidence() {
        for (body in listOf("", "<html>unavailable</html>", "{}", """{"choices":[]}""")) {
            assertEquals(SummarizerDiagnostics.Owner.EXTERNAL,
                SummarizerDiagnostics.owner(Exception("decoder error"), GenErrorResult(GenErrorCode.S2, 200), request(200, body)))
        }
        // No wire evidence: an app parser exception is not enough to blame the provider.
        assertEquals(SummarizerDiagnostics.Owner.LOCAL,
            SummarizerDiagnostics.owner(Exception("decoder error"), GenErrorResult(GenErrorCode.S2, null), request()))
    }

    @Test fun cancellationIsNotDiagnosticFailure() {
        assertEquals(SummarizerDiagnostics.Owner.CANCELLED, owner(CancellationException("user stop"), request(429)))
    }

    @Test fun notDispatchedFailureIsLocalEvenWithProviderLookingProse() {
        assertEquals(SummarizerDiagnostics.Owner.LOCAL,
            owner(IllegalArgumentException("HTTP 500 while building request"), SummarizerDiagnostics.RequestEvidence()))
    }

    @Test fun structuredProviderEvidenceIsPreserved() {
        val evidence = request(502, """{"error":{"message":"Upstream quota exceeded","code":429,"type":"provider_error","error_type":"quota","metadata":{"provider_name":"Upstream","raw":{"error":{"message":"quota exceeded","code":429}}}}}""")
        val snapshot = evidence.snapshot()
        assertEquals(502, snapshot.outerHttpStatus)
        assertEquals("Upstream", snapshot.actualServingProvider)
        assertEquals("429", snapshot.providerCode)
        assertEquals("provider_error", snapshot.providerType)
        assertTrue(snapshot.errorMessages.any { it.contains("quota") })
        assertEquals(evidence.attemptId, snapshot.attemptId)
    }

    @Test fun localTraceRetainsExceptionAndCauseWithoutSecretsOrConversation() {
        val error = IllegalStateException("cannot save Authorization: Bearer sk-secret123456789", java.io.IOException("disk write failed"))
        val detail = SummarizerDiagnostics.localDetail("Compacting: commitManualCompaction", error)
        assertTrue(detail.contains("java.lang.IllegalStateException"))
        assertTrue(detail.contains("java.io.IOException: disk write failed"))
        assertTrue(detail.contains("SummarizerDiagnosticsTest"))
        assertFalse(detail.contains("sk-secret123456789"))
    }

    @Test fun sanitizerKeepsErrorEvidenceButRemovesPayloadAndArbitraryKnownKey() {
        val raw = """{"error":{"message":"quota exceeded","code":429},"messages":[{"content":"private conversation"}],"choices":[{"message":{"content":"private summary"}}],"api_key":"custom-secret-value"}"""
        val result = SummarizerDetailSanitizer.sanitize(raw, listOf("custom-secret-value"))!!
        assertTrue(result.contains("quota exceeded"))
        assertTrue(result.contains("429"))
        assertFalse(result.contains("private conversation"))
        assertFalse(result.contains("private summary"))
        assertFalse(result.contains("custom-secret-value"))
    }

    @Test fun routingWritesExactlyOneCorrectLogOrNeither() {
        for (owner in SummarizerDiagnostics.Owner.entries) {
            for (enabled in listOf(true, false)) {
                var providerWrites = 0
                var errorWrites = 0
                SummarizerDiagnostics.record(owner, enabled,
                    external = { providerWrites++ }, local = { errorWrites++ })
                assertEquals(if (owner == SummarizerDiagnostics.Owner.EXTERNAL && enabled) 1 else 0, providerWrites)
                assertEquals(if (owner == SummarizerDiagnostics.Owner.LOCAL) 1 else 0, errorWrites)
            }
        }
    }

    @Test fun storageTraceRedactsPlainSummaryTextAndRetainsFrames() {
        val summary = "this is the private summary being saved"
        val error = IllegalStateException("could not store: $summary", java.io.IOException("disk failure"))
        val detail = SummarizerDiagnostics.localDetail("Summarizing", error, listOf(summary))
        assertFalse(detail.contains(summary))
        assertTrue(detail.contains("disk failure"))
        assertTrue(detail.contains("SummarizerDiagnosticsTest"))
        assertEquals(listOf("[{\"text\":\"secret\"}]", "secret"),
            SummarizerDiagnostics.privateSectionValues("[{\"text\":\"secret\"}]"))
    }

    @Test fun localExceptionWithHttpLookingTextIsStillLocalAfterDispatch() {
        assertEquals(SummarizerDiagnostics.Owner.LOCAL,
            owner(IllegalStateException("internal invariant broken; prior HTTP 500"), request()))
    }

    @Test fun malformedSuccessBodyCannotLeakSummaryTextThroughDiagnostics() {
        val text = "private summary returned as plain text instead of a completion envelope"
        val evidence = request(200, text)
        val privateValues = evidence.privateResponseValues()
        assertFalse(SummarizerDetailSanitizer.sanitize(evidence.body, privateValues)!!.contains(text))
        assertFalse(SummarizerDetailSanitizer.sanitize("decoder error. JSON input: ${text.take(30)}")!!.contains(text.take(30)))
        val providerError = request(401, "API access rejected")
        assertTrue(providerError.privateResponseValues().isEmpty())
        assertEquals("API access rejected", SummarizerDetailSanitizer.sanitize(providerError.body))
    }

    @Test fun anEmptyAnswerKeepsTheEvidenceOfWhyItWasEmpty() {
        val body = """{"id":"gen-1","provider":"Nvidia","model":"vendor/model","object":"chat.completion","created":1,""" +
            """"choices":[{"index":0,"finish_reason":"length","native_finish_reason":"max_tokens",""" +
            """"message":{"role":"assistant","content":null,"refusal":null,"reasoning":"thinking about the private chat"}}],""" +
            """"usage":{"prompt_tokens":6374,"completion_tokens":4096}}"""
        val evidence = request(200, body)
        val logged = SummarizerDetailSanitizer.sanitize(evidence.body, evidence.privateResponseValues())!!
        assertTrue(logged.contains("\"finish_reason\":\"length\""))
        assertTrue(logged.contains("\"native_finish_reason\":\"max_tokens\""))
        assertTrue(logged.contains("\"content\":null"))
        assertTrue(logged.contains("\"completion_tokens\":4096"))
        assertTrue(logged.contains("\"provider\":\"Nvidia\""))
        assertTrue(logged.contains("\"reasoning\":\"[removed: 31 characters]\""))
        assertFalse(logged.contains("private chat"))
    }

    @Test fun aReturnedSummaryIsReplacedByItsLengthWhileTheRestStays() {
        val summary = "The user and the companion talked about the garden."
        val body = """{"id":"gen-2","created":1,"model":"m","choices":[{"index":0,"finish_reason":"stop",""" +
            """"message":{"role":"assistant","content":"$summary"}}]}"""
        val logged = SummarizerDetailSanitizer.sanitize(body)!!
        assertFalse(logged.contains("garden"))
        assertTrue(logged.contains("[removed: ${summary.length} characters]"))
        assertTrue(logged.contains("\"finish_reason\":\"stop\""))
    }

    @Test fun aStreamedAnswerKeepsItsLastEventsAndLosesOnlyTheWords() {
        val events = (1..10).joinToString("\n") {
            """data: {"id":"gen-3","choices":[{"index":0,"delta":{"content":"secret piece $it"},"finish_reason":null}]}"""
        } + "\n" + """data: {"id":"gen-3","choices":[{"index":0,"delta":{"content":""},"finish_reason":"length"}],"usage":{"completion_tokens":512}}""" +
            "\ndata: [DONE]"
        val evidence = request(200, events)
        assertTrue(evidence.privateResponseValues().isEmpty())
        val logged = SummarizerDetailSanitizer.sanitize(evidence.body, evidence.privateResponseValues())!!
        assertFalse(logged.contains("secret piece"))
        assertTrue(logged.contains("\"finish_reason\":\"length\""))
        assertTrue(logged.contains("\"completion_tokens\":512"))
        assertTrue(logged.contains("streamed events omitted"))
        assertTrue(logged.contains("data: [DONE]"))
    }

    @Test fun anUnrecognizedJsonShapeStillLosesTheReturnedWords() {
        for (body in listOf(
            """{"output":"private generated summary"}""",
            """{"choices":[{"message":{"content":["private generated summary"]}}]}""",
            """{"result":{"pieces":["private generated summary"]}}"""
        )) {
            val evidence = request(200, body)
            val logged = SummarizerDetailSanitizer.sanitize(evidence.body, evidence.privateResponseValues())!!
            assertFalse(logged, logged.contains("private generated summary"))
            assertTrue(logged, logged.contains("[removed: 25 characters]"))
        }
    }

    companion object {
        private const val validResponse = """{"id":"response-id","created":1,"model":"test","choices":[{"index":0,"message":{"role":"assistant","content":"valid reply"},"finish_reason":"stop"}]}"""
    }
}
