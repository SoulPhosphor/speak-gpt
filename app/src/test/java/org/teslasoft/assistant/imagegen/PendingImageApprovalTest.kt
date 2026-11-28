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

package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An image request waiting for approval is saved exactly as the model sent it. */
class PendingImageApprovalTest {

    private val calls = listOf(
        StreamedToolCallAssembler.AssembledToolCall("call_1", "create_image", "{\"prompt\":\"a lighthouse\"}"),
        StreamedToolCallAssembler.AssembledToolCall(null, "unknown_tool", "{}")
    )

    @Test fun theSavedApprovalKeepsEveryToolCallTheTextTheModelAndTheEndpoint() {
        val approval = PendingImageApproval.of("a1", calls, "Here is my idea.", "model-x", "endpoint-7")
        val decoded = PendingImageApproval.decode(approval.encode())!!
        assertEquals(approval, decoded)
        val back = decoded.toAssembledCalls()
        assertEquals(listOf("call_1", null), back.map { it.id })
        assertEquals(listOf("create_image", "unknown_tool"), back.map { it.name })
        assertEquals("{\"prompt\":\"a lighthouse\"}", back[0].arguments)
    }

    @Test fun nothingSavedOrAnUnreadableRecordIsNoApproval() {
        assertNull(PendingImageApproval.decode(null))
        assertNull(PendingImageApproval.decode(""))
        assertNull(PendingImageApproval.decode("{not json"))
        assertNull(PendingImageApproval.decode("{\"version\":99,\"id\":\"a\",\"calls\":[]}"))
    }

    @Test fun aRecordWithoutToolCallsIsNoApproval() {
        val empty = PendingImageApproval("a1", emptyList(), "", "m", "e")
        assertNull(PendingImageApproval.decode(empty.encode()))
    }
}
