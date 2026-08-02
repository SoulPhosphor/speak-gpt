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

/** Direction for one of the provider chart's sort dropdowns. */
enum class SortDirection { NONE, HIGH_TO_LOW, LOW_TO_HIGH }

/**
 * Shared filter state for the provider chart, edited in place by the Filters
 * pull-out panel — the same auto-apply mechanism as the Memory Filters panel
 * (no Apply button; the Choose Provider screen re-applies on resume). The
 * Choose Provider screen resets it when it opens, so filters always start
 * from the default view.
 *
 * Sorting: each set dropdown contributes a sort key, applied in the panel's
 * listed order (Input Price, Output Price, Latency, Throughput, Uptime).
 * Endpoints with an unknown value for a key sort after known ones regardless
 * of direction. Every sort starts internally unset (NONE) — the dropdowns
 * never show a visible "Default" option. Alphabetical order is the base and
 * final tiebreak, A to Z unless flipped to Z to A.
 */
object ProviderFilterState {
    /** Base/tiebreak order. True = A to Z (the default), false = Z to A. */
    var alphaAToZ: Boolean = true
    var sortInputPrice: SortDirection = SortDirection.NONE
    var sortOutputPrice: SortDirection = SortDirection.NONE
    var quantization: String? = null
    var sortLatency: SortDirection = SortDirection.NONE
    var sortThroughput: SortDirection = SortDirection.NONE
    var sortUptime: SortDirection = SortDirection.NONE
    var requireTools: Boolean = false
    var requireCaching: Boolean = false
    var requireZdr: Boolean = false

    fun reset() {
        alphaAToZ = true
        sortInputPrice = SortDirection.NONE
        sortOutputPrice = SortDirection.NONE
        quantization = null
        sortLatency = SortDirection.NONE
        sortThroughput = SortDirection.NONE
        sortUptime = SortDirection.NONE
        requireTools = false
        requireCaching = false
        requireZdr = false
    }

    /** Filter and sort [endpoints] according to the current state. */
    fun apply(endpoints: List<ProviderEndpointInfo>): List<ProviderEndpointInfo> {
        var result = endpoints.asSequence()

        quantization?.let { q -> result = result.filter { it.quantization == q } }
        if (requireTools) result = result.filter { it.supportsTools == true }
        if (requireCaching) result = result.filter { it.supportsCaching == true }
        if (requireZdr) result = result.filter { it.zdr == true }

        val sorts: List<Pair<SortDirection, (ProviderEndpointInfo) -> Double?>> = listOf(
            sortInputPrice to { e: ProviderEndpointInfo -> e.promptPrice },
            sortOutputPrice to { e: ProviderEndpointInfo -> e.completionPrice },
            sortLatency to { e: ProviderEndpointInfo -> e.latency },
            sortThroughput to { e: ProviderEndpointInfo -> e.throughput },
            sortUptime to { e: ProviderEndpointInfo -> e.uptime }
        ).filter { it.first != SortDirection.NONE }

        val comparator = Comparator<ProviderEndpointInfo> { a, b ->
            for ((direction, key) in sorts) {
                val va = key(a)
                val vb = key(b)
                // Unknown values always sort after known ones.
                if (va == null && vb == null) continue
                if (va == null) return@Comparator 1
                if (vb == null) return@Comparator -1
                val cmp = when (direction) {
                    SortDirection.HIGH_TO_LOW -> vb.compareTo(va)
                    else -> va.compareTo(vb)
                }
                if (cmp != 0) return@Comparator cmp
            }
            val alpha = a.providerName.compareTo(b.providerName, ignoreCase = true)
            if (alphaAToZ) alpha else -alpha
        }

        return result.sortedWith(comparator).toList()
    }
}
