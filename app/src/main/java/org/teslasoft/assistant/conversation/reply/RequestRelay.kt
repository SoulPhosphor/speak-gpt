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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a provider request in [scope] (the reply's own scope) and hands its
 * items, and its failure, to whoever reads the returned flow. While
 * [keepRunning] is false, a reader that stops early also stops the request,
 * so Stop still ends it. Once [keepRunning] is true, the request continues
 * and keeps recording output in ReplyRun. The closed screen's delivery
 * buffer is discarded; reopened screens observe the run's accumulated state.
 */
internal class RequestRelay(private val scope: CoroutineScope) {

    /** The request most recently started, and how it ended (null: completed or still running). */
    private class Current(val job: kotlinx.coroutines.Job) {
        @Volatile var failure: Throwable? = null
    }

    @Volatile private var current: Current? = null

    /** Waits for the request most recently started to end; returns its failure, or null. */
    suspend fun awaitCurrent(): Throwable? {
        val call = current ?: return null
        call.job.join()
        // Stopped before it could record anything: still a stopped request.
        return call.failure ?: if (call.job.isCancelled) kotlinx.coroutines.CancellationException("Request stopped") else null
    }

    /** Stops the request most recently started, if it is still running. */
    fun cancelCurrent() {
        current?.job?.cancel()
    }

    /** The terminal owner no longer needs the completed coroutine's inputs. */
    fun release() {
        val call = current ?: return
        if (call.job.isCompleted) {
            if (current === call) current = null
        } else scope.launch {
            call.job.join()
            if (current === call) current = null
        }
    }

    fun <T> relay(
        source: Flow<T>,
        requestContext: CoroutineContext,
        keepRunning: () -> Boolean
    ): Flow<T> {
        // A slow screen cannot accumulate an unlimited second copy of output.
        val channel = Channel<T>(64)
        val readerGone = AtomicBoolean(false)
        val started = kotlinx.coroutines.CompletableDeferred<Current>()
        val producer = scope.launch(requestContext, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val call = started.await()
            try {
                source.collect { item ->
                    currentCoroutineContext().ensureActive()
                    if (!readerGone.get()) {
                        try { channel.send(item) } catch (cancelled: CancellationException) {
                            // Closing the display wakes a producer waiting on its
                            // bounded buffer, without cancelling the paid request.
                            currentCoroutineContext().ensureActive()
                            if (!readerGone.get()) throw cancelled
                        }
                    }
                }
                channel.close()
            } catch (e: Throwable) {
                call.failure = e
                channel.close(e)
            }
        }
        val call = Current(producer)
        current = call
        started.complete(call)
        producer.start()
        return flow {
            var finished = false
            try {
                for (item in channel) emit(item)
                finished = true
            } finally {
                if (!finished && !keepRunning()) producer.cancel()
                readerGone.set(true)
                channel.cancel()
            }
        }
    }

    /** A completed (non-streaming) call, run like [relay]. */
    suspend fun <T> relayCall(
        call: suspend () -> T,
        requestContext: CoroutineContext,
        keepRunning: () -> Boolean
    ): T = relay(flow { emit(call()) }, requestContext, keepRunning).single()
}
