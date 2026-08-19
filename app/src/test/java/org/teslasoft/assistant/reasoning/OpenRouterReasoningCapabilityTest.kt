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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterReasoningCapabilityTest {

    private fun entry(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun reasoningObjectMarkerGivesFullControl() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","supported_parameters":["max_tokens","reasoning","tools"]}""")
        )!!
        assertEquals(ReasoningSupport.KNOWN, cap.support)
        assertTrue(cap.effortConfigurable)
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            cap.supportedEfforts
        )
        assertTrue(cap.canDisableReasoning)
        assertTrue(cap.canReturnVisibleReasoning)
        assertTrue(cap.tokenBudgetSupported)
        assertEquals(CapabilitySource.PROVIDER_METADATA, cap.source)
    }

    @Test
    fun includeReasoningOnlyMeansReturnableButNotConfigurable() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"id":"x/y","supported_parameters":["include_reasoning"]}""")
        )!!
        assertEquals(ReasoningSupport.KNOWN, cap.support)
        assertFalse(cap.effortConfigurable)
        assertTrue(cap.supportedEfforts.isEmpty())
        assertFalse(cap.canDisableReasoning)
        assertTrue(cap.canReturnVisibleReasoning)
    }

    @Test
    fun noReasoningMarkerFallsThroughAsNull() {
        assertNull(
            OpenRouterReasoningCapability.fromModelEntry(
                entry("""{"id":"x/y","supported_parameters":["max_tokens","tools","temperature"]}""")
            )
        )
    }

    @Test
    fun missingOrMalformedMetadataIsNullNeverAbsent() {
        assertNull(OpenRouterReasoningCapability.fromModelEntry(null))
        assertNull(OpenRouterReasoningCapability.fromModelEntry(entry("""{"id":"x/y"}""")))
        assertNull(OpenRouterReasoningCapability.fromModelEntry(entry("""{"supported_parameters":"reasoning"}""")))
        assertNull(OpenRouterReasoningCapability.fromModelEntry(entry("""{"supported_parameters":[]}""")))
    }

    @Test
    fun markerMatchingIsCaseInsensitive() {
        val cap = OpenRouterReasoningCapability.fromModelEntry(
            entry("""{"supported_parameters":["Reasoning"]}""")
        )
        assertTrue(cap != null && cap.effortConfigurable)
    }
}
