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

package org.teslasoft.assistant.conversation.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

/** A reply's provider request runs in the reply's scope; its reader may change. */
class RequestRelayTest {
    private val relay = RequestRelay(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private class ProviderFailure(message: String) : Exception(message)

    @Test fun theReaderReceivesEveryItemInOrder() = runBlocking {
        val items = withTimeout(5_000) {
            relay.relay(flow { repeat(5) { emit(it) } }, EmptyCoroutineContext) { false }.toList()
        }
        assertEquals(listOf(0, 1, 2, 3, 4), items)
    }

    @Test fun aProviderFailureReachesTheReaderWithItsOwnType() = runBlocking {
        try {
            withTimeout(5_000) {
                relay.relay(flow { emit(1); throw ProviderFailure("rate limited") }, EmptyCoroutineContext) { false }
                    .toList()
            }
            fail("the failure must reach the reader")
        } catch (e: ProviderFailure) {
            assertEquals("rate limited", e.message)
        }
    }

    @Test fun aReaderThatStopsStopsTheRequestWhenNotHandedOff() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val source = flow {
            try { emit(1); awaitCancellation() } finally { stopped.complete(Unit) }
        }
        withTimeout(5_000) {
            relay.relay(source, EmptyCoroutineContext) { false }.take(1).toList()
            stopped.await()
        }
        assertTrue(stopped.isCompleted)
    }

    @Test fun aHandedOffRequestKeepsRunningAfterItsReaderStopsAndNothingIsLost() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val sent = AtomicInteger(0)
        val source = flow {
            emit(1); sent.incrementAndGet()
            release.await()
            emit(2); emit(3); sent.addAndGet(2)
        }
        var handedOff = false
        val flow = relay.relay(source, EmptyCoroutineContext) { handedOff }
        withTimeout(5_000) {
            // The first reader (a screen) stops after one item, once handed off.
            val first = mutableListOf<Int>()
            val reader = flow.take(1)
            handedOff = true
            reader.toList(first)
            assertEquals(listOf(1), first)
            release.complete(Unit)
        }
        // The request was not stopped: its other items were still produced.
        withTimeout(5_000) { while (sent.get() < 3) kotlinx.coroutines.delay(10) }
        assertEquals(3, sent.get())
    }

    @Test fun aCompletedCallReturnsItsResult() = runBlocking {
        val result = withTimeout(5_000) { relay.relayCall({ "reply" }, EmptyCoroutineContext) { false } }
        assertEquals("reply", result)
    }

    @Test fun waitingForTheRequestInFlightGivesItsFailureOrNothing() = runBlocking {
        val relayA = RequestRelay(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertEquals(null, relayA.awaitCurrent())
        withTimeout(5_000) { relayA.relay(flow { emit(1) }, EmptyCoroutineContext) { false }.toList() }
        assertEquals(null, withTimeout(5_000) { relayA.awaitCurrent() })

        val relayB = RequestRelay(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        try {
            withTimeout(5_000) {
                relayB.relay(flow<Int> { throw ProviderFailure("overloaded") }, EmptyCoroutineContext) { false }.toList()
            }
        } catch (_: ProviderFailure) { }
        val failure = withTimeout(5_000) { relayB.awaitCurrent() }
        assertTrue(failure is ProviderFailure)
    }

    @Test fun stopEndsTheRequestInFlightAsStopped() = runBlocking {
        val relayC = RequestRelay(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val started = CompletableDeferred<Unit>()
        // Handed off: nobody reads, the request keeps running until stopped.
        relayC.relay(flow<Int> { started.complete(Unit); awaitCancellation() }, EmptyCoroutineContext) { true }
        withTimeout(5_000) { started.await() }
        relayC.cancelCurrent()
        val failure = withTimeout(5_000) { relayC.awaitCurrent() }
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }
}
