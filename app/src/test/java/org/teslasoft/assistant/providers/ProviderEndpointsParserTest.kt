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
 * array is a failure. Latency/throughput arrive as percentile objects (p50 is
 * displayed); implicit-caching support comes ONLY from the API's explicit
 * field; ZDR comes from the separate /endpoints/zdr list (parseZdrMatches).
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
                "latency_last_30m": {"p50": 0.45, "p90": 1.2},
                "throughput_last_30m": {"p50": 55.3},
                "supports_implicit_caching": true
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
        val list = ProviderEndpointsParser.parse(fullBody)!!.endpoints
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
    }

    @Test
    fun latencyAndThroughputReadP50FromPercentileObjects() {
        val openai = ProviderEndpointsParser.parse(fullBody)!!.endpoints[0]
        assertEquals(0.45, openai.latency!!, 1e-9)
        assertEquals(55.3, openai.throughput!!, 1e-9)
    }

    @Test
    fun plainNumberStatsAreStillAccepted() {
        val body = """{"data":{"endpoints":[{"provider_name":"Flat","latency_last_30m":0.7}]}}"""
        assertEquals(0.7, ProviderEndpointsParser.parse(body)!!.endpoints[0].latency!!, 1e-9)
    }

    @Test
    fun cachingComesOnlyFromTheExplicitField() {
        val list = ProviderEndpointsParser.parse(fullBody)!!.endpoints
        // DeepInfra has pricing but no supports_implicit_caching → unknown,
        // NOT inferred from the absence of cache pricing.
        assertNull(list[1].supportsCaching)
    }

    @Test
    fun missingOptionalFieldsBecomeUnknown() {
        val deepinfra = ProviderEndpointsParser.parse(fullBody)!!.endpoints[1]
        assertEquals("fp8", deepinfra.quantization)
        // Parameter list present without "tools" → known false, not unknown.
        assertFalse(deepinfra.supportsTools!!)
        // ZDR is not part of this response → unknown here.
        assertNull(deepinfra.zdr)
        assertNull(deepinfra.latency)
        assertNull(deepinfra.throughput)
    }

    @Test
    fun endpointWithoutParameterListHasUnknownToolSupport() {
        val body = """{"data":{"endpoints":[{"provider_name":"Mystery"}]}}"""
        val list = ProviderEndpointsParser.parse(body)!!.endpoints
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
        val result = ProviderEndpointsParser.parse("""{"data":{"endpoints":[]}}""")!!
        assertEquals(0, result.endpoints.size)
        // ...but an empty list can never confirm an absence.
        assertFalse(result.authoritative)
    }

    /* ------------------------------ completeness ------------------------------ */

    @Test
    fun completeUnpaginatedResponsesAreAuthoritative() {
        assertTrue(ProviderEndpointsParser.parse(fullBody)!!.authoritative)
    }

    @Test
    fun paginationSignalsMakeTheResultNonAuthoritative() {
        val hasMore = """{"has_more":true,"data":{"endpoints":[{"provider_name":"A"}]}}"""
        assertFalse(ProviderEndpointsParser.parse(hasMore)!!.authoritative)

        val nextCursor = """{"data":{"endpoints":[{"provider_name":"A"}],"next_cursor":"abc"}}"""
        assertFalse(ProviderEndpointsParser.parse(nextCursor)!!.authoritative)

        val paged = """{"page":1,"total_pages":3,"data":{"endpoints":[{"provider_name":"A"}]}}"""
        assertFalse(ProviderEndpointsParser.parse(paged)!!.authoritative)

        val links = """{"links":{"next":"https://x/page2"},"data":{"endpoints":[{"provider_name":"A"}]}}"""
        assertFalse(ProviderEndpointsParser.parse(links)!!.authoritative)
    }

    @Test
    fun explicitNullNextDoesNotDisqualifyCompleteness() {
        val body = """{"data":{"endpoints":[{"provider_name":"A"}],"next":null}}"""
        assertTrue(ProviderEndpointsParser.parse(body)!!.authoritative)
    }

    /* ------------------------------ ZDR list ------------------------------ */

    private val zdrBody = """
        {"data": [
            {"name": "OpenAI | openai/gpt-4o", "provider_name": "OpenAI", "tag": "openai",
             "model_variant_slug": "openai/gpt-4o"},
            {"name": "Azure | openai/gpt-4o", "provider_name": "Azure", "tag": "azure",
             "model_variant_slug": "openai/gpt-4o"},
            {"name": "OpenAI | openai/gpt-4.1", "provider_name": "OpenAI", "tag": "openai",
             "model_variant_slug": "openai/gpt-4.1"}
        ]}
    """.trimIndent()

    @Test
    fun zdrMatchesOnlyTheRequestedModel() {
        val matches = ProviderEndpointsParser.parseZdrMatches(zdrBody, "openai/gpt-4o")!!
        assertTrue("openai" in matches)
        assertTrue("azure" in matches)
        // Records for other models never leak in.
        val other = ProviderEndpointsParser.parseZdrMatches(zdrBody, "meta-llama/llama-3-70b")!!
        assertTrue(other.isEmpty())
    }

    @Test
    fun zdrModelIdentityFallsBackToTheCompositeName() {
        val body = """{"data":[{"name":"Together | mistralai/mixtral-8x7b", "provider_name":"Together"}]}"""
        val matches = ProviderEndpointsParser.parseZdrMatches(body, "mistralai/mixtral-8x7b")!!
        assertTrue("together" in matches)
    }

    @Test
    fun zdrRecordsWithoutModelIdentityAreSkippedNotOverclaimed() {
        val body = """{"data":[{"provider_name":"Vague"}]}"""
        assertTrue(ProviderEndpointsParser.parseZdrMatches(body, "openai/gpt-4o")!!.isEmpty())
    }

    @Test
    fun unreadableZdrBodiesReturnNullSoTheColumnStaysUnknown() {
        assertNull(ProviderEndpointsParser.parseZdrMatches("not json", "openai/gpt-4o"))
        assertNull(ProviderEndpointsParser.parseZdrMatches("""{"error":"x"}""", "openai/gpt-4o"))
    }
}
