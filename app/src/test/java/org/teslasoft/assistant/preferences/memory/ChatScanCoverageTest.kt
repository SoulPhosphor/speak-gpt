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

package org.teslasoft.assistant.preferences.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.ChatScanCoverage.Message
import org.teslasoft.assistant.preferences.memory.ChatScanCoverage.Status

class ChatScanCoverageTest {

    private fun turn(role: String, content: String, id: String? = null): JSONObject =
        JSONObject().put("role", role).put("content", content).also {
            if (id != null) it.put(TranscriptTurnVisibility.MESSAGE_ID, id)
        }

    private fun row(vararg turns: JSONObject): String =
        JSONArray().also { a -> turns.forEach { a.put(it) } }.toString()

    private val chat = listOf(
        Message("u1", "user", "Hi"),
        Message("a1", "assistant", "Hello"),
        Message("u2", "user", "How are you?"),
        Message("a2", "assistant", "Well."),
        Message("u3", "user", "Bye")
    )

    @Test fun nothingScannedIsUnscanned() {
        assertEquals(Status.Unscanned, ChatScanCoverage.describe(chat, emptyList(), hasUnscanned = true))
    }

    @Test fun nothingLeftToScanIsComplete() {
        val scanned = listOf(row(turn("user", "Hi", "u1")))
        assertEquals(Status.Complete, ChatScanCoverage.describe(chat, scanned, hasUnscanned = false))
    }

    @Test fun partialScanIsTheFirstToLastScannedMessageNumber() {
        val scanned = listOf(
            row(turn("user", "Hi", "u1"), turn("assistant", "Hello", "a1")),
            row(turn("user", "How are you?", "u2"), turn("assistant", "Well.", "a2"))
        )
        assertEquals(Status.Range(1, 4), ChatScanCoverage.describe(chat, scanned, hasUnscanned = true))
    }

    @Test fun turnsWithoutIdsMatchByRoleAndTextInOrder() {
        val repeated = listOf(
            Message("", "user", "Again"),
            Message("", "assistant", "Sure"),
            Message("", "user", "Again"),
            Message("", "assistant", "Sure")
        )
        val scanned = listOf(row(turn("user", "Again"), turn("assistant", "Sure"), turn("user", "Again")))
        assertEquals(Status.Range(1, 3), ChatScanCoverage.describe(repeated, scanned, hasUnscanned = true))
    }

    @Test fun scannedMessagesNoLongerInTheChatGiveNoLine() {
        val scanned = listOf(row(turn("user", "Deleted", "gone")))
        assertNull(ChatScanCoverage.describe(chat, scanned, hasUnscanned = true))
    }
}
