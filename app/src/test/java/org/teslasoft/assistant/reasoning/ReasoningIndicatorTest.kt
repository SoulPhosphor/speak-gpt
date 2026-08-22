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
        source = CapabilitySource.PROVIDER_ADAPTER,
        // Positively known mandatory — the only case that is truly "Fixed".
        reasoningMandatory = true
    )

    // Reasons, but the app has NOT established its controls (e.g. an observed
    // response). Not adjustable, not known mandatory → automatic, never fixed.
    private val unknownConfig = ReasoningCapability(
        support = ReasoningSupport.KNOWN,
        effortConfigurable = false,
        supportedEfforts = emptyList(),
        canDisableReasoning = false,
        canReturnVisibleReasoning = true,
        source = CapabilitySource.OBSERVED_RESPONSE,
        reasoningMandatory = false
    )

    // Reasons with no effort ladder but reasoning can be turned On/Off.
    private val disableOnly = ReasoningCapability(
        support = ReasoningSupport.KNOWN,
        effortConfigurable = false,
        supportedEfforts = emptyList(),
        canDisableReasoning = true,
        canReturnVisibleReasoning = true,
        source = CapabilitySource.PROVIDER_METADATA
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
    fun mandatoryModelWithNoAdjustableLevelIsFixed() {
        // Even when the requested effort is Auto, a POSITIVELY-KNOWN mandatory
        // model with no controls shows the fixed/locked indicator.
        assertEquals(
            ReasoningIndicator.FIXED,
            ReasoningIndicator.forGeneration(mandatoryNoEffort, ReasoningEffort.AUTO)
        )
    }

    @Test
    fun unknownConfigModelIsAutomaticNotFixed() {
        // Reasons, but the app has not established that reasoning is mandatory or
        // unadjustable. It must NOT be reported as fixed (§7.7 #4).
        assertEquals(
            ReasoningIndicator.AUTOMATIC,
            ReasoningIndicator.forGeneration(unknownConfig, ReasoningEffort.AUTO)
        )
    }

    @Test
    fun disableOnlyModelMapsAutoAndOffNotFixed() {
        // A model with no ladder but an On/Off control is adjustable, so its
        // indicator follows the effective effort rather than the locked glyph.
        assertEquals(
            ReasoningIndicator.AUTOMATIC,
            ReasoningIndicator.forGeneration(disableOnly, ReasoningEffort.AUTO)
        )
        assertEquals(
            ReasoningIndicator.OFF,
            ReasoningIndicator.forGeneration(disableOnly, ReasoningEffort.OFF)
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
