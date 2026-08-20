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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointReasoningCapabilityTest {

    private fun entry(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun liveEntryWinsOverEverything() {
        val cap = EndpointReasoningCapability.resolve(
            reasoningCapabilityByModel = ReasoningCapabilityStore.EMPTY,
            modelId = "gpt-4o", // id tier would say Unknown
            liveModelEntry = entry("""{"supported_parameters":["reasoning"]}""")
        )
        assertEquals(CapabilitySource.PROVIDER_METADATA, cap.source)
        assertTrue(cap.effortConfigurable)
    }

    @Test
    fun persistedMetadataUsedWhenNoLiveEntry() {
        // grok-style id the direct-provider tier does not recognize, but which
        // was learned earlier and stored.
        val stored = EndpointReasoningCapability.learnFromEntry(
            ReasoningCapabilityStore.EMPTY,
            "x-ai/grok-4",
            entry("""{"supported_parameters":["reasoning"]}""")
        )
        val cap = EndpointReasoningCapability.resolve(stored, "x-ai/grok-4")
        assertTrue(cap.isReasoningCapable)
        assertTrue(cap.effortConfigurable)
    }

    @Test
    fun fallsBackToIdKnowledgeWhenNothingStored() {
        val cap = EndpointReasoningCapability.resolve(ReasoningCapabilityStore.EMPTY, "o3-mini")
        assertEquals(CapabilitySource.PROVIDER_ADAPTER, cap.source)
    }

    @Test
    fun unknownWhenNoSourceEstablishesCapability() {
        val cap = EndpointReasoningCapability.resolve(ReasoningCapabilityStore.EMPTY, "gpt-4o")
        assertEquals(ReasoningCapability.UNKNOWN, cap)
    }

    @Test
    fun learnFromCatalogRecordsOnlyReasoningModels() {
        val catalog = """
            {"data":[
              {"id":"x-ai/grok-4","supported_parameters":["reasoning"]},
              {"id":"plain/model","supported_parameters":["tools"]},
              {"id":"d/r1","supported_parameters":["include_reasoning"]}
            ]}
        """.trimIndent()
        val store = EndpointReasoningCapability.learnFromCatalogJson(ReasoningCapabilityStore.EMPTY, catalog)

        assertTrue(EndpointReasoningCapability.resolve(store, "x-ai/grok-4").effortConfigurable)
        assertTrue(EndpointReasoningCapability.resolve(store, "d/r1").isReasoningCapable)
        // A non-reasoning model is never recorded; it resolves via id tiers only,
        // which do not know "plain/model", so it stays Unknown.
        assertEquals(ReasoningCapability.UNKNOWN, EndpointReasoningCapability.resolve(store, "plain/model"))
    }

    @Test
    fun learnFromCatalogLeavesStoreUnchangedOnJunk() {
        assertEquals(
            ReasoningCapabilityStore.EMPTY,
            EndpointReasoningCapability.learnFromCatalogJson(ReasoningCapabilityStore.EMPTY, "not json")
        )
        assertEquals(
            ReasoningCapabilityStore.EMPTY,
            EndpointReasoningCapability.learnFromCatalogJson(ReasoningCapabilityStore.EMPTY, null)
        )
    }

    @Test
    fun learnFromEntryOnlyChangesStoreWhenSomethingLearned() {
        val noReasoning = EndpointReasoningCapability.learnFromEntry(
            ReasoningCapabilityStore.EMPTY, "m", entry("""{"supported_parameters":["tools"]}""")
        )
        assertEquals(ReasoningCapabilityStore.EMPTY, noReasoning)

        val learned = EndpointReasoningCapability.learnFromEntry(
            ReasoningCapabilityStore.EMPTY, "m", entry("""{"supported_parameters":["reasoning"]}""")
        )
        assertNotEquals(ReasoningCapabilityStore.EMPTY, learned)
    }
}
