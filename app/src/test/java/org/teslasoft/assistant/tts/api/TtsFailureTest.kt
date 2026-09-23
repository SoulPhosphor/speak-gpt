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

    @Test fun explicitDeletionCodeIsDistinctFromUnsupportedVoiceAndBareNotFound() {
        for ((payload, expected) in listOf(
            """{"error":{"code":"voice_deleted","message":"This voice was deleted"}}""" to TtsFailureKind.VOICE_DELETED,
            """{"error":{"message":"unsupported voice"}}""" to TtsFailureKind.VOICE_UNSUPPORTED,
            """{"error":{"message":"Not found"}}""" to TtsFailureKind.NOT_FOUND
        )) {
            val failure = assertThrows(TtsException::class.java) {
                response(payload, 404).requireSuccess(source(), TtsOperation.SPEECH)
            }.failure
            assertEquals(expected, failure.kind)
            assertNotNull(failure.evidence)
        }
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
            if (kind in setOf(TtsFailureKind.VOICE_DELETED, TtsFailureKind.PERMANENT_UNAVAILABLE)) {
                assertEquals(listOf("Okay", "Select New Voice"), message.actions)
                assertEquals("Selected Voice Is Permanently Unavailable", message.title)
                assertEquals("Please select a new voice.", message.explanation)
            } else assertTrue(message.actions == listOf("Okay") || message.actions == listOf("Cancel", "Retry"))
            assertEquals(target, failure.target)
            assertNull(failure.evidence)
        }
    }

    @Test fun offlineTimeoutMissingEmptyAndUnreadableAreNotCollapsed() {
        val kinds = listOf(TtsFailureKind.OFFLINE, TtsFailureKind.CONNECT_TIMEOUT, TtsFailureKind.RESPONSE_TIMEOUT,
            TtsFailureKind.DISCOVERY_UNAVAILABLE, TtsFailureKind.EMPTY, TtsFailureKind.MALFORMED,
            TtsFailureKind.IDENTIFIERS_MISSING)
        val messages = kinds.map { TtsFailures.message(TtsFailure(TtsOperation.VOICES, source().target, "Service", it)) }
        assertEquals(listOf("No Internet Connection", "Connection Timed Out", "Response Timed Out",
            "No Voices Available", "No Voices Available", "Voice List Could Not Be Read",
            "Voice List Could Not Be Read"), messages.map { it.title })
        assertEquals(listOf(null, null, null, "Provider details", "Provider details", "Provider details",
            "Provider details"), messages.map { it.detailsHeading })
    }

    @Test fun voiceRequestFailuresWithoutSpecificMessageShareOneTitle() {
        for (kind in listOf(TtsFailureKind.SERVER, TtsFailureKind.REJECTED, TtsFailureKind.NOT_FOUND, TtsFailureKind.UNKNOWN)) {
            val message = TtsFailures.message(TtsFailure(TtsOperation.VOICES, source().target, "Service", kind))
            assertEquals("Voice Request Failed", message.title)
            assertEquals("The client could not retrieve the available voices from the provider.", message.explanation)
            assertEquals("Provider error", message.detailsHeading)
        }
        // Outside the voice list these keep their existing wording.
        val speech = TtsFailures.message(TtsFailure(TtsOperation.SPEECH, source().target, "Service", TtsFailureKind.SERVER))
        assertEquals("Service Error", speech.title)
        assertNull(speech.detailsHeading)
    }

    @Test fun rateLimitUsesOneMessageForEveryTtsRequest() {
        for (op in TtsOperation.entries) {
            val message = TtsFailures.message(TtsFailure(op, source().target, "Service", TtsFailureKind.RATE_LIMIT))
            assertEquals("Request Rate Limited", message.title)
            assertTrue(message.explanation.startsWith("The provider is temporarily limiting requests.\n\n"))
            assertTrue(message.explanation.endsWith("Try the request again later or choose another model."))
            assertEquals("Provider error", message.detailsHeading)
            assertEquals(listOf("Cancel", "Retry"), message.actions)
        }
    }
}
