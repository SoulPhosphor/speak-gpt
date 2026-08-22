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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningCapabilityLifecycleTest {

    private val catalog = """
        {"data":[
          {"id":"vendor/reasoner","supported_parameters":["reasoning"],
           "reasoning":{"supported_efforts":["high","low"],"mandatory":true,"supports_max_tokens":false}},
          {"id":"vendor/plain","supported_parameters":["tools","temperature"]}
        ]}
    """.trimIndent()

    @Test
    fun catalogToPersistenceToUiAndRequestUsesOneCapabilityRecord() {
        val refresh = EndpointReasoningCapability.refreshFromOpenRouterCatalog(null, catalog)
        assertNotNull(refresh)
        assertEquals(listOf("vendor/reasoner", "vendor/plain"), refresh!!.models.map { it.id })

        val capable = EndpointReasoningCapability.resolve(
            refresh.capabilityJson,
            "vendor/reasoner",
            providerPath = ReasoningProviderPath.OPENROUTER
        )
        val plain = EndpointReasoningCapability.resolve(
            refresh.capabilityJson,
            "vendor/plain",
            providerPath = ReasoningProviderPath.OPENROUTER
        )

        // View All and favorite consumers use these shared predicates.
        assertTrue(capable.isReasoningCapable)
        assertTrue(capable.hasConfigurableSetting)
        assertFalse(plain.isReasoningCapable)
        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.LOW), capable.supportedEfforts)

        val resolved = ReasoningSettingsResolver.resolve(
            conversationOverride = ReasoningEffort.HIGH,
            favoriteEffort = null,
            favoriteShowReasoning = true,
            capability = capable
        )
        val fields = ReasoningRequestSerializer.requestFields(
            resolved,
            isOpenRouter = true,
            reasoningCapable = capable.isReasoningCapable
        )
        assertEquals("high", fields!!.getAsJsonObject("reasoning").get("effort").asString)
    }

    @Test
    fun observedReasoningPromotesUnknownWithoutInventingEfforts() {
        val updated = EndpointReasoningCapability.learnFromObservedResponse(null, "vendor/unknown")
        val capability = EndpointReasoningCapability.resolve(updated, "vendor/unknown")
        assertTrue(capability.isReasoningCapable)
        assertTrue(capability.canReturnVisibleReasoning)
        assertFalse(capability.effortConfigurable)
        assertTrue(capability.supportedEfforts.isEmpty())
        assertEquals(CapabilitySource.OBSERVED_RESPONSE, capability.source)
    }

    @Test
    fun authoritativeMetadataSupersedesObservedEvidence() {
        val observed = EndpointReasoningCapability.learnFromObservedResponse(null, "vendor/reasoner")
        val refresh = EndpointReasoningCapability.refreshFromOpenRouterCatalog(observed, catalog)!!
        val capability = ReasoningCapabilityStore.get(refresh.capabilityJson, "vendor/reasoner")
        assertEquals(CapabilitySource.PROVIDER_METADATA, capability.source)
        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.LOW), capability.supportedEfforts)
        assertTrue(capability.effortsAuthoritative)
    }

    @Test
    fun observedResponseDoesNotOverwriteAuthoritativeAbsence() {
        val refresh = EndpointReasoningCapability.refreshFromOpenRouterCatalog(null, catalog)!!
        val unchanged = EndpointReasoningCapability.learnFromObservedResponse(
            refresh.capabilityJson,
            "vendor/plain"
        )
        assertEquals(refresh.capabilityJson, unchanged)
        assertEquals(
            ReasoningSupport.ABSENT,
            ReasoningCapabilityStore.get(unchanged, "vendor/plain").support
        )
    }

    @Test
    fun failedCatalogRefreshPreservesKnownCache() {
        val known = EndpointReasoningCapability.learnFromObservedResponse(null, "vendor/reasoner")
        assertEquals(null, EndpointReasoningCapability.refreshFromOpenRouterCatalog(known, "not json"))
        assertEquals(known, EndpointReasoningCapability.learnFromCatalogJson(known, "not json"))
    }

    @Test
    fun conclusiveCleanupRemovesOnlyGoneModels() {
        var store = EndpointReasoningCapability.learnFromObservedResponse(null, "vendor/keep")
        store = EndpointReasoningCapability.learnFromObservedResponse(store, "vendor/gone")
        val conclusiveCatalog = """
            {"data":[{"id":"vendor/keep","supported_parameters":["reasoning"]}]}
        """.trimIndent()
        val pruned = EndpointReasoningCapability
            .refreshFromOpenRouterCatalog(store, conclusiveCatalog)!!
            .capabilityJson
        assertTrue(ReasoningCapabilityStore.get(pruned, "vendor/keep").isReasoningCapable)
        assertEquals(ReasoningSupport.UNKNOWN, ReasoningCapabilityStore.get(pruned, "vendor/gone").support)
    }
}
