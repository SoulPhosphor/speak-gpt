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
}
