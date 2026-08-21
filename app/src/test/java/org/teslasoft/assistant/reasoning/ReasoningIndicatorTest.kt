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
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningIndicatorTest {

    private fun configurable(canDisable: Boolean = true) = ReasoningCapability(
        support = ReasoningSupport.KNOWN,
        effortConfigurable = true,
        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        canDisableReasoning = canDisable,
        canReturnVisibleReasoning = true,
        source = CapabilitySource.PROVIDER_METADATA
    )

    private val mandatoryNoEffort = ReasoningCapability(
        support = ReasoningSupport.KNOWN,
        effortConfigurable = false,
        supportedEfforts = emptyList(),
        canDisableReasoning = false,
        canReturnVisibleReasoning = true,
        source = CapabilitySource.PROVIDER_ADAPTER
    )

    @Test
    fun nonReasoningPathHasNoIndicator() {
        assertNull(ReasoningIndicator.forGeneration(ReasoningCapability.UNKNOWN, ReasoningEffort.AUTO))
        assertNull(ReasoningIndicator.forGeneration(ReasoningCapability.ABSENT, ReasoningEffort.HIGH))
    }

    @Test
    fun autoOnAConfigurableModelIsAutomatic() {
        assertEquals(
            ReasoningIndicator.AUTOMATIC,
            ReasoningIndicator.forGeneration(configurable(), ReasoningEffort.AUTO)
        )
    }

    @Test
    fun explicitLevelsMapAcross() {
        assertEquals(ReasoningIndicator.LOW, ReasoningIndicator.forGeneration(configurable(), ReasoningEffort.LOW))
        assertEquals(ReasoningIndicator.MEDIUM, ReasoningIndicator.forGeneration(configurable(), ReasoningEffort.MEDIUM))
        assertEquals(ReasoningIndicator.HIGH, ReasoningIndicator.forGeneration(configurable(), ReasoningEffort.HIGH))
        assertEquals(ReasoningIndicator.MINIMAL, ReasoningIndicator.forGeneration(configurable(), ReasoningEffort.MINIMAL))
    }

    @Test
    fun offMapsToOffWhenReasoningDisabled() {
        assertEquals(
            ReasoningIndicator.OFF,
            ReasoningIndicator.forGeneration(configurable(), ReasoningEffort.OFF)
        )
    }

    @Test
    fun reasoningModelWithNoAdjustableLevelIsFixed() {
        // Even when the requested effort is Auto, an unconfigurable reasoning
        // model shows the fixed/locked indicator, not automatic.
        assertEquals(
            ReasoningIndicator.FIXED,
            ReasoningIndicator.forGeneration(mandatoryNoEffort, ReasoningEffort.AUTO)
        )
    }

    @Test
    fun tokensRoundTrip() {
        for (indicator in ReasoningIndicator.entries) {
            assertEquals(indicator, ReasoningIndicator.fromToken(indicator.token))
        }
        assertEquals(ReasoningIndicator.MEDIUM, ReasoningIndicator.fromToken("  MEDIUM "))
        assertNull(ReasoningIndicator.fromToken(null))
        assertNull(ReasoningIndicator.fromToken(""))
        assertNull(ReasoningIndicator.fromToken("nonsense"))
    }
}
