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

class ReasoningVariantMarkersTest {

    @Test
    fun openRouterThinkingVariantSuffixIsWeakEvidence() {
        val cap = ReasoningVariantMarkers.fromModelId(
            "anthropic/claude-3.7-sonnet:thinking",
            ReasoningRequestFormat.OPENROUTER
        )!!
        assertEquals(ReasoningSupport.KNOWN, cap.support)
        assertTrue(cap.canReturnVisibleReasoning)
        // A marker never establishes an effort ladder.
        assertFalse(cap.effortConfigurable)
        assertEquals(CapabilitySource.VARIANT_MARKER, cap.source)
    }

    @Test
    fun genericThinkingVariantDoesNotPromiseVisibleReasoning() {
        val cap = ReasoningVariantMarkers.fromModelId(
            "vendor/model:thinking",
            ReasoningRequestFormat.OPENAI_COMPATIBLE
        )!!
        assertTrue(cap.isReasoningCapable)
        assertFalse(cap.canReturnVisibleReasoning)
    }

    @Test
    fun genericSubstringsAreNotMarkers() {
        // §7.7: "thinking"/"reasoning"/"r1"/"deep"/"pro" as loose substrings
        // must never classify. Only the structural ":thinking" variant suffix.
        assertNull(ReasoningVariantMarkers.fromModelId("my-thinking-model"))
        assertNull(ReasoningVariantMarkers.fromModelId("reasoning-x"))
        assertNull(ReasoningVariantMarkers.fromModelId("qwen-r1-preview"))
        assertNull(ReasoningVariantMarkers.fromModelId("gpt-4o"))
        assertNull(ReasoningVariantMarkers.fromModelId(""))
        assertNull(ReasoningVariantMarkers.fromModelId(null))
    }
}
