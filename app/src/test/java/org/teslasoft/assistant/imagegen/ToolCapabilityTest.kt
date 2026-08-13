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

package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/**
 * §8 of image-generation-rebuild-plan.md: tool capability is tracked per
 * exact endpoint/model pair in the same store shape as image capability,
 * and UNSUPPORTED is learned ONLY from a clear tools-not-supported
 * provider error — never from a timeout, content refusal, or unrelated
 * error (§29).
 */
class ToolCapabilityTest {

    // --- store ---

    @Test
    fun unknownIsTheDefaultForEveryUnrecordedModel() {
        assertEquals(ToolCapability.UNKNOWN, ToolCapabilityStore.get("", "some-model"))
        assertEquals(ToolCapability.UNKNOWN, ToolCapabilityStore.get(null, "some-model"))
        assertEquals(ToolCapability.UNKNOWN, ToolCapabilityStore.get("{}", "some-model"))
        assertEquals(ToolCapability.UNKNOWN, ToolCapabilityStore.get("not json", "some-model"))
    }

    @Test
    fun learnedStatesRoundTrip() {
        var json = ToolCapabilityStore.set("", "model-a", ToolCapability.UNSUPPORTED)
        json = ToolCapabilityStore.set(json, "model-b", ToolCapability.SUPPORTED)
        assertEquals(ToolCapability.UNSUPPORTED, ToolCapabilityStore.get(json, "model-a"))
        assertEquals(ToolCapability.SUPPORTED, ToolCapabilityStore.get(json, "model-b"))
        assertEquals(ToolCapability.UNKNOWN, ToolCapabilityStore.get(json, "model-c"))
    }

    @Test
    fun settingUnknownRemovesTheEntryAndClearForgetsEverything() {
        var json = ToolCapabilityStore.set("", "model-a", ToolCapability.UNSUPPORTED)
        json = ToolCapabilityStore.set(json, "model-a", ToolCapability.UNKNOWN)
        assertTrue(ToolCapabilityStore.isEmpty(json))
        assertEquals(ToolCapabilityStore.EMPTY, ToolCapabilityStore.clear())
    }

    @Test
    fun entriesListEverythingRecordedInDeterministicOrder() {
        var json = ToolCapabilityStore.set("", "zeta", ToolCapability.SUPPORTED)
        json = ToolCapabilityStore.set(json, "alpha", ToolCapability.UNSUPPORTED)
        val entries = ToolCapabilityStore.entries(json)
        assertEquals(listOf("alpha", "zeta"), entries.map { it.first })
    }

    // --- OpenRouter routing scope ---

    @Test
    fun directEndpointsKeepTheHistoricalModelOnlyKey() {
        assertEquals(
            "deepseek/deepseek-v3.1",
            ToolCapabilityScope.key("deepseek/deepseek-v3.1", openRouterRouting = false)
        )
    }

    @Test
    fun onlyProvidersAndAutomaticDoNotShareCapabilityLearning() {
        val automatic = ToolCapabilityScope.key(
            "deepseek/deepseek-v3.1", openRouterRouting = true
        )
        val deepSeekOnly = ToolCapabilityScope.key(
            "deepseek/deepseek-v3.1",
            openRouterRouting = true,
            routingType = FavoriteModelObject.ROUTING_ONLY,
            selectedProvider = "DeepSeek"
        )
        val deepInfraOnly = ToolCapabilityScope.key(
            "deepseek/deepseek-v3.1",
            openRouterRouting = true,
            routingType = FavoriteModelObject.ROUTING_ONLY,
            selectedProvider = "DeepInfra"
        )

        assertNotEquals(automatic, deepSeekOnly)
        assertNotEquals(deepSeekOnly, deepInfraOnly)

        val json = ToolCapabilityStore.set("", deepSeekOnly, ToolCapability.UNSUPPORTED)
        assertEquals(ToolCapability.UNSUPPORTED, ToolCapabilityStore.get(json, deepSeekOnly))
        assertEquals(ToolCapability.UNKNOWN, ToolCapabilityStore.get(json, automatic))
        assertEquals(ToolCapability.UNKNOWN, ToolCapabilityStore.get(json, deepInfraOnly))
    }

    @Test
    fun preferredScopeIncludesOrderFallbackAndIgnoreConfiguration() {
        val first = ToolCapabilityScope.key(
            "model",
            openRouterRouting = true,
            routingType = FavoriteModelObject.ROUTING_PREFERRED,
            allowFallbacks = false,
            providerOrder = listOf("DeepInfra", "Together"),
            ignoredProviders = listOf("OpenAI", "Fireworks")
        )
        val reordered = ToolCapabilityScope.key(
            "model",
            openRouterRouting = true,
            routingType = FavoriteModelObject.ROUTING_PREFERRED,
            allowFallbacks = false,
            providerOrder = listOf("Together", "DeepInfra"),
            ignoredProviders = listOf("fireworks", "openai")
        )
        val ignoredOrderOnly = ToolCapabilityScope.key(
            "model",
            openRouterRouting = true,
            routingType = FavoriteModelObject.ROUTING_PREFERRED,
            allowFallbacks = false,
            providerOrder = listOf("DeepInfra", "Together"),
            ignoredProviders = listOf("fireworks", "openai")
        )

        assertNotEquals(first, reordered)
        assertEquals(first, ignoredOrderOnly)
    }

    // --- the §29 strictness guard ---

    @Test
    fun clearToolsNotSupportedErrorsMatch() {
        assertTrue(
            ToolSupportClassifier.isToolsNotSupportedError(
                "Invalid parameter: 'tools' is not supported by this model."
            )
        )
        assertTrue(
            ToolSupportClassifier.isToolsNotSupportedError(
                "No endpoints found that support tool use"
            )
        )
        assertTrue(
            ToolSupportClassifier.isToolsNotSupportedError(
                "function calling is not supported for this model"
            )
        )
    }

    @Test
    fun timeoutsContentRefusalsAndUnrelatedErrorsNeverMatch() {
        assertFalse(ToolSupportClassifier.isToolsNotSupportedError("read timed out"))
        assertFalse(
            ToolSupportClassifier.isToolsNotSupportedError(
                "Your request was rejected by the content policy."
            )
        )
        assertFalse(ToolSupportClassifier.isToolsNotSupportedError("insufficient credits"))
        assertFalse(ToolSupportClassifier.isToolsNotSupportedError("internal server error"))
        assertFalse(ToolSupportClassifier.isToolsNotSupportedError(null))
        assertFalse(ToolSupportClassifier.isToolsNotSupportedError(""))
    }

    /** Mentioning tools without a clear not-supported signal is not enough
     *  — a model that merely fails while tools are attached stays UNKNOWN. */
    @Test
    fun mentioningToolsAloneIsNotEnough() {
        assertFalse(
            ToolSupportClassifier.isToolsNotSupportedError(
                "the tool call arguments were malformed"
            )
        )
    }
}
