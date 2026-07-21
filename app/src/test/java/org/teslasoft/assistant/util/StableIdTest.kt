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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableIdTest {

    @Test fun newIdCarriesPrefixAndIsUnique() {
        val a = StableId.newId("p-")
        val b = StableId.newId("p-")
        assertTrue(a.startsWith("p-"))
        assertNotEquals("two ids are distinct", a, b)
    }

    @Test fun resolveMintsWhenBlank() {
        val id = StableId.resolve("", "ep-")
        assertTrue(id.startsWith("ep-"))
    }

    @Test fun resolveKeepsAnExistingIdVerbatim() {
        // A legacy hashed id, or a previously-minted stable id, is kept as-is —
        // never regenerated (that would orphan every reference).
        assertEquals("existinghash123", StableId.resolve("existinghash123", "ep-"))
        assertEquals("ap-abc", StableId.resolve("ap-abc", "ap-"))
    }
}
