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

package org.teslasoft.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.ResponseLifecycle
import java.io.File

/**
 * Regression guards for the pre-dispatch lifecycle fix: an assistant reply
 * attempt that creates a visible row but fails or is cancelled BEFORE the
 * provider request is dispatched must (a) still produce a lifecycle record,
 * (b) be classified as request_not_sent — never provider/network/parser/
 * timeout/stream_closed — and (c) never reach the Provider Failure Log.
 *
 * The streaming pipeline lives in one enormous Android activity that cannot be
 * instantiated on the JVM, so the structural invariants are asserted by a
 * source scan in the same style as FunctionCallingRemovalTest. The pure
 * termination vocabulary is asserted directly.
 */
class PreDispatchLifecycleTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
        return file.readText()
    }

    private val chatActivity =
        "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"

    /** The body of one method, from its signature up to the next top-level
     *  member, so ordering asserts stay scoped to that method. */
    private fun methodRegion(src: String, signature: String): String {
        val start = src.indexOf(signature)
        if (start < 0) throw AssertionError("method not found: $signature")
        val after = src.indexOf("\n    private ", start + signature.length)
        return if (after >= 0) src.substring(start, after) else src.substring(start)
    }

    // ---- The new termination vocabulary --------------------------------

    @Test
    fun requestNotSentTerminationExistsWithExpectedWire() {
        assertEquals("request_not_sent", ResponseLifecycle.Termination.REQUEST_NOT_SENT.wire)
    }

    // ---- Lifecycle starts at row creation, not after construction ------

    @Test
    fun regularResponseStartsLifecycleBeforeBuildingTheRequest() {
        val region = methodRegion(source(chatActivity), "private suspend fun regularGPTResponse(")

        val rowIdx = region.indexOf("markLastAssistantStreaming()")
        val startIdx = region.indexOf("startLifecycle(")
        val dispatchIdx = region.indexOf("ai!!.chatCompletions(")

        assertTrue("row creation not found", rowIdx >= 0)
        assertTrue("startLifecycle not found", startIdx >= 0)
        assertTrue("provider dispatch not found", dispatchIdx >= 0)

        // Recording begins right after the visible row is created…
        assertTrue(
            "startLifecycle must run after the assistant row is created",
            startIdx > rowIdx
        )
        // …and BEFORE the provider request is built/dispatched, so a failure
        // during construction is still covered by a recorder.
        assertTrue(
            "startLifecycle must run before the provider request is dispatched",
            startIdx < dispatchIdx
        )
    }

    @Test
    fun regularResponseStartsExactlyOneLifecycleRecord() {
        val region = methodRegion(source(chatActivity), "private suspend fun regularGPTResponse(")
        val count = region.split("startLifecycle(").size - 1
        assertEquals(
            "regularGPTResponse must open exactly one lifecycle record (moved up, not duplicated)",
            1, count
        )
    }

    // ---- The dispatch boundary is tracked explicitly -------------------

    @Test
    fun dispatchBoundaryIsResetPerAttempt() {
        val region = methodRegion(source(chatActivity), "private suspend fun startLifecycle(")
        assertTrue(
            "startLifecycle must reset the dispatch boundary for each attempt",
            region.contains("providerRequestDispatched = false")
        )
    }

    @Test
    fun dispatchBoundaryIsSetTrueImmediatelyBeforeCollection() {
        val region = methodRegion(source(chatActivity), "private suspend fun regularGPTResponse(")
        val setIdx = region.indexOf("providerRequestDispatched = true")
        val collectIdx = region.indexOf(".collect {")
        assertTrue("dispatch flag is never set in regularGPTResponse", setIdx >= 0)
        assertTrue("provider collection not found", collectIdx >= 0)
        assertTrue(
            "the dispatch boundary must flip true before the provider stream is collected",
            setIdx < collectIdx
        )
    }

    // ---- Pre-dispatch classification -----------------------------------

    @Test
    fun preDispatchExceptionIsClassifiedRequestNotSent() {
        val chat = source(chatActivity)
        assertTrue(
            "a pre-dispatch exception must be recorded as request_not_sent",
            chat.contains("if (!providerRequestDispatched) {") &&
                chat.contains("ResponseLifecycle.Termination.REQUEST_NOT_SENT")
        )
    }

    @Test
    fun preDispatchCancellationIsClassifiedRequestNotSent() {
        val chat = source(chatActivity)
        // request_not_sent is used by BOTH catch paths (exception + the
        // non-user, non-teardown cancellation branch).
        val uses = chat.split("Termination.REQUEST_NOT_SENT").size - 1
        assertTrue(
            "request_not_sent must classify both the pre-dispatch exception and cancellation paths",
            uses >= 2
        )
    }

    @Test
    fun preDispatchFailureSkipsTheProviderFailureLog() {
        val chat = source(chatActivity)
        assertTrue(
            "the Provider Failure Log write must be gated on the request having been dispatched",
            chat.contains("genError.reachedServer() && providerRequestDispatched")
        )
    }
}
