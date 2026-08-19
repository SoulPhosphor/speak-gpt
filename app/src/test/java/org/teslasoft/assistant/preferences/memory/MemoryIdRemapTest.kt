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

package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-reference remap/ambiguity rule ([MemoryIdRemap]): a reference to a
 * repaired memory follows it only when the old id names exactly one memory.
 */
class MemoryIdRemapTest {

    @Test
    fun keptIdResolvesToItself() {
        val res = MemoryIdRemap.buildResolution(
            listOf(MemoryIdRemap.Entry("m-a", "m-a", remapped = false, collidesWithLive = false))
        )
        val r = MemoryIdRemap.resolve(res, "m-a")
        assertEquals("m-a", r.value); assertFalse(r.dropped)
    }

    @Test
    fun malformedRemapResolvesToNewId() {
        // Case 2: a malformed id that names only the incoming memory remaps cleanly.
        val res = MemoryIdRemap.buildResolution(
            listOf(MemoryIdRemap.Entry("garbage", "m-new", remapped = true, collidesWithLive = false))
        )
        val r = MemoryIdRemap.resolve(res, "garbage")
        assertEquals("m-new", r.value); assertFalse(r.dropped)
    }

    @Test
    fun liveCollisionRemapIsAmbiguousAndDropped() {
        // Case 1: the old id is still held by a live memory, so a reference to it
        // could mean either memory — do not guess.
        val res = MemoryIdRemap.buildResolution(
            listOf(MemoryIdRemap.Entry("m-x", "m-new", remapped = true, collidesWithLive = true))
        )
        val r = MemoryIdRemap.resolve(res, "m-x")
        assertNull(r.value); assertTrue(r.dropped)
    }

    @Test
    fun duplicateIncomingIdIsAmbiguous() {
        val res = MemoryIdRemap.buildResolution(
            listOf(
                MemoryIdRemap.Entry("m-dup", "m-1", remapped = true, collidesWithLive = false),
                MemoryIdRemap.Entry("m-dup", "m-2", remapped = true, collidesWithLive = false)
            )
        )
        val r = MemoryIdRemap.resolve(res, "m-dup")
        assertNull(r.value); assertTrue(r.dropped)
    }

    @Test
    fun unknownIdIsKeptUnchanged() {
        // A reference to a memory not in the import (an existing store memory) is
        // left as-is, and that is not a diagnostic-worthy drop.
        val res = MemoryIdRemap.buildResolution(emptyList())
        val r = MemoryIdRemap.resolve(res, "m-existing")
        assertEquals("m-existing", r.value); assertFalse(r.dropped)
    }

    @Test
    fun blankReferenceResolvesToNullWithoutDrop() {
        val res = MemoryIdRemap.buildResolution(emptyList())
        val r = MemoryIdRemap.resolve(res, null)
        assertNull(r.value); assertFalse(r.dropped)
    }
}
