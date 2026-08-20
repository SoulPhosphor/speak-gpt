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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningSettingsResolverTest {

    private val fullControl = ReasoningCapability(
        support = ReasoningSupport.KNOWN,
        effortConfigurable = true,
        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        canDisableReasoning = true,
        canReturnVisibleReasoning = true,
        source = CapabilitySource.PROVIDER_METADATA
    )

    @Test
    fun conversationOverrideWinsOverFavorite() {
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = ReasoningEffort.HIGH,
            favoriteEffort = ReasoningEffort.LOW,
            favoriteShowReasoning = true,
            capability = fullControl
        )
        assertEquals(ReasoningEffort.HIGH, r.effort)
        assertEquals(ResolvedReasoning.Source.CONVERSATION_OVERRIDE, r.source)
        assertNull(r.clampedFrom)
    }

    @Test
    fun favoriteDefaultUsedWhenNoOverride() {
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = null,
            favoriteEffort = ReasoningEffort.MEDIUM,
            favoriteShowReasoning = null,
            capability = fullControl
        )
        assertEquals(ReasoningEffort.MEDIUM, r.effort)
        assertEquals(ResolvedReasoning.Source.FAVORITE_DEFAULT, r.source)
        assertTrue(r.showReasoning) // default On when favorite value absent
    }

    @Test
    fun autoWhenNeitherOverrideNorFavorite() {
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = null,
            favoriteEffort = null,
            favoriteShowReasoning = null,
            capability = fullControl
        )
        assertEquals(ReasoningEffort.AUTO, r.effort)
        assertEquals(ResolvedReasoning.Source.DEFAULT_AUTO, r.source)
        assertFalse(r.sendsExplicitLevel)
    }

    @Test
    fun autoIsARealPersistedOverrideNotAnInheritSignal() {
        // A conversation that explicitly chose Auto must NOT fall back to the
        // favorite's Low — Auto is its own owned choice (§7.9).
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = ReasoningEffort.AUTO,
            favoriteEffort = ReasoningEffort.LOW,
            favoriteShowReasoning = true,
            capability = fullControl
        )
        assertEquals(ReasoningEffort.AUTO, r.effort)
        assertEquals(ResolvedReasoning.Source.CONVERSATION_OVERRIDE, r.source)
    }

    @Test
    fun unsupportedSavedLevelClampsToAutoWithDiagnostic() {
        val highOnly = fullControl.copy(supportedEfforts = listOf(ReasoningEffort.HIGH))
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = ReasoningEffort.MEDIUM, // no longer supported
            favoriteEffort = null,
            favoriteShowReasoning = true,
            capability = highOnly
        )
        assertEquals(ReasoningEffort.AUTO, r.effort)
        assertEquals(ReasoningEffort.MEDIUM, r.clampedFrom)
    }

    @Test
    fun offClampsToAutoWhenReasoningIsMandatory() {
        val mandatory = fullControl.copy(canDisableReasoning = false)
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = ReasoningEffort.OFF,
            favoriteEffort = null,
            favoriteShowReasoning = true,
            capability = mandatory
        )
        assertEquals(ReasoningEffort.AUTO, r.effort)
        assertEquals(ReasoningEffort.OFF, r.clampedFrom)
    }

    @Test
    fun offKeptWhenDisableSupported() {
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = ReasoningEffort.OFF,
            favoriteEffort = null,
            favoriteShowReasoning = true,
            capability = fullControl
        )
        assertEquals(ReasoningEffort.OFF, r.effort)
        assertTrue(r.disablesReasoning)
        assertNull(r.clampedFrom)
    }

    @Test
    fun nonReasoningPathForcesAutoButKeepsShowReasoning() {
        // Conversation temporarily on a non-reasoning model (§7.5): the saved
        // preference is reported as clampedFrom (preserved upstream), effort is
        // forced to Auto so no reasoning param is sent to a plain model.
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = ReasoningEffort.HIGH,
            favoriteEffort = null,
            favoriteShowReasoning = false,
            capability = ReasoningCapability.UNKNOWN
        )
        assertEquals(ReasoningEffort.AUTO, r.effort)
        assertEquals(ReasoningEffort.HIGH, r.clampedFrom)
        assertFalse(r.showReasoning)
    }

    @Test
    fun showReasoningDefaultsOnWhenUnset() {
        val r = ReasoningSettingsResolver.resolve(
            conversationOverride = null,
            favoriteEffort = null,
            favoriteShowReasoning = null,
            capability = fullControl
        )
        assertTrue(r.showReasoning)
    }
}
