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

class ReasoningEffortTest {

    @Test
    fun serializedFormsAreStableAndRoundTrip() {
        for (effort in ReasoningEffort.entries) {
            assertEquals(effort, ReasoningEffort.fromSerialized(effort.serialized))
        }
    }

    @Test
    fun exactStoredStringsAreLocked() {
        // These strings are persisted; a change would silently reset users.
        assertEquals("auto", ReasoningEffort.AUTO.serialized)
        assertEquals("off", ReasoningEffort.OFF.serialized)
        assertEquals("minimal", ReasoningEffort.MINIMAL.serialized)
        assertEquals("low", ReasoningEffort.LOW.serialized)
        assertEquals("medium", ReasoningEffort.MEDIUM.serialized)
        assertEquals("high", ReasoningEffort.HIGH.serialized)
        assertEquals("xhigh", ReasoningEffort.XHIGH.serialized)
        assertEquals("max", ReasoningEffort.MAX.serialized)
    }

    @Test
    fun parsingIsCaseAndWhitespaceTolerant() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromSerialized("  HIGH "))
        assertEquals(ReasoningEffort.AUTO, ReasoningEffort.fromSerialized("Auto"))
    }

    @Test
    fun unknownBlankOrNullReturnsNullSoCallersFallBack() {
        assertNull(ReasoningEffort.fromSerialized(null))
        assertNull(ReasoningEffort.fromSerialized(""))
        assertNull(ReasoningEffort.fromSerialized("   "))
        assertNull(ReasoningEffort.fromSerialized("garbage"))
    }

    @Test
    fun isExplicitLevelExcludesAutoAndOff() {
        assertFalse(ReasoningEffort.AUTO.isExplicitLevel)
        assertFalse(ReasoningEffort.OFF.isExplicitLevel)
        assertTrue(ReasoningEffort.LOW.isExplicitLevel)
        assertTrue(ReasoningEffort.MEDIUM.isExplicitLevel)
        assertTrue(ReasoningEffort.HIGH.isExplicitLevel)
        assertTrue(ReasoningEffort.MINIMAL.isExplicitLevel)
        assertTrue(ReasoningEffort.XHIGH.isExplicitLevel)
        assertTrue(ReasoningEffort.MAX.isExplicitLevel)
    }
}
