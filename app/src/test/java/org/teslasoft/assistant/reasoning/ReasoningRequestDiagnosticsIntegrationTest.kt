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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.teslasoft.assistant.util.OutboundRequestDiagnostics

class ReasoningRequestDiagnosticsIntegrationTest {

    @Test
    fun autoReasoningStillRunsFinalRequestDiagnostics() {
        val body = """{
            "model":"stealth/ox-alpha",
            "messages":[],
            "max_tokens":8000,
            "logit_bias":{},
            "stream":true
        }""".trimIndent()
        val resolved = ResolvedReasoning(
            ReasoningEffort.AUTO,
            true,
            ResolvedReasoning.Source.FAVORITE_DEFAULT
        )

        val out = ReasoningRequestSerializer.augmentBody(
            body,
            resolved,
            isOpenRouter = true,
            reasoningCapable = true
        )
        val root = JsonParser.parseString(out).asJsonObject

        assertFalse(root.has("logit_bias"))
        // Auto with Show Reasoning On relies on OpenRouter's documented default
        // return behavior and sends no reasoning object. Final-request
        // diagnostics still run and strip the empty logit_bias.
        assertFalse(root.has("reasoning"))
        assertEquals(
            listOf("max_tokens", "messages", "model", "stream"),
            OutboundRequestDiagnostics.latestFieldNames()
        )
    }
}
