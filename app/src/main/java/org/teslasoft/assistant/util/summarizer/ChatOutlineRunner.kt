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
 */
object ChatOutlineRunner {

    fun interface Starter {
        /** Starts a run; false when one could not be started. */
        fun start(mode: SummarizerController.OutlineMode, redoIndex: Int): Boolean
    }

    private val starters = HashMap<String, Starter>()

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
}
