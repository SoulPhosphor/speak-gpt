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

package org.teslasoft.assistant.util.summarizer

/**
 * Lets the Chat Outline screen start an outline run. The open chat screen
 * owns the conversation snapshot a run reads, so it registers a starter for
 * its chat while it exists; the Chat Outline screen, opened on top of it,
 * calls [start].
 *
 * If Android recreated the app while the Chat Outline screen was open, the
 * chat screen underneath has not been rebuilt yet and no starter exists.
 * The outline screen then leaves the run with [request] and closes; the chat
 * screen picks it up with [takePending] once its messages are loaded, starts
 * it, and opens the outline screen again.
 */
object ChatOutlineRunner {

    fun interface Starter {
        /** Starts a run; false when one could not be started. */
        fun start(mode: SummarizerController.OutlineMode, redoIndex: Int): Boolean
    }

    data class Request(val mode: SummarizerController.OutlineMode, val redoIndex: Int)

    private val starters = HashMap<String, Starter>()
    private val pending = HashMap<String, Request>()

    @Synchronized
    fun register(chatId: String, starter: Starter) {
        starters.entries.removeAll { it.value === starter }
        if (chatId.isNotBlank()) starters[chatId] = starter
    }

    @Synchronized
    fun unregister(starter: Starter) {
        starters.entries.removeAll { it.value === starter }
    }

    @Synchronized
    fun start(chatId: String, mode: SummarizerController.OutlineMode, redoIndex: Int = -1): Boolean =
        starters[chatId]?.start(mode, redoIndex) ?: false

    @Synchronized
    fun hasStarter(chatId: String): Boolean = starters.containsKey(chatId)

    /** Leaves a run for the chat screen to start when it is rebuilt. */
    @Synchronized
    fun request(chatId: String, mode: SummarizerController.OutlineMode, redoIndex: Int = -1) {
        if (chatId.isNotBlank()) pending[chatId] = Request(mode, redoIndex)
    }

    @Synchronized
    fun takePending(chatId: String): Request? = pending.remove(chatId)
}
