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
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningCapabilityTest {

    private fun fullControl() = ReasoningCapability(
        support = ReasoningSupport.KNOWN,
        effortConfigurable = true,
        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        canDisableReasoning = true,
        canReturnVisibleReasoning = true,
        source = CapabilitySource.PROVIDER_METADATA
    )

    @Test
    fun thinkingChoicesLeadWithAutoAndTrailWithOffWhenDisableable() {
        val choices = fullControl().thinkingChoices()
        assertEquals(
            listOf(
                ReasoningEffort.AUTO,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.OFF
            ),
            choices
        )
    }

    @Test
    fun mandatoryReasoningOffersNoOffChoice() {
        val mandatory = fullControl().copy(canDisableReasoning = false)
        assertFalse(mandatory.thinkingChoices().contains(ReasoningEffort.OFF))
        assertFalse(mandatory.supports(ReasoningEffort.OFF))
    }

    @Test
    fun notEffortConfigurableOffersNoDropdown() {
        val visibleOnly = ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            canReturnVisibleReasoning = true,
            source = CapabilitySource.PROVIDER_METADATA
        )
        assertTrue(visibleOnly.thinkingChoices().isEmpty())
        // A model that reasons and returns reasoning is still capable and still
        // worth a settings screen (Show Reasoning), just no Thinking dropdown.
        assertTrue(visibleOnly.isReasoningCapable)
        assertTrue(visibleOnly.hasConfigurableSetting)
    }

    @Test
    fun disableOnlyModelExposesAutoAndOffWithoutALadder() {
        // Kimi-like: reasons, no effort ladder, but reasoning can be turned off.
        val disableOnly = ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            supportedEfforts = emptyList(),
            canDisableReasoning = true,
            canReturnVisibleReasoning = true,
            source = CapabilitySource.PROVIDER_METADATA
        )
        assertEquals(
            listOf(ReasoningEffort.AUTO, ReasoningEffort.OFF),
            disableOnly.thinkingChoices()
        )
        assertTrue(disableOnly.isEffortAdjustable)
        assertTrue(disableOnly.hasConfigurableSetting)
        assertTrue(disableOnly.supports(ReasoningEffort.OFF))
        // On/Off with no mandatory evidence is not "Fixed".
        assertFalse(disableOnly.isFixedReasoning)
    }

    @Test
    fun disableOnlyIsConfigurableEvenWithoutReturnableReasoning() {
        // The only user control is On/Off, and no visible reasoning is returned.
        // hasConfigurableSetting must still be true (On/Off is a real setting).
        val disableOnlyNoVisible = ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            canDisableReasoning = true,
            canReturnVisibleReasoning = false,
            source = CapabilitySource.PROVIDER_METADATA
        )
        assertTrue(disableOnlyNoVisible.hasConfigurableSetting)
        assertFalse(disableOnlyNoVisible.isFixedReasoning)
    }

    @Test
    fun fixedRequiresMandatoryEvidenceNotJustAbsentControls() {
        // No ladder, no Off, no mandatory evidence → unknown config, NOT fixed.
        val unknownConfig = ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            canDisableReasoning = false,
            canReturnVisibleReasoning = true,
            source = CapabilitySource.OBSERVED_RESPONSE
        )
        assertFalse(unknownConfig.isEffortAdjustable)
        assertFalse(unknownConfig.isFixedReasoning)
        assertTrue(unknownConfig.thinkingChoices().isEmpty())

        // Same shape, but positively known mandatory → fixed.
        val mandatory = unknownConfig.copy(
            source = CapabilitySource.PROVIDER_ADAPTER,
            reasoningMandatory = true
        )
        assertTrue(mandatory.isFixedReasoning)
        assertTrue(mandatory.thinkingChoices().isEmpty())
    }

    @Test
    fun autoAlwaysSupportedEvenWithoutEffortControl() {
        assertTrue(ReasoningCapability.UNKNOWN.supports(ReasoningEffort.AUTO))
        assertTrue(ReasoningCapability.ABSENT.supports(ReasoningEffort.AUTO))
    }

    @Test
    fun supportsRejectsLevelsOutsideTheAdvertisedSet() {
        val cap = fullControl().copy(
            supportedEfforts = listOf(ReasoningEffort.HIGH) // e.g. gpt-5-pro
        )
        assertTrue(cap.supports(ReasoningEffort.HIGH))
        assertFalse(cap.supports(ReasoningEffort.MEDIUM))
        assertFalse(cap.supports(ReasoningEffort.MINIMAL))
    }

    @Test
    fun unknownAndAbsentAreNotCapable() {
        assertFalse(ReasoningCapability.UNKNOWN.isReasoningCapable)
        assertFalse(ReasoningCapability.ABSENT.isReasoningCapable)
        assertEquals(ReasoningSupport.UNKNOWN, ReasoningCapability.UNKNOWN.support)
        assertEquals(ReasoningSupport.ABSENT, ReasoningCapability.ABSENT.support)
    }
}
