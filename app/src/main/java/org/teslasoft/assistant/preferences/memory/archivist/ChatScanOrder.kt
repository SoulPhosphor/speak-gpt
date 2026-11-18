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

package org.teslasoft.assistant.preferences.memory.archivist

/**
 * Where a Scan for Memories request goes when another run is live (owner
 * design, Nov 18 2026): the live run finishes the conversation it is on, takes
 * the requested chat next, then returns to its own remaining work.
 */
object ChatScanOrder {

    /**
     * Moves each requested chat that is still waiting in [queue] (at or after
     * [position], the next conversation to start) to the front of the waiting
     * part, in request order. Returns the requests whose chat is not waiting in
     * this run; those are scanned as their own runs before [position]. A chat
     * requested twice is handled once.
     */
    fun <T> promote(
        queue: MutableList<T>,
        position: Int,
        requests: List<Archivist.ChatScanRequest>,
        chatIdOf: (T) -> String
    ): List<Archivist.ChatScanRequest> {
        val separate = ArrayList<Archivist.ChatScanRequest>()
        val handled = HashSet<String>()
        var insertAt = position
        for (request in requests) {
            if (!handled.add(request.chatId)) continue
            var found = -1
            for (i in insertAt until queue.size) {
                if (chatIdOf(queue[i]) == request.chatId) {
                    found = i
                    break
                }
            }
            if (found >= 0) {
                queue.add(insertAt, queue.removeAt(found))
                insertAt++
            } else {
                separate.add(request)
            }
        }
        return separate
    }
}
