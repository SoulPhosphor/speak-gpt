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

package org.teslasoft.assistant.providers

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportedProviderParserTest {

    @Test fun readsOpenRouterSelectedEndpointFromOfficialRouterMetadata() {
        assertEquals(
            "Open Inference",
            ReportedProviderParser.fromResponseLine(
                "data: {\"openrouter_metadata\":{\"endpoints\":{\"available\":[" +
                    "{\"provider\":\"Backup\",\"selected\":false}," +
                    "{\"provider\":\"Open Inference\",\"selected\":true}]}}}"
            )
        )
    }

    @Test fun readsProviderReturnedByOpenRouterStream() {
        assertEquals(
            "Open Inference",
            ReportedProviderParser.fromResponseLine(
                "data: {\"id\":\"gen-1\",\"provider\":\"Open Inference\",\"choices\":[]}"
            )
        )
    }

    @Test fun readsProviderFromPlainJsonToo() {
        assertEquals(
            "OpenAI",
            ReportedProviderParser.fromResponseLine("{\"provider\":\"OpenAI\",\"choices\":[]}")
        )
    }

    @Test fun neverInventsProviderWhenResponseDoesNotReportOne() {
        assertNull(ReportedProviderParser.fromResponseLine("data: {\"choices\":[]}"))
        assertNull(ReportedProviderParser.fromResponseLine(
            "data: {\"openrouter_metadata\":{\"endpoints\":{\"available\":[" +
                "{\"provider\":\"Candidate\",\"selected\":false}]}}}"
        ))
        assertNull(ReportedProviderParser.fromResponseLine("data: [DONE]"))
        assertNull(ReportedProviderParser.fromResponseLine(": OPENROUTER PROCESSING"))
        assertNull(ReportedProviderParser.fromResponseLine("data: {broken"))
    }

    @Test fun readsModelResponseIdAndStructuredUsageWithoutFlatteningIt() {
        val metadata = ReportedProviderParser.metadataFromResponseLine(
            "data: {\"id\":\"gen-9\",\"model\":\"actual/model\",\"provider\":\"Provider A\"," +
                "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4," +
                "\"total_tokens\":14,\"cost\":0.002},\"provider_cost\":0.003,\"choices\":[]}"
        )!!
        assertEquals("gen-9", metadata.responseId)
        assertEquals("actual/model", metadata.modelId)
        assertEquals("Provider A", metadata.provider)
        assertEquals(10L, metadata.inputTokens)
        assertEquals(4L, metadata.outputTokens)
        assertEquals(14L, metadata.totalTokens)
        assertEquals(10, JSONObject(metadata.usageJson!!).getInt("prompt_tokens"))
        assertEquals(0.003, JSONObject(metadata.costJson!!).getDouble("provider_cost"), 0.0)
    }

    @Test fun nonIntegerTokenExtensionsArePreservedButNeverInventedAsCounts() {
        val metadata = ReportedProviderParser.metadataFromResponseLine(
            "data: {\"usage\":{\"input_tokens\":\"unknown\",\"output_tokens\":4.5},\"choices\":[]}"
        )!!
        assertNull(metadata.inputTokens)
        assertNull(metadata.outputTokens)
        assertEquals("unknown", JSONObject(metadata.usageJson!!).getString("input_tokens"))
    }

    // The observed copy of a generation stream must be read through to its
    // end even after the provider is found: stopping early stalls Ktor's
    // channel splitter and freezes the live reply the copy was split from.
    @Test fun consumesObservedStreamToEndAfterFindingProvider() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        launch {
            channel.writeStringUtf8("data: {\"id\":\"gen-1\",\"provider\":\"Open Inference\",\"choices\":[]}\n")
            repeat(200) {
                channel.writeStringUtf8("data: {\"id\":\"gen-1\",\"choices\":[{\"delta\":{\"content\":\"${"x".repeat(400)}\"}}]}\n")
            }
            channel.writeStringUtf8("data: [DONE]\n")
            channel.close()
        }
        val providers = mutableListOf<String>()
        ReportedProviderParser.consumeObservedStream(channel) { providers.add(it) }
        assertEquals(listOf("Open Inference"), providers)
        assertTrue(channel.isClosedForRead)
        assertEquals(0, channel.availableForRead)
    }

    @Test fun reportsOnlyFirstProviderSeenInObservedStream() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("data: {\"provider\":\"First\",\"choices\":[]}\n")
        channel.writeStringUtf8("data: {\"provider\":\"Second\",\"choices\":[]}\n")
        channel.close()
        val providers = mutableListOf<String>()
        ReportedProviderParser.consumeObservedStream(channel) { providers.add(it) }
        assertEquals(listOf("First"), providers)
    }

    @Test fun consumesObservedStreamFullyWhenNoProviderIsReported() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n")
        channel.writeStringUtf8("data: [DONE]\n")
        channel.close()
        val providers = mutableListOf<String>()
        ReportedProviderParser.consumeObservedStream(channel) { providers.add(it) }
        assertTrue(providers.isEmpty())
        assertTrue(channel.isClosedForRead)
    }

    @Test fun metadataStreamKeepsLateUsageAfterIdentityWasAlreadyFound() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        launch {
            channel.writeStringUtf8(
                "data: {\"id\":\"gen-1\",\"model\":\"model-a\",\"provider\":\"Provider A\",\"choices\":[]}\n"
            )
            repeat(20) {
                channel.writeStringUtf8(
                    "data: {\"id\":\"gen-1\",\"model\":\"model-a\",\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n"
                )
            }
            channel.writeStringUtf8(
                "data: {\"id\":\"gen-1\",\"model\":\"model-a\",\"usage\":{\"input_tokens\":12,\"output_tokens\":8,\"total_tokens\":20},\"choices\":[]}\n"
            )
            channel.close()
        }
        val results = mutableListOf<ReportedProviderParser.ResponseMetadata>()
        ReportedProviderParser.consumeObservedMetadataStream(channel) { results.add(it) }
        assertEquals("Provider A", results.first().provider)
        assertEquals(12L, results.last().inputTokens)
        assertEquals(8L, results.last().outputTokens)
        assertEquals(20L, results.last().totalTokens)
        assertTrue(channel.isClosedForRead)
    }
}
