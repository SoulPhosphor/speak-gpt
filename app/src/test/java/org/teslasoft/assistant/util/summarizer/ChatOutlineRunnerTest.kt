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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.util.summarizer.SummarizerController.OutlineMode

class ChatOutlineRunnerTest {

    @Test
    fun retryRepeatsTheRunLastAskedForInThatChat() {
        assertNull(ChatOutlineRunner.lastRequest("retry-chat-none"))

        // Recorded even when no chat screen could start it, so Retry can ask again.
        assertFalse(ChatOutlineRunner.start("retry-chat-a", OutlineMode.REDO, 2, "notes"))
        assertEquals(
            ChatOutlineRunner.Request(OutlineMode.REDO, 2, "notes"),
            ChatOutlineRunner.lastRequest("retry-chat-a")
        )

        ChatOutlineRunner.request("retry-chat-b", OutlineMode.GENERATE_NEW, -1, null)
        assertEquals(
            ChatOutlineRunner.Request(OutlineMode.GENERATE_NEW, -1, null),
            ChatOutlineRunner.lastRequest("retry-chat-b")
        )
        ChatOutlineRunner.takePending("retry-chat-b")
        // Each chat keeps its own.
        assertEquals(OutlineMode.REDO, ChatOutlineRunner.lastRequest("retry-chat-a")?.mode)
    }
}
