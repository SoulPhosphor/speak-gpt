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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ProviderFilterStateTest {

    private fun endpoint(
        name: String,
        quant: String? = null,
        prompt: Double? = null,
        uptime: Double? = null,
        tools: Boolean? = null,
        caching: Boolean? = null,
        zdr: Boolean? = null
    ) = ProviderEndpointInfo(
        providerName = name, slug = name.lowercase(), quantization = quant,
        promptPrice = prompt, completionPrice = null, cacheReadPrice = null,
        cacheWritePrice = null, latency = null, throughput = null,
        uptime = uptime, supportsTools = tools, supportsCaching = caching, zdr = zdr
    )

    @Before
    fun resetState() = ProviderFilterState.reset()

    @After
    fun cleanUp() = ProviderFilterState.reset()

    @Test
    fun defaultIsAlphabetical() {
        val result = ProviderFilterState.apply(
            listOf(endpoint("Zeta"), endpoint("alpha"), endpoint("Mid"))
        )
        assertEquals(listOf("alpha", "Mid", "Zeta"), result.map { it.providerName })
    }

    @Test
    fun quantizationFilterKeepsOnlyMatches() {
        ProviderFilterState.quantization = "fp8"
        val result = ProviderFilterState.apply(
            listOf(endpoint("A", quant = "fp8"), endpoint("B", quant = "fp16"), endpoint("C"))
        )
        assertEquals(listOf("A"), result.map { it.providerName })
    }

    @Test
    fun requireFlagsDropUnknownAndFalse() {
        ProviderFilterState.requireTools = true
        val result = ProviderFilterState.apply(
            listOf(endpoint("A", tools = true), endpoint("B", tools = false), endpoint("C", tools = null))
        )
        assertEquals(listOf("A"), result.map { it.providerName })
    }

    @Test
    fun priceSortsBothDirectionsWithUnknownLast() {
        val endpoints = listOf(
            endpoint("Cheap", prompt = 0.000001),
            endpoint("Pricey", prompt = 0.00001),
            endpoint("Mystery", prompt = null)
        )

        ProviderFilterState.sortPrice = SortDirection.LOW_TO_HIGH
        assertEquals(
            listOf("Cheap", "Pricey", "Mystery"),
            ProviderFilterState.apply(endpoints).map { it.providerName }
        )

        ProviderFilterState.sortPrice = SortDirection.HIGH_TO_LOW
        assertEquals(
            listOf("Pricey", "Cheap", "Mystery"),
            ProviderFilterState.apply(endpoints).map { it.providerName }
        )
    }

    @Test
    fun tiedPrimarySortFallsThroughToNextKeyThenAlphabetical() {
        ProviderFilterState.sortPrice = SortDirection.LOW_TO_HIGH
        ProviderFilterState.sortUptime = SortDirection.HIGH_TO_LOW
        val result = ProviderFilterState.apply(
            listOf(
                endpoint("B", prompt = 0.000001, uptime = 95.0),
                endpoint("A", prompt = 0.000001, uptime = 99.0),
                endpoint("C", prompt = 0.000001, uptime = 99.0)
            )
        )
        // Same price → uptime high-to-low → alphabetical for the 99.0 tie.
        assertEquals(listOf("A", "C", "B"), result.map { it.providerName })
    }

    @Test
    fun resetRestoresTheDefaultView() {
        ProviderFilterState.sortPrice = SortDirection.HIGH_TO_LOW
        ProviderFilterState.quantization = "fp8"
        ProviderFilterState.requireZdr = true
        ProviderFilterState.reset()
        val result = ProviderFilterState.apply(
            listOf(endpoint("Zeta"), endpoint("alpha"))
        )
        assertEquals(listOf("alpha", "Zeta"), result.map { it.providerName })
    }
}
