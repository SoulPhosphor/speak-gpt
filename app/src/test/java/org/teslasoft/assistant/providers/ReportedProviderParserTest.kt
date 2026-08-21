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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.RawStreamObservationCodec

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

    @Test fun consumesObservedStreamToEndAndEmitsOneTerminalEnvelope() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        launch {
            channel.writeStringUtf8("data: {\"id\":\"gen-1\",\"model\":\"actual/model\",\"provider\":\"Open Inference\",\"choices\":[]}\n")
            repeat(200) {
                channel.writeStringUtf8("data: {\"id\":\"gen-1\",\"choices\":[{\"delta\":{\"content\":\"${"x".repeat(400)}\"}}]}\n")
            }
            channel.writeStringUtf8("data: {\"id\":\"gen-1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}\n")
            channel.writeStringUtf8("data: [DONE]\n")
            channel.close()
        }
        val signals = mutableListOf<String>()
        ReportedProviderParser.consumeObservedStream(channel) { signals.add(it) }

        assertEquals("Open Inference", signals.first())
        val raw = RawStreamObservationCodec.decode(signals.last())!!
        assertEquals(203, raw.sseDataEvents)
        assertEquals(200, raw.rawContentChunks)
        assertEquals("stop", raw.finishReason)
        assertTrue(raw.receivedDone)
        assertEquals("[DONE]", raw.protocolTerminalMarker)
        assertTrue(raw.usageReceived)
        assertEquals(10, raw.promptTokens)
        assertEquals(20, raw.completionTokens)
        assertEquals(30, raw.totalTokens)
        assertEquals("gen-1", raw.generationId)
        assertTrue(raw.flowEndedNormally)
        assertTrue(channel.isClosedForRead)
        assertEquals(0, channel.availableForRead)
    }

    @Test fun capturesResponseReportedModelForDurableAttribution() {
        val inspector = RawSseInspector()
        inspector.acceptLine("data: {\"id\":\"gen-model\",\"model\":\"actual/model\",\"choices\":[]}")
        assertEquals("actual/model", inspector.finishNormally().model)
    }

    @Test fun capturesProviderReportedChargedCostWithoutInventingSplit() {
        val inspector = RawSseInspector()
        inspector.acceptLine(
            "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20," +
                "\"total_tokens\":120,\"cost\":0.00123}}"
        )
        val usage = inspector.finishNormally()
        assertEquals(0.00123, usage.totalCost!!, 0.000000001)
        assertNull(usage.inputCost)
        assertNull(usage.outputCost)
    }

    @Test fun capturesExplicitProviderCostComponentsWhenReported() {
        val inspector = RawSseInspector()
        inspector.acceptLine(
            "{\"usage\":{\"cost\":0.003,\"cost_details\":" +
                "{\"prompt_cost\":0.001,\"completion_cost\":0.002}}}"
        )
        val usage = inspector.finishNormally()
        assertEquals(0.001, usage.inputCost!!, 0.000000001)
        assertEquals(0.002, usage.outputCost!!, 0.000000001)
        assertEquals(0.003, usage.totalCost!!, 0.000000001)
    }

    @Test fun reportsOnlyFirstProviderPlusTerminalEnvelope() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("data: {\"provider\":\"First\",\"choices\":[]}\n")
        channel.writeStringUtf8("data: {\"provider\":\"Second\",\"choices\":[]}\n")
        channel.close()
        val signals = mutableListOf<String>()
        ReportedProviderParser.consumeObservedStream(channel) { signals.add(it) }
        assertEquals("First", signals.first())
        assertEquals(2, signals.size)
        assertTrue(RawStreamObservationCodec.isEncoded(signals.last()))
    }

    @Test fun noProviderStillProducesTerminalEvidenceAndDrains() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("data: {\"id\":\"gen-3\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n")
        channel.writeStringUtf8("data: [DONE]\n")
        channel.close()
        val signals = mutableListOf<String>()
        ReportedProviderParser.consumeObservedStream(channel) { signals.add(it) }
        assertEquals(1, signals.size)
        val raw = RawStreamObservationCodec.decode(signals.single())!!
        assertEquals("gen-3", raw.generationId)
        assertTrue(raw.receivedDone)
        assertTrue(channel.isClosedForRead)
    }

    @Test fun midStreamProviderErrorIsCapturedWithoutLoggingGeneratedText() {
        val inspector = RawSseInspector()
        inspector.acceptLine("data: {\"id\":\"gen-2\",\"choices\":[{\"delta\":{\"content\":\"private partial text\"},\"finish_reason\":null}]}")
        inspector.acceptLine("data: {\"id\":\"gen-2\",\"error\":{\"code\":502,\"message\":\"upstream disconnected\",\"metadata\":{\"error_type\":\"provider_error\",\"provider_name\":\"StreamLake\"}},\"choices\":[{\"delta\":{},\"finish_reason\":\"error\"}]}")

        val result = inspector.finishNormally()
        assertTrue(result.providerErrorReceived)
        assertEquals("error", result.finishReason)
        assertTrue(result.providerErrorSummary!!.contains("code=502"))
        assertTrue(result.providerErrorSummary!!.contains("upstream disconnected"))
        assertTrue(result.providerErrorSummary!!.contains("provider=StreamLake"))
        assertFalse(result.receivedDone)
        assertFalse(result.toString().contains("private partial text"))
    }

    @Test fun eofWithoutDoneOrFinishRemainsIncompleteEvidence() {
        val inspector = RawSseInspector()
        inspector.acceptLine("data: {\"id\":\"gen-4\",\"choices\":[{\"delta\":{},\"finish_reason\":null}]}")
        val result = inspector.finishNormally()
        assertEquals("gen-4", result.generationId)
        assertNull(result.finishReason)
        assertFalse(result.receivedDone)
        assertTrue(result.flowEndedNormally)
    }

    @Test fun malformedDataIsCountedButNotInventedIntoProviderFailure() {
        val inspector = RawSseInspector()
        inspector.acceptLine("data: {not-json")
        val result = inspector.finishNormally()
        assertEquals(1, result.sseDataEvents)
        assertEquals(1, result.malformedDataEvents)
        assertFalse(result.providerErrorReceived)
        assertNull(result.finishReason)
    }

    @Test fun responsesStyleTerminalMarkerIsRecognized() {
        val inspector = RawSseInspector()
        inspector.acceptLine("data: {\"type\":\"response.done\",\"id\":\"resp-1\",\"usage\":{\"total_tokens\":12}}")
        val result = inspector.finishNormally()
        assertEquals("response.done", result.protocolTerminalMarker)
        assertTrue(result.usageReceived)
        assertEquals(12, result.totalTokens)
    }
}
