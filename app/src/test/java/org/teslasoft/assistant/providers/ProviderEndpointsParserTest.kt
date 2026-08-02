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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider-discovery parser must be tolerant: any missing optional field
 * becomes null (rendered "?"), and only a response with no readable endpoints
 * array is a failure.
 */
class ProviderEndpointsParserTest {

    private val fullBody = """
        {"data": {"id": "openai/gpt-4o", "endpoints": [
            {
                "name": "OpenAI | openai/gpt-4o",
                "provider_name": "OpenAI",
                "tag": "openai",
                "quantization": null,
                "pricing": {"prompt": "0.0000025", "completion": "0.00001",
                            "input_cache_read": "0.00000125"},
                "supported_parameters": ["tools", "tool_choice", "max_tokens"],
                "uptime_last_30m": 99.5,
                "is_zdr": true
            },
            {
                "provider_name": "DeepInfra",
                "tag": "deepinfra",
                "quantization": "fp8",
                "pricing": {"prompt": "0.000001", "completion": "0.000002"},
                "supported_parameters": ["max_tokens"],
                "uptime_last_30m": 97.1
            }
        ]}}
    """.trimIndent()

    @Test
    fun parsesFullEndpointFields() {
        val list = ProviderEndpointsParser.parse(fullBody)!!
        assertEquals(2, list.size)

        val openai = list[0]
        assertEquals("OpenAI", openai.providerName)
        assertEquals("openai", openai.slug)
        assertNull(openai.quantization)
        assertEquals(0.0000025, openai.promptPrice!!, 1e-12)
        assertEquals(0.00001, openai.completionPrice!!, 1e-12)
        assertEquals(0.00000125, openai.cacheReadPrice!!, 1e-12)
        assertEquals(99.5, openai.uptime!!, 1e-9)
        assertTrue(openai.supportsTools == true)
        assertTrue(openai.supportsCaching == true)
        assertTrue(openai.zdr == true)
    }

    @Test
    fun missingOptionalFieldsBecomeUnknownOrDerived() {
        val list = ProviderEndpointsParser.parse(fullBody)!!
        val deepinfra = list[1]
        assertEquals("fp8", deepinfra.quantization)
        // Parameter list present without "tools" → known false, not unknown.
        assertFalse(deepinfra.supportsTools!!)
        // Pricing present without cache prices → no caching.
        assertFalse(deepinfra.supportsCaching!!)
        // No zdr field at all → unknown.
        assertNull(deepinfra.zdr)
        // No latency/throughput fields → unknown.
        assertNull(deepinfra.latency)
        assertNull(deepinfra.throughput)
    }

    @Test
    fun endpointWithoutParameterListHasUnknownToolSupport() {
        val body = """{"data":{"endpoints":[{"provider_name":"Mystery"}]}}"""
        val list = ProviderEndpointsParser.parse(body)!!
        assertEquals(1, list.size)
        assertNull(list[0].supportsTools)
        assertNull(list[0].supportsCaching)
        assertNull(list[0].promptPrice)
        // No tag → slug falls back to the display name.
        assertEquals("Mystery", list[0].slug)
    }

    @Test
    fun malformedBodiesFailInsteadOfInventingData() {
        assertNull(ProviderEndpointsParser.parse("not json at all"))
        assertNull(ProviderEndpointsParser.parse("""{"error":{"message":"no such model"}}"""))
        assertNull(ProviderEndpointsParser.parse("<html>gateway error</html>"))
    }

    @Test
    fun emptyEndpointsArrayIsAnEmptyListNotAFailure() {
        val list = ProviderEndpointsParser.parse("""{"data":{"endpoints":[]}}""")
        assertEquals(0, list!!.size)
    }
}
