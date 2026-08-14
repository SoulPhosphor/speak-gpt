/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.providers.ReportedProviderParser

class MessageMetadataTest {

    @Test fun roundTripKeepsStructuredUsageSeparate() {
        val metadata = MessageMetadata(
            createdAt = 1234L,
            requestedModelId = "requested/model",
            actualModelId = "actual/model",
            endpointId = "endpoint-1",
            endpointLabel = "Router",
            endpointSource = "https://example.test/v1/chat/completions",
            configuredProvider = "OpenRouter",
            actualProvider = "Inference Co",
            responseId = "gen-123",
            inputTokens = 101L,
            outputTokens = 55L,
            totalTokens = 156L,
            providerUsageJson = "{\"reasoning_tokens\":7,\"cost\":0.003}",
            providerCostJson = "{\"provider_cost\":0.003}"
        )

        val json = JSONObject(metadata.toJson())
        val usage = json.getJSONObject("usage")
        assertEquals(101L, usage.getLong("inputTokens"))
        assertEquals(55L, usage.getLong("outputTokens"))
        assertEquals(156L, usage.getLong("totalTokens"))
        assertEquals(7, usage.getJSONObject("providerDetails").getInt("reasoning_tokens"))

        val restored = MessageMetadata.fromJson(json.toString())!!
        assertEquals("actual/model", restored.displayModelId())
        assertEquals("Inference Co", restored.displayProvider())
        assertEquals(101L, restored.inputTokens)
        assertEquals(55L, restored.outputTokens)
        assertEquals(156L, restored.totalTokens)
        assertTrue(restored.hasTokenUsage())
    }

    @Test fun missingUsageStaysMissing() {
        val metadata = MessageMetadata.createdNow(99L)
        val json = JSONObject(metadata.toJson())
        assertFalse(json.has("usage"))
        val restored = MessageMetadata.fromJson(json.toString())!!
        assertNull(restored.inputTokens)
        assertNull(restored.outputTokens)
        assertNull(restored.totalTokens)
        assertFalse(restored.hasTokenUsage())
    }

    @Test fun capturePrefersResponseReportedModelWithoutCollapsingTokens() {
        val capture = MessageMetadataCapture(
            messageIndex = 4,
            initial = MessageMetadata.createdNow(77L),
            requestedModelId = "requested/model",
            endpointId = "endpoint-1",
            endpointLabel = "Profile",
            endpointSource = "https://example.test/v1/chat/completions",
            configuredProvider = "Configured Provider"
        )
        capture.noteTypedChunk("gen-1", input = 20, output = 9, total = 29)
        capture.noteObserved(
            ReportedProviderParser.ResponseMetadata(
                modelId = "actual/model",
                provider = "Actual Provider",
                usageJson = "{\"prompt_tokens\":20,\"completion_tokens\":9}"
            )
        )
        val result = capture.snapshot()
        assertEquals("actual/model", result.displayModelId())
        assertEquals("Actual Provider", result.displayProvider())
        assertEquals(20L, result.inputTokens)
        assertEquals(9L, result.outputTokens)
        assertEquals(29L, result.totalTokens)
    }

    @Test fun damagedOrLegacyRecordsDoNotAcquireInventedValues() {
        assertNull(MessageMetadata.fromJson(null))
        assertNull(MessageMetadata.fromJson("not json"))
        assertNull(MessageMetadata.fromMessage(mapOf("message" to "legacy")))
    }
}
