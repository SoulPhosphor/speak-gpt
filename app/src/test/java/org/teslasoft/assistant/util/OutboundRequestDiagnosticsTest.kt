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

package org.teslasoft.assistant.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundRequestDiagnosticsTest {

    @Test
    fun emptyLogitBiasIsRemovedAndOnlyTopLevelNamesAreCaptured() {
        val body = """{
            "model":"stealth/ox-alpha",
            "messages":[{"role":"user","content":"SECRET_PROMPT_TEXT"}],
            "max_tokens":8000,
            "logit_bias":{},
            "stream":true,
            "stream_options":{"include_usage":true}
        }""".trimIndent()

        val out = OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(body)
        val root = JsonParser.parseString(out).asJsonObject

        assertFalse(root.has("logit_bias"))
        assertEquals(
            listOf("max_tokens", "messages", "model", "stream", "stream_options"),
            OutboundRequestDiagnostics.latestFieldNames()
        )
        assertFalse(OutboundRequestDiagnostics.latestFieldNamesText()!!.contains("SECRET_PROMPT_TEXT"))
    }

    @Test
    fun nonEmptyLogitBiasIsPreserved() {
        val body = """{
            "model":"example/model",
            "messages":[],
            "logit_bias":{"123":25},
            "stream":true
        }""".trimIndent()

        val out = OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(body)
        val root = JsonParser.parseString(out).asJsonObject

        assertTrue(root.has("logit_bias"))
        assertEquals(25, root.getAsJsonObject("logit_bias").get("123").asInt)
        assertTrue(OutboundRequestDiagnostics.latestFieldNames()!!.contains("logit_bias"))
    }

    @Test
    fun nonStreamedBodyIsRecordedWithOnlyFieldsActuallySerialized() {
        OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(
            """{"model":"example/model","messages":[],"stream":true}"""
        )

        val body = """{
            "model":"example/model",
            "messages":[],
            "stream":false
        }"""
        assertEquals(body, OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(body))
        assertEquals(
            listOf("messages", "model", "stream"),
            OutboundRequestDiagnostics.latestFieldNames()
        )
        assertFalse(OutboundRequestDiagnostics.latestFieldNames()!!.contains("stream_options"))
    }

    @Test
    fun nonStreamedBodyReportsStreamOptionsOnlyWhenSerialized() {
        val body = """{
            "model":"example/model",
            "messages":[],
            "stream":false,
            "stream_options":{"include_usage":true}
        }"""

        OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(body)

        assertTrue(OutboundRequestDiagnostics.latestFieldNames()!!.contains("stream_options"))
    }

    @Test
    fun malformedBodyIsUnchangedAndClearsPreviousCapture() {
        OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(
            """{"model":"example/model","messages":[],"stream":true}"""
        )

        val body = "not json"
        assertEquals(body, OutboundRequestDiagnostics.sanitizeAndCaptureSerializedChatBody(body))
        assertNull(OutboundRequestDiagnostics.latestFieldNames())
    }
}
