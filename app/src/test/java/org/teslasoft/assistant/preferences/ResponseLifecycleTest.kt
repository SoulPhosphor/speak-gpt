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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Response Lifecycle outcome rules and entry shape — the parts a user
 * reads and that drive the red "Outcome: Incomplete" line.
 */
class ResponseLifecycleTest {

    @Test fun streamedVisibleTextProducesNonzeroReceivedCharacters() {
        val recorder = ResponseLifecycleRecorder(
            turnId = "T-visible-1",
            phase = ResponseLifecycle.PHASE_PRIMARY,
            provider = "openrouter.ai",
            model = "LongCat",
            requestedMaxOutput = 8_000,
            startUptimeMs = 0
        )
        var visibleResponse = ""
        recorder.noteChunk("stop", "gen-visible", 10, 4, 14)
        visibleResponse += "Visible streamed text"
        recorder.noteVisibleResponse(visibleResponse)

        assertEquals(visibleResponse.length, recorder.receivedCharacters)
        assertTrue(recorder.receivedCharacters > 0)
    }

    @Test fun noFinishReasonIsIncompleteAndStreamClosed() {
        val r = ResponseLifecycle.classifyNormalCompletion(null, receivedCharacters = 100)
        // A stream that closed without a terminal finish reason is never treated
        // as complete just because text arrived.
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.STREAM_CLOSED, r.termination)
        assertEquals("missing", r.finishReasonDisplay)
        assertTrue(r.streamClosed)
    }

    @Test fun lengthIsIncompleteButProviderEndedTheStream() {
        val r = ResponseLifecycle.classifyNormalCompletion("length", receivedCharacters = 100)
        // Truncated by the token limit: the provider ended normally, but the
        // answer was cut off, so this is Incomplete (and shown in red).
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
        assertEquals("length", r.finishReasonDisplay)
        assertEquals(false, r.streamClosed)
    }

    @Test fun stopWithTextIsComplete() {
        val r = ResponseLifecycle.classifyNormalCompletion("stop", receivedCharacters = 100)
        assertEquals(ResponseLifecycle.Outcome.COMPLETE, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
        assertEquals("stop", r.finishReasonDisplay)
    }

    @Test fun toolCallsWithTextIsComplete() {
        val r = ResponseLifecycle.classifyNormalCompletion("tool_calls", receivedCharacters = 100)
        assertEquals(ResponseLifecycle.Outcome.COMPLETE, r.outcome)
    }

    @Test fun emptyStopIsEmptyNotComplete() {
        // The provider ended cleanly ("stop") but sent no visible text: the
        // owner wants this called out as Empty, not Complete.
        val r = ResponseLifecycle.classifyNormalCompletion("stop", receivedCharacters = 0)
        assertEquals(ResponseLifecycle.Outcome.EMPTY, r.outcome)
        assertEquals(ResponseLifecycle.Termination.PROVIDER_DONE, r.termination)
        assertEquals("stop", r.finishReasonDisplay)
    }

    @Test fun emptyClosedStreamIsEmpty() {
        // No text AND no finish reason: still Empty (the headline), with the
        // stream-closed detail preserved in the termination source.
        val r = ResponseLifecycle.classifyNormalCompletion(null, receivedCharacters = 0)
        assertEquals(ResponseLifecycle.Outcome.EMPTY, r.outcome)
        assertEquals(ResponseLifecycle.Termination.STREAM_CLOSED, r.termination)
        assertTrue(r.streamClosed)
    }

    @Test fun toolCallWithNoTextIsNotEmpty() {
        // A tool-call handoff legitimately has no visible text — never Empty.
        val r = ResponseLifecycle.classifyNormalCompletion("tool_calls", receivedCharacters = 0)
        assertEquals(ResponseLifecycle.Outcome.COMPLETE, r.outcome)
    }

    @Test fun emptyOutcomeLineIsRed() {
        val body = ResponseLifecycle.format(
            turnId = "T3-1", phase = ResponseLifecycle.PHASE_PRIMARY,
            provider = "openrouter.ai", model = "LongCat",
            outcome = ResponseLifecycle.Outcome.EMPTY, finishReasonDisplay = "stop",
            streamClosed = false, termination = ResponseLifecycle.Termination.PROVIDER_DONE,
            requestedMaxOutput = 8000, promptTokens = 30, completionTokens = 0,
            totalTokens = 30, receivedCharacters = 0, durationMs = 600,
            generationId = "gen-empty", errorText = null
        )
        // The viewer reds any whole line matching ^Outcome: (Incomplete|Empty)$.
        assertTrue(body.contains("Outcome: Empty"))
        assertTrue(Regex("(?m)^Outcome: (?:Incomplete|Empty)$").containsMatchIn(body))
    }

    @Test fun incompleteLineMatchesTheRedRenderExactly() {
        val body = ResponseLifecycle.format(
            turnId = "T1-1", phase = ResponseLifecycle.PHASE_PRIMARY,
            provider = "openrouter.ai", model = "LongCat",
            outcome = ResponseLifecycle.Outcome.INCOMPLETE, finishReasonDisplay = "missing",
            streamClosed = true, termination = ResponseLifecycle.Termination.STREAM_CLOSED,
            requestedMaxOutput = 8000, promptTokens = null, completionTokens = 742,
            totalTokens = null, receivedCharacters = 1200, durationMs = 1843,
            generationId = "gen-abc", errorText = null
        )
        // The viewer reds any whole line matching ^Outcome: Incomplete$ — the
        // body must produce exactly that line so the red pass fires.
        assertTrue(Regex("(?m)^Outcome: Incomplete$").containsMatchIn(body))
        // Fields the client library cannot surface are honest placeholders, not
        // guesses or zeros.
        assertTrue(body.contains("Received Done: unavailable"))
        assertTrue(body.contains("Reasoning Tokens: not reported"))
        assertTrue(body.contains("Provider Cost: not reported"))
        // A null token count reads "not reported", never 0.
        assertTrue(body.contains("Prompt Tokens: not reported"))
        assertTrue(body.contains("Completion Tokens: 742"))
        // No error means the honest "none reported", and the body carries no
        // leading timestamp (Logger prepends the header).
        assertTrue(body.contains("Error: none reported"))
        assertTrue(body.startsWith("Turn ID: T1-1"))
    }

    @Test fun completeEntryReadsComplete() {
        val body = ResponseLifecycle.format(
            turnId = "T2-1", phase = ResponseLifecycle.PHASE_TOOL_CONTINUATION,
            provider = "openrouter.ai", model = "LongCat",
            outcome = ResponseLifecycle.Outcome.COMPLETE, finishReasonDisplay = "stop",
            streamClosed = false, termination = ResponseLifecycle.Termination.PROVIDER_DONE,
            requestedMaxOutput = 8000, promptTokens = 30, completionTokens = 40,
            totalTokens = 70, receivedCharacters = 210, durationMs = 900,
            generationId = "gen-xyz", errorText = null
        )
        assertTrue(body.contains("Outcome: Complete"))
        assertTrue(body.contains("Finish Reason: stop"))
        assertTrue(body.contains("Phase: tool_continuation"))
        // A Complete entry must never trip the red-incomplete matcher.
        assertTrue(!Regex("(?m)^Outcome: Incomplete$").containsMatchIn(body))
    }
}
