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

package org.teslasoft.assistant.reasoning

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningContinuationSerializerTest {

    private fun details(json: String): JsonArray = JsonParser.parseString(json).asJsonArray

    private val encrypted = details(
        """[{"type":"reasoning.encrypted","data":"OPAQUE==","signature":"sig"}]"""
    )

    @Test
    fun attachesReasoningDetailsToTheToolCallAssistantMessage() {
        val body = """
            {"model":"x/y","messages":[
              {"role":"user","content":"hi"},
              {"role":"assistant","content":"working","tool_calls":[{"id":"c1","type":"function","function":{"name":"create_image","arguments":"{}"}}]},
              {"role":"tool","tool_call_id":"c1","content":"ok"}
            ]}
        """.trimIndent()
        val out = ReasoningContinuationSerializer.attachToToolCallMessage(body, encrypted)
        val messages = JsonParser.parseString(out).asJsonObject.getAsJsonArray("messages")
        val assistant = messages[1].asJsonObject
        val attached = assistant.getAsJsonArray("reasoning_details")
        assertEquals("OPAQUE==", attached[0].asJsonObject.get("data").asString)
        assertEquals("sig", attached[0].asJsonObject.get("signature").asString)
        // The tool call and user message are untouched.
        assertEquals("c1", assistant.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
    }

    @Test
    fun noToolCallAssistantMessageLeavesBodyUnchanged() {
        val body = """{"model":"x/y","messages":[{"role":"assistant","content":"just text"}]}"""
        assertEquals(body, ReasoningContinuationSerializer.attachToToolCallMessage(body, encrypted))
    }

    @Test
    fun nullOrEmptyDetailsLeaveBodyUnchanged() {
        val body = """{"messages":[{"role":"assistant","tool_calls":[{"id":"c1"}]}]}"""
        assertEquals(body, ReasoningContinuationSerializer.attachToToolCallMessage(body, null))
        assertEquals(body, ReasoningContinuationSerializer.attachToToolCallMessage(body, JsonArray()))
    }

    @Test
    fun malformedBodyLeftUnchanged() {
        assertEquals("not json", ReasoningContinuationSerializer.attachToToolCallMessage("not json", encrypted))
    }

    @Test
    fun lastToolCallAssistantMessageWins() {
        val body = """
            {"messages":[
              {"role":"assistant","tool_calls":[{"id":"old"}]},
              {"role":"tool","tool_call_id":"old","content":"r"},
              {"role":"assistant","tool_calls":[{"id":"new"}]}
            ]}
        """.trimIndent()
        val out = ReasoningContinuationSerializer.attachToToolCallMessage(body, encrypted)
        val messages = JsonParser.parseString(out).asJsonObject.getAsJsonArray("messages")
        assertFalse(messages[0].asJsonObject.has("reasoning_details"))
        assertTrue(messages[2].asJsonObject.has("reasoning_details"))
    }

    @Test
    fun replayOverwritesRatherThanDuplicates() {
        val body = """{"messages":[{"role":"assistant","tool_calls":[{"id":"c1"}],"reasoning_details":[{"type":"stale"}]}]}"""
        val out = ReasoningContinuationSerializer.attachToToolCallMessage(body, encrypted)
        val assistant = JsonParser.parseString(out).asJsonObject.getAsJsonArray("messages")[0].asJsonObject
        val attached = assistant.getAsJsonArray("reasoning_details")
        assertEquals(1, attached.size())
        assertEquals("reasoning.encrypted", attached[0].asJsonObject.get("type").asString)
    }
}
