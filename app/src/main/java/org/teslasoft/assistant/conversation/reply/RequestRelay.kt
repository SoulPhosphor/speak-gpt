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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Runs a provider request in [scope] (the reply's own scope) and hands its
 * items, and its failure, to whoever reads the returned flow. While
 * [keepRunning] is false, a reader that stops early also stops the request,
 * so Stop still ends it. Once [keepRunning] is true, the request continues
 * and the rest of its output stays buffered for the next reader.
 */
internal class RequestRelay(private val scope: CoroutineScope) {

    fun <T> relay(
        source: Flow<T>,
        requestContext: CoroutineContext,
        keepRunning: () -> Boolean
    ): Flow<T> {
        val channel = Channel<T>(Channel.UNLIMITED)
        val producer = scope.launch(requestContext) {
            try {
                source.collect { channel.send(it) }
                channel.close()
            } catch (e: Throwable) {
                channel.close(e)
            }
        }
        return flow {
            var finished = false
            try {
                for (item in channel) emit(item)
                finished = true
            } finally {
                if (!finished && !keepRunning()) producer.cancel()
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
