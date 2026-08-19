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

class ReasoningCapabilityStoreTest {

    private val full = ReasoningCapability(
        support = ReasoningSupport.KNOWN,
        effortConfigurable = true,
        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        canDisableReasoning = true,
        canReturnVisibleReasoning = true,
        tokenBudgetSupported = true,
        source = CapabilitySource.PROVIDER_METADATA
    )

    @Test
    fun roundTripsAKnownCapability() {
        val json = ReasoningCapabilityStore.set(ReasoningCapabilityStore.EMPTY, "x/y", full)
        val back = ReasoningCapabilityStore.get(json, "x/y")
        assertEquals(full.support, back.support)
        assertEquals(full.effortConfigurable, back.effortConfigurable)
        assertEquals(full.supportedEfforts, back.supportedEfforts)
        assertEquals(full.canDisableReasoning, back.canDisableReasoning)
        assertEquals(full.canReturnVisibleReasoning, back.canReturnVisibleReasoning)
        assertEquals(full.tokenBudgetSupported, back.tokenBudgetSupported)
        assertEquals(full.source, back.source)
    }

    @Test
    fun unrecordedModelReadsBackUnknownNotAbsent() {
        assertEquals(ReasoningCapability.UNKNOWN, ReasoningCapabilityStore.get(ReasoningCapabilityStore.EMPTY, "nope"))
        assertEquals(ReasoningCapability.UNKNOWN, ReasoningCapabilityStore.get(null, "nope"))
    }

    @Test
    fun uncertaintyIsNeverPersisted() {
        val withEntry = ReasoningCapabilityStore.set(ReasoningCapabilityStore.EMPTY, "x/y", full)
        assertFalse(ReasoningCapabilityStore.isEmpty(withEntry))
        // Recording UNKNOWN removes the entry rather than storing "nothing".
        val cleared = ReasoningCapabilityStore.set(withEntry, "x/y", ReasoningCapability.UNKNOWN)
        assertEquals(ReasoningCapabilityStore.EMPTY, cleared)
        assertTrue(ReasoningCapabilityStore.isEmpty(cleared))
    }

    @Test
    fun includeOnlyCapabilityRoundTripsWithoutEfforts() {
        val visibleOnly = ReasoningCapability(
            support = ReasoningSupport.KNOWN,
            effortConfigurable = false,
            canReturnVisibleReasoning = true,
            source = CapabilitySource.PROVIDER_METADATA
        )
        val json = ReasoningCapabilityStore.set(ReasoningCapabilityStore.EMPTY, "d/r1", visibleOnly)
        val back = ReasoningCapabilityStore.get(json, "d/r1")
        assertFalse(back.effortConfigurable)
        assertTrue(back.supportedEfforts.isEmpty())
        assertTrue(back.canReturnVisibleReasoning)
    }

    @Test
    fun blankModelIdIsANoOp() {
        assertEquals(ReasoningCapabilityStore.EMPTY, ReasoningCapabilityStore.set(ReasoningCapabilityStore.EMPTY, "", full))
    }

    @Test
    fun multipleModelsCoexist() {
        var json = ReasoningCapabilityStore.set(ReasoningCapabilityStore.EMPTY, "a", full)
        json = ReasoningCapabilityStore.set(json, "b", full)
        assertTrue(ReasoningCapabilityStore.get(json, "a").isReasoningCapable)
        assertTrue(ReasoningCapabilityStore.get(json, "b").isReasoningCapable)
    }
}
