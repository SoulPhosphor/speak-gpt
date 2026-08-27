package org.teslasoft.assistant.tts.api

import org.junit.Assert.*
import org.junit.Test

class TtsFailureTest {
    @Test fun serverEvidenceBelongsToTheFailedSpeechAttemptAndRedactsKey() {
        val s = source()
        val error = assertThrows(TtsException::class.java) {
            response("""{"error":{"code":429,"message":"Rate limit exceeded","metadata":{"provider_name":"Actual Provider","raw":"private-test-key is limited"}}}""", 429)
                .requireSuccess(s, TtsOperation.PREVIEW)
        }.failure
        assertEquals(s.target, error.target)
        assertEquals("Renamed Service", error.endpointName)
        assertEquals("Actual Provider", error.evidence!!.actualServingProvider)
        assertEquals(TtsOperation.PREVIEW, error.operation)
        assertFalse(error.evidence.toString().contains("private-test-key"))
        assertEquals(TtsFailureKind.RATE_LIMIT, error.kind)
    }

    @Test fun bare404IsNotModelOrVoiceDeletion() {
        val error = assertThrows(TtsException::class.java) {
            response("", 404).requireSuccess(source(), TtsOperation.SPEECH)
        }.failure
        assertEquals(TtsFailureKind.NOT_FOUND, error.kind)
        assertEquals("Speech Request Not Found", TtsFailures.message(error).title)
        assertNull(error.evidence!!.actualServingProvider)
    }

    @Test fun rateUsageAndCreditsHaveDistinctExplanations() {
        val examples = listOf(Triple(429, "Rate limit exceeded", TtsFailureKind.RATE_LIMIT),
            Triple(429, "Quota exceeded", TtsFailureKind.USAGE_LIMIT),
            Triple(402, "Insufficient credits", TtsFailureKind.NO_CREDITS),
            Triple(401, "Incorrect API key", TtsFailureKind.AUTH))
        for ((status, text, kind) in examples) {
            val error = assertThrows(TtsException::class.java) {
                response("""{"error":{"message":"$text"}}""", status).requireSuccess(source(), TtsOperation.VOICES)
            }.failure
            assertEquals(kind, error.kind)
        }
    }

    @Test fun everyMappedReasonHasTitleAndApprovedActionsWithoutMutatingSelection() {
        val target = source().target
        for (kind in TtsFailureKind.entries) {
            val failure = TtsFailure(TtsOperation.VOICES, target, "Speech Endpoint", kind)
            val message = TtsFailures.message(failure)
            assertTrue(message.title.first().isUpperCase())
            assertTrue(message.explanation.isNotBlank())
            assertTrue(message.actions == listOf("Okay") || message.actions == listOf("Cancel", "Retry"))
            assertEquals(target, failure.target)
            assertNull(failure.evidence)
        }
    }

    @Test fun offlineTimeoutMissingEmptyAndUnreadableAreNotCollapsed() {
        val kinds = listOf(TtsFailureKind.OFFLINE, TtsFailureKind.CONNECT_TIMEOUT, TtsFailureKind.RESPONSE_TIMEOUT,
            TtsFailureKind.DISCOVERY_UNAVAILABLE, TtsFailureKind.EMPTY, TtsFailureKind.MALFORMED,
            TtsFailureKind.IDENTIFIERS_MISSING)
        val messages = kinds.map { TtsFailures.message(TtsFailure(TtsOperation.VOICES, source().target, "Service", it)) }
        assertEquals(kinds.size, messages.map { it.title }.distinct().size)
        assertEquals("Voice List Unavailable", messages[3].title)
        assertEquals("No Voices Returned", messages[4].title)
    }
}
