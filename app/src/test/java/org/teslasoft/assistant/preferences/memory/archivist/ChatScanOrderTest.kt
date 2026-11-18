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

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScanOrderTest {

    private fun req(chatId: String, type: String = "associative") = Archivist.ChatScanRequest(chatId, type)

    @Test fun waitingChatMovesToTheNextPositionAndTheRestKeepTheirOrder() {
        val queue = mutableListOf("a", "b", "c", "d")
        val separate = ChatScanOrder.promote(queue, 1, listOf(req("d"))) { it }
        assertEquals(listOf("a", "d", "b", "c"), queue)
        assertEquals(emptyList<Archivist.ChatScanRequest>(), separate)
    }

    @Test fun theConversationInProgressAndFinishedOnesAreNeverMoved() {
        // Position 2 is the next to start: a and b are done or in progress.
        val queue = mutableListOf("a", "b", "c")
        val separate = ChatScanOrder.promote(queue, 2, listOf(req("a"))) { it }
        assertEquals(listOf("a", "b", "c"), queue)
        assertEquals(listOf(req("a")), separate)
    }

    @Test fun chatNotInTheRunIsScannedSeparately() {
        val queue = mutableListOf("a", "b")
        val separate = ChatScanOrder.promote(queue, 0, listOf(req("z", "lorebook"))) { it }
        assertEquals(listOf("a", "b"), queue)
        assertEquals(listOf(req("z", "lorebook")), separate)
    }

    @Test fun severalRequestsKeepRequestOrderAndDuplicatesAreHandledOnce() {
        val queue = mutableListOf("a", "b", "c", "d")
        val separate = ChatScanOrder.promote(
            queue, 0, listOf(req("c"), req("z"), req("b"), req("c"), req("z"))
        ) { it }
        assertEquals(listOf("c", "b", "a", "d"), queue)
        assertEquals(listOf(req("z")), separate)
    }

    @Test fun emptyRequestsChangeNothing() {
        val queue = mutableListOf("a", "b")
        val separate = ChatScanOrder.promote(queue, 0, emptyList()) { it }
        assertEquals(listOf("a", "b"), queue)
        assertEquals(emptyList<Archivist.ChatScanRequest>(), separate)
    }
}
