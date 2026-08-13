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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The serialization boundary: a [RoutingDecision] becomes OpenRouter's
 * `provider` object, and a request body gains exactly that object. Proves the
 * exact JSON per mode, and that re-processing a request never duplicates the
 * `provider` object.
 */
class ProviderRoutingSerializerTest {

    private val body = """{"model":"openai/gpt-4o","messages":[{"role":"user","content":"hi"}]}"""

    /* ------------------------------ provider object ------------------------------ */

    @Test
    fun automaticWithNoExclusionsProducesNoProviderObject() {
        val decision = RoutingDecision(RoutingBlock.NONE)
        assertNull(ProviderRoutingSerializer.providerObject(decision))
        // ...and the body is therefore untouched.
        assertEquals(body, ProviderRoutingSerializer.augmentBody(body, null))
    }

    @Test
    fun automaticWithExclusionsSendsOnlyIgnore() {
        val decision = RoutingDecision(RoutingBlock.NONE, ignore = listOf("openai"))
        val obj = ProviderRoutingSerializer.providerObject(decision)!!
        assertFalse(obj.has("order"))
        assertFalse(obj.has("allow_fallbacks"))
        assertEquals("openai", obj.getAsJsonArray("ignore")[0].asString)
    }

    @Test
    fun preferredSerializesOrderFallbacksAndIgnore() {
        val decision = RoutingDecision(
            RoutingBlock.NONE,
            order = listOf("deepinfra", "together"),
            ignore = listOf("openai"),
            allowFallbacks = true
        )
        val obj = ProviderRoutingSerializer.providerObject(decision)!!
        assertEquals("deepinfra", obj.getAsJsonArray("order")[0].asString)
        assertEquals("together", obj.getAsJsonArray("order")[1].asString)
        assertTrue(obj.get("allow_fallbacks").asBoolean)
        assertEquals("openai", obj.getAsJsonArray("ignore")[0].asString)
    }

    @Test
    fun preferredWithFallbacksOffSerializesFalse() {
        val decision = RoutingDecision(
            RoutingBlock.NONE, order = listOf("deepinfra"), allowFallbacks = false
        )
        val obj = ProviderRoutingSerializer.providerObject(decision)!!
        assertFalse(obj.get("allow_fallbacks").asBoolean)
    }

    @Test
    fun onlySerializesLiteralOnlyWithFallbacksDisabled() {
        val decision = RoutingDecision(RoutingBlock.NONE, only = "deepinfra")
        val obj = ProviderRoutingSerializer.providerObject(decision)!!
        val only = obj.getAsJsonArray("only")
        assertEquals(1, only.size())
        assertEquals("deepinfra", only[0].asString)
        assertFalse(obj.has("order"))
        assertFalse(obj.get("allow_fallbacks").asBoolean)
        // Only mode never sends an ignore list.
        assertFalse(obj.has("ignore"))
    }

    @Test
    fun blockedDecisionSerializesNothing() {
        assertNull(ProviderRoutingSerializer.providerObject(RoutingDecision(RoutingBlock.ONLY_PROVIDER_NOT_SELECTED)))
        assertNull(ProviderRoutingSerializer.providerObject(RoutingDecision(RoutingBlock.NO_PREFERRED_AVAILABLE)))
    }

    /* ------------------------------ body augmentation ------------------------------ */

    @Test
    fun augmentBodyAddsProviderAndPreservesModelAndMessages() {
        val decision = RoutingDecision(RoutingBlock.NONE, only = "deepinfra")
        val out = ProviderRoutingSerializer.augmentBody(body, ProviderRoutingSerializer.providerObject(decision))
        val root = JsonParser.parseString(out).asJsonObject
        // Model and messages are untouched — model selection stays independent.
        assertEquals("openai/gpt-4o", root.get("model").asString)
        assertTrue(root.has("messages"))
        assertEquals("deepinfra", root.getAsJsonObject("provider").getAsJsonArray("only")[0].asString)
    }

    @Test
    fun augmentBodyIsIdempotentAcrossReprocessing() {
        val decision = RoutingDecision(RoutingBlock.NONE, order = listOf("deepinfra"))
        val provider = ProviderRoutingSerializer.providerObject(decision)
        val once = ProviderRoutingSerializer.augmentBody(body, provider)
        // A rebuilt request (tool continuation / retry) passing through again
        // must not accumulate a second provider object.
        val twice = ProviderRoutingSerializer.augmentBody(once, provider)
        val root = JsonParser.parseString(twice).asJsonObject
        assertTrue(root.has("provider"))
        // Exactly one provider object; re-serialization is stable.
        assertEquals(once, twice)
    }

    @Test
    fun malformedBodyIsReturnedUnchanged() {
        val decision = RoutingDecision(RoutingBlock.NONE, only = "deepinfra")
        val provider = ProviderRoutingSerializer.providerObject(decision)
        assertEquals("not json", ProviderRoutingSerializer.augmentBody("not json", provider))
    }
}
