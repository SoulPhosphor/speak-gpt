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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningCapabilityResolverTest {

    private fun entry(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun metadataWinsOverAdapterKnowledge() {
        // Even though "openai/o3" is a known reasoning family (adapter tier),
        // an OpenRouter entry present for it should decide via metadata.
        val cap = ReasoningCapabilityResolver.resolve(
            modelId = "openai/o3",
            openRouterModelEntry = entry("""{"supported_parameters":["reasoning"]}""")
        )
        assertEquals(CapabilitySource.PROVIDER_METADATA, cap.source)
    }

    @Test
    fun adapterKnowledgeUsedWhenNoMetadataEntry() {
        val cap = ReasoningCapabilityResolver.resolve(modelId = "o3-mini")
        assertEquals(CapabilitySource.PROVIDER_ADAPTER, cap.source)
        assertEquals(ReasoningSupport.KNOWN, cap.support)
    }

    @Test
    fun variantMarkerUsedWhenAdapterDoesNotRecognizeModel() {
        val cap = ReasoningCapabilityResolver.resolve(modelId = "vendor/model:thinking")
        assertEquals(CapabilitySource.VARIANT_MARKER, cap.source)
    }

    @Test
    fun unknownWhenNothingEstablishesCapability() {
        val cap = ReasoningCapabilityResolver.resolve(modelId = "gpt-4o")
        assertEquals(ReasoningCapability.UNKNOWN, cap)
        assertEquals(CapabilitySource.NONE, cap.source)
    }

    @Test
    fun metadataWithoutReasoningFallsThroughToAdapter() {
        // The entry exists but advertises no reasoning; the model id is still a
        // known reasoning family, so the adapter tier should classify it.
        val cap = ReasoningCapabilityResolver.resolve(
            modelId = "o3",
            openRouterModelEntry = entry("""{"supported_parameters":["tools","max_tokens"]}""")
        )
        assertEquals(CapabilitySource.PROVIDER_ADAPTER, cap.source)
    }
}
